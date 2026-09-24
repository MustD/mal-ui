package io.challenge_workshop.mal_ui.animelist

import io.challenge_workshop.mal_ui.mal.malClientDefaults
import io.challenge_workshop.mal_ui.session.FakeMal
import io.challenge_workshop.mal_ui.session.TEST_API_BASE_URL
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The primary seam for the Anime List: nearly everything that can be wrong is behind the pager, and
 * `:core:allTests` runs this on jvm, js, wasmJs and android.
 *
 * The assertions are on what a caller can observe — the [AnimeListContent] the pager says it is, and
 * the requests it made. Never the flags that value is decided from: which screen a state *is* is the
 * pager's answer to give, and a test that read the flags would be re-deciding it. In particular the **outgoing request is asserted, not just
 * the parsed result**: a wrong `fields` string still returns 200, and costs the UI its data with no
 * error anywhere.
 */
class AnimeListPagerTest {

    private val accessToken = "good-access"

    private fun pagerOver(
        animeList: (Int) -> AnimeListResponse,
        pageSize: Int = 50,
        watchStatus: WatchStatus? = null,
        sortOrder: AnimeListSortOrder = AnimeListSortOrder.LastUpdated,
        holdAnimeList: suspend (Int) -> Unit = {},
    ): Pair<AnimeListPager, FakeMal> {
        val mal = FakeMal(
            acceptedAccessToken = accessToken,
            animeList = animeList,
            holdAnimeList = holdAnimeList,
        )
        // No `Auth` plugin here: this test is about the pager, and the plugin's behaviour is
        // `MalSessionRefreshTest`'s. `MalSessionAnimeListTest` covers the two meeting.
        val http = HttpClient(mal.engine) {
            malClientDefaults()
            install(DefaultRequest) { headers.append(HttpHeaders.Authorization, "Bearer $accessToken") }
        }
        val pager = AnimeListPager(
            client = MalAnimeListClient(TEST_API_BASE_URL, http),
            pageSize = pageSize,
            watchStatus = watchStatus,
            sortOrder = sortOrder,
        )
        return pager to mal
    }

    private fun page(vararg entries: FakeEntry, hasMore: Boolean = false): (Int) -> AnimeListResponse =
        { AnimeListResponse.Page(entries.toList(), hasMore) }

    private val AnimeListPager.content: AnimeListContent get() = state.value.content

    /** The entries on screen, which only [AnimeListContent.Entries] has. */
    private fun AnimeListPager.shown(): AnimeListContent.Entries = assertIs(content)

    private fun AnimeListPager.ids(): List<Long> = shown().entries.map { it.animeId }

    private fun AnimeListState.tail(): AnimeListTail? = (content as? AnimeListContent.Entries)?.tail

    @Test
    fun the_first_page_asks_for_the_agreed_query() = runTest {
        val (pager, mal) = pagerOver(page(FakeEntry(1, "Cowboy Bebop")))

        pager.start()

        val url = mal.animeListRequests.single()
        assertEquals("50", url.parameters["limit"])
        assertEquals("0", url.parameters["offset"])
        assertEquals("true", url.parameters["nsfw"])
        assertEquals("list_updated_at", url.parameters["sort"])
        assertEquals(MalAnimeListClient.ANIME_LIST_FIELDS, url.parameters["fields"])
        // "All" is the *absence* of the parameter, not a sixth value — MAL takes one status or none.
        assertNull(url.parameters["status"], "an unfiltered list must send no `status` at all")
    }

    @Test
    fun each_watch_status_filter_sends_its_own_single_status_value() = runTest {
        val expected = mapOf(
            WatchStatus.Watching to "watching",
            WatchStatus.Completed to "completed",
            WatchStatus.OnHold to "on_hold",
            WatchStatus.Dropped to "dropped",
            WatchStatus.PlanToWatch to "plan_to_watch",
        )
        for ((status, wire) in expected) {
            val (pager, mal) = pagerOver(page(), watchStatus = status)

            pager.start()

            assertEquals(wire, mal.animeListRequests.single().parameters["status"], "for $status")
        }
    }

    @Test
    fun each_sort_order_sends_its_own_sort_value() = runTest {
        for (sortOrder in AnimeListSortOrder.entries) {
            val (pager, mal) = pagerOver(page(), sortOrder = sortOrder)

            pager.start()

            assertEquals(sortOrder.wireValue, mal.animeListRequests.single().parameters["sort"])
        }
    }

    @Test
    fun a_loaded_page_becomes_list_entries() = runTest {
        val (pager, _) = pagerOver(
            page(
                FakeEntry(
                    id = 1,
                    title = "Cowboy Bebop",
                    numEpisodes = 26,
                    mediaType = "tv",
                    airingStatus = "finished_airing",
                    watchStatus = "on_hold",
                    score = 9,
                    watched = 12,
                ),
            ),
        )

        pager.start()

        val entry = pager.shown().entries.single()
        assertEquals(1L, entry.animeId)
        assertEquals("Cowboy Bebop", entry.title)
        assertEquals(26, entry.totalEpisodes)
        assertEquals("tv", entry.mediaType)
        assertEquals(AiringStatus.FinishedAiring, entry.airingStatus)
        // The two `status` fields of one response, kept apart: the node's is the anime's, the
        // list_status one is the user's. Swapping them is the mistake this asserts against.
        assertEquals(WatchStatus.OnHold, entry.watchStatus)
        assertEquals(9, entry.score)
        assertEquals(12, entry.episodesWatched)
        assertEquals("https://cdn.myanimelist.net/images/anime/4/1.jpg", entry.picture?.medium)
    }

    @Test
    fun an_unknown_status_or_media_type_parses_rather_than_throwing() = runTest {
        // MAL adds things. A stored or displayed list must not become a crash because it did.
        val (pager, _) = pagerOver(
            page(
                FakeEntry(
                    id = 7,
                    title = "Something New",
                    mediaType = "a_type_mal_added_later",
                    airingStatus = "on_hiatus",
                    watchStatus = "rewatching",
                ),
            ),
        )

        pager.start()

        val entry = pager.shown().entries.single()
        assertEquals(WatchStatus.Unknown, entry.watchStatus)
        assertEquals(AiringStatus.Unknown, entry.airingStatus)
        // Not an enum at all, precisely so an unrecognised one is still displayable.
        assertEquals("a_type_mal_added_later", entry.mediaType)
    }

    @Test
    fun a_response_without_paging_next_exhausts_the_pager_and_stops_it_asking() = runTest {
        val (pager, mal) = pagerOver(page(FakeEntry(1, "Cowboy Bebop"), hasMore = false))

        pager.start()
        assertEquals(AnimeListTail.End, pager.shown().tail)

        pager.next()

        assertEquals(1, mal.animeListRequests.size, "an exhausted pager must make no further request")
    }

    @Test
    fun next_advances_the_offset_by_the_page_size_and_appends_in_order() = runTest {
        val (pager, mal) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                AnimeListResponse.Page(fakeEntries(count = 2, firstId = offset + 1L), hasMore = offset < 2)
            },
        )

        pager.start()
        pager.next()

        assertContentEquals(
            listOf(1L, 2L, 3L, 4L),
            pager.ids(),
            "the second page must be appended after the first, in order",
        )
        assertEquals(listOf("0", "2"), mal.animeListRequests.map { it.parameters["offset"] })
    }

    /**
     * A fast scroll fires the proximity trigger on every frame it is near the end, so the guard that
     * matters is not "the UI asks once" — it is that the pager answers once however often it is
     * asked.
     *
     * The second `next()` is issued while the first is genuinely suspended inside its request, which
     * is the only window in which the duplicate could happen. What is asserted is the requests MAL
     * saw, because a duplicate costs a wasted page and a doubled append, not a flag.
     */
    @Test
    fun a_page_already_in_flight_is_not_requested_twice() = runTest {
        val secondPageLanded = CompletableDeferred<Unit>()
        val (pager, mal) = pagerOver(
            pageSize = 2,
            animeList = { offset -> AnimeListResponse.Page(fakeEntries(2, firstId = offset + 1L), hasMore = true) },
            holdAnimeList = { offset -> if (offset > 0) secondPageLanded.await() },
        )
        pager.start()

        val inFlight = backgroundScope.launch { pager.next() }
        pager.state.first { it.tail() == AnimeListTail.LoadingMore }

        pager.next()

        secondPageLanded.complete(Unit)
        inFlight.join()
        assertEquals(
            listOf("0", "2"),
            mal.animeListRequests.map { it.parameters["offset"] },
            "the second `next()` landed while offset=2 was in flight and must have been a no-op",
        )
        assertContentEquals(listOf(1L, 2L, 3L, 4L), pager.ids())
    }

    /**
     * The scroll trigger keeps firing while the user sits at the bottom, so a failed later page
     * must not be re-requested by proximity alone — that is a request loop against a MAL that is
     * already failing. Recovering from it is [AnimeListPager.retry], which the user asks for.
     */
    @Test
    fun next_makes_no_request_while_a_failed_later_page_is_still_showing_its_error() = runTest {
        val (pager, mal) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                if (offset > 0) AnimeListResponse.TransportFailure
                else AnimeListResponse.Page(fakeEntries(2), hasMore = true)
            },
        )
        pager.start()
        pager.next()
        assertIs<AnimeListTail.MoreFailed>(pager.shown().tail)

        pager.next()
        pager.next()

        assertEquals(
            listOf("0", "2"),
            mal.animeListRequests.map { it.parameters["offset"] },
            "a standing 'more failed' must not be retried by scrolling",
        )
    }

    /**
     * `data: []` with a `paging.next` — two independent facts in MAL's shape, and a page that
     * appends nothing.
     *
     * Nothing downstream can get past one. The scroll trigger re-arms on the entry count changing
     * and the count does not change; with an empty screen there is nothing to scroll either. So the
     * pager asks again itself, and the screen stays on the first-page state throughout rather than
     * flipping to an empty list MAL has just contradicted.
     */
    @Test
    fun an_empty_page_that_is_not_the_end_is_paged_past_rather_than_shown() = runTest {
        lateinit var pager: AnimeListPager
        val contentWhileScanning = mutableListOf<AnimeListContent>()
        val (built, mal) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                if (offset < 4) AnimeListResponse.Page(emptyList(), hasMore = true)
                else AnimeListResponse.Page(fakeEntries(2), hasMore = false)
            },
            // Sampled from inside the second and third requests: the scan is the one window in
            // which the pager asks for a page nobody scrolled towards, and it must be showing the
            // first-page state while it does — not the "loading more" row at the bottom of a list
            // that has no entries in it.
            holdAnimeList = { offset ->
                if (offset > 0) contentWhileScanning += pager.content
            },
        )
        pager = built

        pager.start()

        assertEquals(listOf("0", "2", "4"), mal.animeListRequests.map { it.parameters["offset"] })
        assertContentEquals(listOf(1L, 2L), pager.ids())
        assertEquals(
            listOf<AnimeListContent>(AnimeListContent.FirstPageLoading, AnimeListContent.FirstPageLoading),
            contentWhileScanning,
        )
        assertEquals(AnimeListTail.End, pager.shown().tail)
    }

    /**
     * The scan is a loop over a MAL that keeps saying there is more and sending none of it, so it
     * needs a floor. Stopping is not enough on its own — stopping quietly would leave the screen on
     * a skeleton that never resolves — so it stops as the retryable failure it is.
     */
    @Test
    fun a_run_of_empty_pages_stops_asking_and_surfaces_a_retryable_error() = runTest {
        var holes = true
        val (pager, mal) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                if (holes) AnimeListResponse.Page(emptyList(), hasMore = true)
                else AnimeListResponse.Page(fakeEntries(2, firstId = offset + 1L), hasMore = false)
            },
        )

        pager.start()

        assertEquals(AnimeListPager.MAX_EMPTY_PAGE_SCAN, mal.animeListRequests.size)
        // Failed, not Empty: nothing ever landed, so this must not read as an empty Anime List.
        assertIs<AnimeListContent.FirstPageFailed>(pager.content)

        // The retry has to go back to where the run *started*, not to where it gave up. Resuming at
        // the far end of a scan asks MAL for a position past the end of a list whose entries all sit
        // before it — which comes back empty and exhausted, and would then be shown as an empty
        // account to a user who has one.
        holes = false
        pager.retry()

        assertEquals(
            "0",
            mal.animeListRequests.last().parameters["offset"],
            "the scan advanced past twenty holes, and the retry must not resume beyond them",
        )
        assertContentEquals(listOf(1L, 2L), pager.ids())
    }

    /**
     * A hole met during a [AnimeListPager.reset] must not empty the screen either.
     *
     * The entries behind a reset are the previous query's and are deliberately kept until the
     * replacement lands — a filter that flashed the screen to a skeleton on every tap is what that
     * is for — and a page with nothing in it is not a replacement.
     */
    @Test
    fun a_hole_during_a_reset_does_not_empty_the_screen_before_the_replacement_lands() = runTest {
        lateinit var pager: AnimeListPager
        var filtering = false
        val contentWhileScanning = mutableListOf<AnimeListContent>()
        val (built, _) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                when {
                    !filtering -> AnimeListResponse.Page(fakeEntries(2), hasMore = false)
                    offset == 0 -> AnimeListResponse.Page(emptyList(), hasMore = true)
                    else -> AnimeListResponse.Page(fakeEntries(2, firstId = 9), hasMore = false)
                }
            },
            // Sampled from inside the request that follows the hole: this is the window in which the
            // old slice has to still be there.
            holdAnimeList = { offset ->
                if (filtering && offset > 0) contentWhileScanning += pager.content
            },
        )
        pager = built
        pager.start()

        filtering = true
        pager.reset(watchStatus = WatchStatus.OnHold)

        val whileScanning = assertIs<AnimeListContent.Entries>(contentWhileScanning.single())
        assertEquals(listOf(1L, 2L), whileScanning.entries.map { it.animeId })
        assertTrue(whileScanning.replacing, "the old slice is on screen as the thing being replaced")
        assertContentEquals(listOf(9L, 10L), pager.ids())
    }

    /** The same hole, met halfway down a loaded list: paged past, and nothing on screen disturbed. */
    @Test
    fun an_empty_page_in_the_middle_keeps_what_is_loaded_and_pages_past_it() = runTest {
        val (pager, mal) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                when (offset) {
                    0 -> AnimeListResponse.Page(fakeEntries(2), hasMore = true)
                    2 -> AnimeListResponse.Page(emptyList(), hasMore = true)
                    else -> AnimeListResponse.Page(fakeEntries(2, firstId = 3), hasMore = false)
                }
            },
        )

        pager.start()
        pager.next()

        assertEquals(listOf("0", "2", "4"), mal.animeListRequests.map { it.parameters["offset"] })
        assertContentEquals(listOf(1L, 2L, 3L, 4L), pager.ids())
        assertEquals(AnimeListTail.End, pager.shown().tail, "a hole is not a failure")
    }

    @Test
    fun a_failed_first_page_surfaces_as_the_first_page_error_with_nothing_loaded() = runTest {
        val (pager, _) = pagerOver({ AnimeListResponse.Failure(HttpStatusCode.ServiceUnavailable) })

        pager.start()

        // Not Empty — nothing landed, so this must not read as an empty Anime List — and not a
        // failed tail, because there are no entries for one to be the bottom of.
        assertIs<AnimeListContent.FirstPageFailed>(pager.content)
        assertTrue(pager.state.value.queryControlsEnabled, "a failed filter can be picked again")
        assertFalse(pager.state.value.pagingArmed)
    }

    @Test
    fun retry_re_requests_the_same_offset_after_a_first_page_failure() = runTest {
        var fail = true
        val (pager, mal) = pagerOver({
            if (fail) AnimeListResponse.Failure() else AnimeListResponse.Page(fakeEntries(1), hasMore = false)
        })

        pager.start()
        fail = false
        pager.retry()

        assertEquals(listOf("0", "0"), mal.animeListRequests.map { it.parameters["offset"] })
        assertEquals(1, pager.shown().entries.size)
    }

    @Test
    fun a_failed_later_page_keeps_every_loaded_entry_and_retries_only_that_offset() = runTest {
        var failFrom = 2
        val (pager, mal) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                if (offset >= failFrom) AnimeListResponse.TransportFailure
                else AnimeListResponse.Page(fakeEntries(2, firstId = offset + 1L), hasMore = true)
            },
        )

        pager.start()
        pager.next()

        // Entries with a failed tail, not a failed first page: the loaded page is still good, and
        // this is not a dead screen.
        assertEquals(listOf(1L, 2L), pager.ids(), "nothing loaded may be discarded")
        assertIs<AnimeListTail.MoreFailed>(pager.shown().tail)
        assertTrue(pager.state.value.pagingArmed, "armed still — the pager is what refuses to re-ask")

        failFrom = Int.MAX_VALUE
        pager.retry()

        assertEquals(listOf("0", "2", "2"), mal.animeListRequests.map { it.parameters["offset"] })
        assertContentEquals(listOf(1L, 2L, 3L, 4L), pager.ids())
        assertEquals(AnimeListTail.Idle, pager.shown().tail)
    }

    @Test
    fun reset_refetches_from_zero_and_keeps_the_old_entries_observable_until_it_lands() = runTest {
        lateinit var pager: AnimeListPager
        var resetting = false
        val stateWhileInFlight = mutableListOf<AnimeListState>()
        val (built, mal) = pagerOver(
            pageSize = 2,
            animeList = { _ ->
                // Sampled from *inside* the request: this is exactly the window in which changing a
                // filter must not flash the screen empty.
                stateWhileInFlight += pager.state.value
                if (resetting) {
                    AnimeListResponse.Page(fakeEntries(1, firstId = 100), hasMore = false)
                } else {
                    AnimeListResponse.Page(fakeEntries(2, firstId = 1), hasMore = true)
                }
            },
        )
        pager = built

        pager.start()
        resetting = true
        pager.reset(watchStatus = WatchStatus.Completed)

        val replacing = assertIs<AnimeListContent.Entries>(
            stateWhileInFlight.last().content,
            "the previously loaded entries must still be observable while the new first page is in flight",
        )
        assertEquals(listOf(1L, 2L), replacing.entries.map { it.animeId })
        assertTrue(replacing.replacing)
        assertEquals(listOf("0", "0"), mal.animeListRequests.map { it.parameters["offset"] })
        assertEquals("completed", mal.animeListRequests.last().parameters["status"])
        assertEquals(listOf(100L), pager.ids(), "the old page is replaced, not appended")
        assertFalse(pager.shown().replacing)
        assertEquals(WatchStatus.Completed, pager.state.value.watchStatus)
        assertEquals(AnimeListTail.End, pager.shown().tail, "the new page carries no `paging.next`")
    }

    /**
     * A [AnimeListPager.reset] with neither argument keeps both — which is what Reload is.
     *
     * Reload is the way to pick up a change made on myanimelist.net, so it has to refetch the list
     * the user is *looking at* rather than the default one. Both arguments default to what is already
     * on screen, and a `reset()` that defaulted them to `null` and `LastUpdated` instead would look
     * identical on a screen nobody had touched — which is every screen a test starts on.
     */
    @Test
    fun reset_with_no_arguments_refetches_the_list_on_screen_from_its_start() = runTest {
        val (pager, mal) = pagerOver(pageSize = 2, animeList = page(FakeEntry(1, "Cowboy Bebop")))

        pager.reset(watchStatus = WatchStatus.Watching, sortOrder = AnimeListSortOrder.Title)
        pager.next()
        mal.animeListRequests.clear()

        pager.reset()

        assertEquals(1, mal.animeListRequests.size, "Reload asks once")
        assertEquals("watching", mal.animeListRequests.single().parameters["status"])
        assertEquals("anime_title", mal.animeListRequests.single().parameters["sort"])
        assertEquals("0", mal.animeListRequests.single().parameters["offset"])
        assertEquals(WatchStatus.Watching, pager.state.value.watchStatus)
        assertEquals(AnimeListSortOrder.Title, pager.state.value.sortOrder)
    }

    /**
     * The other half of a filter change: the pages *after* the first one.
     *
     * `offset` is driven from here, so a reset that forgot to put it back would page the new list
     * from wherever the old one had got to — silently skipping the first N entries of the slice the
     * user just asked for. Every request after the change also has to keep carrying the new
     * `status`, which is the part a pager holding the filter only at request time gets wrong.
     */
    @Test
    fun paging_continues_from_the_new_list_after_a_filter_change() = runTest {
        var filtered = false
        val (pager, mal) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                val firstId = if (filtered) 100L + offset else 1L + offset
                AnimeListResponse.Page(fakeEntries(2, firstId = firstId), hasMore = true)
            },
        )
        pager.start()
        pager.next()

        filtered = true
        pager.reset(watchStatus = WatchStatus.Watching)
        pager.next()

        assertContentEquals(
            listOf(100L, 101L, 102L, 103L),
            pager.ids(),
            "the new list must page from its own start, with nothing of the old one left",
        )
        assertEquals(
            listOf("0", "2", "0", "2"),
            mal.animeListRequests.map { it.parameters["offset"] },
            "`offset` went back to 0 with the filter, then advanced through the new list",
        )
        assertEquals(
            listOf(null, null, "watching", "watching"),
            mal.animeListRequests.map { it.parameters["status"] },
            "every request after the change carries the new filter, not just the first",
        )
    }

    /**
     * The filter can change while a later page is still in flight — the next page is fetched by
     * proximity, so a prefetch is running for most of the time the user spends scrolling, and a chip
     * disabled only while the *first* page loads does not cover it.
     *
     * Two things must hold. The reset must actually make its request rather than losing to the
     * in-flight one's claim on the loading slot; and the superseded page must not land, because
     * appending a page of the old filter's list under the new filter is the one failure a user would
     * read as the filter simply not working.
     */
    @Test
    fun a_filter_change_supersedes_a_page_that_is_still_in_flight() = runTest {
        val releaseStalePage = CompletableDeferred<Unit>()
        var filtered = false
        val (pager, mal) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                if (filtered) AnimeListResponse.Page(fakeEntries(2, firstId = 100), hasMore = false)
                else AnimeListResponse.Page(fakeEntries(2, firstId = offset + 1L), hasMore = true)
            },
            holdAnimeList = { offset -> if (offset > 0) releaseStalePage.await() },
        )
        pager.start()

        val stale = backgroundScope.launch { pager.next() }
        pager.state.first { it.tail() == AnimeListTail.LoadingMore }

        filtered = true
        pager.reset(watchStatus = WatchStatus.Dropped)

        assertContentEquals(
            listOf(100L, 101L),
            pager.ids(),
            "the replacement page landed even though offset=2 still held the loading slot",
        )

        releaseStalePage.complete(Unit)
        stale.join()

        assertContentEquals(
            listOf(100L, 101L),
            pager.ids(),
            "the superseded page must not append itself to the list of a different filter",
        )
        assertEquals(AnimeListTail.End, pager.shown().tail, "and must not undo the new page's exhaustion either")
        assertEquals("dropped", mal.animeListRequests.last().parameters["status"])
    }

    /**
     * A filter change that fails must not leave the *previous* filter's entries under the chip the
     * user just tapped.
     *
     * The old entries staying observable is deliberate and is what stops the screen flashing empty
     * — but only until the replacement resolves. Resolving as a failure and keeping them shows one
     * slice of the list labelled as another, which is worse than showing nothing: there is no way
     * for the user to tell it happened.
     */
    @Test
    fun a_failed_filter_change_discards_the_slice_it_was_replacing() = runTest {
        var failing = false
        val (pager, _) = pagerOver(
            pageSize = 2,
            animeList = { _ ->
                if (failing) AnimeListResponse.Failure() else AnimeListResponse.Page(fakeEntries(2), hasMore = true)
            },
        )
        pager.start()
        assertEquals(2, pager.shown().entries.size)

        failing = true
        pager.reset(watchStatus = WatchStatus.PlanToWatch)

        // Failed, and nothing else: not the unfiltered entries — they are not the Plan to Watch slice,
        // and the chip now says they are — and not Empty, because nothing landed.
        assertIs<AnimeListContent.FirstPageFailed>(pager.content)
    }

    /**
     * A Sort Order change is a filter change in every respect that matters here: the ordering is
     * MAL's, so the pages already loaded are of an order the user has stopped asking for, and
     * `offset` counts positions in whichever order the query names.
     *
     * The failure this pins is the quiet one — carrying the old `offset` into the new order, which
     * skips the first N entries of it and looks like a list that simply starts in the wrong place.
     */
    @Test
    fun a_sort_order_change_resets_paging_and_carries_the_new_sort_on_every_page() = runTest {
        var sorted = false
        val (pager, mal) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                val firstId = if (sorted) 100L + offset else 1L + offset
                AnimeListResponse.Page(fakeEntries(2, firstId = firstId), hasMore = true)
            },
        )
        pager.start()
        pager.next()

        sorted = true
        pager.reset(sortOrder = AnimeListSortOrder.Title)
        pager.next()

        assertContentEquals(
            listOf(100L, 101L, 102L, 103L),
            pager.ids(),
            "the re-ordered list must page from its own start, with nothing of the old order left",
        )
        assertEquals(
            listOf("0", "2", "0", "2"),
            mal.animeListRequests.map { it.parameters["offset"] },
            "`offset` went back to 0 with the Sort Order, then advanced through the new ordering",
        )
        assertEquals(
            listOf("list_updated_at", "list_updated_at", "anime_title", "anime_title"),
            mal.animeListRequests.map { it.parameters["sort"] },
            "every request after the change carries the new Sort Order, not just the first",
        )
        assertEquals(AnimeListSortOrder.Title, pager.state.value.sortOrder)
    }

    /**
     * The two controls are independent settings of one query, and a `reset` that defaulted either
     * of them back would silently drop the other's choice — a Sort Order change that also cleared
     * the filter reads as the chip having been un-tapped by itself.
     */
    @Test
    fun a_sort_order_change_keeps_the_watch_status_filter() = runTest {
        val (pager, mal) = pagerOver(page(), watchStatus = WatchStatus.Watching)

        pager.start()
        pager.reset(sortOrder = AnimeListSortOrder.Score)

        assertEquals(WatchStatus.Watching, pager.state.value.watchStatus)
        assertEquals("watching", mal.animeListRequests.last().parameters["status"])
        assertEquals("list_score", mal.animeListRequests.last().parameters["sort"])
    }

    // --- Each variant and each control decision, reached through the pager -------------------------

    @Test
    fun a_pager_that_has_asked_for_nothing_is_not_requested() = runTest {
        val (pager, _) = pagerOver(page(FakeEntry(1, "Cowboy Bebop")))

        assertEquals(AnimeListContent.NotRequested, pager.content)
        assertTrue(pager.state.value.queryControlsEnabled)
        assertFalse(pager.state.value.pagingArmed, "there is nothing to scroll towards the end of")
    }

    /** A first page with nothing behind it: the skeleton, with both controls and paging held off. */
    @Test
    fun a_first_page_in_flight_with_nothing_behind_it_is_loading_and_holds_the_controls() = runTest {
        lateinit var pager: AnimeListPager
        val whileInFlight = mutableListOf<AnimeListState>()
        val (built, _) = pagerOver(
            page(FakeEntry(1, "Cowboy Bebop")),
            holdAnimeList = { whileInFlight += pager.state.value },
        )
        pager = built

        pager.start()

        val inFlight = whileInFlight.single()
        assertEquals(AnimeListContent.FirstPageLoading, inFlight.content)
        assertFalse(inFlight.queryControlsEnabled)
        assertFalse(inFlight.pagingArmed)
    }

    /**
     * MAL ending a list with nothing in it is [AnimeListContent.Empty] whatever the filter — and the
     * Watch Status beside it is what lets the screen say which empty it is.
     */
    @Test
    fun a_finished_list_with_nothing_in_it_is_empty_under_its_own_filter() = runTest {
        for (watchStatus in listOf(null, WatchStatus.OnHold)) {
            val (pager, _) = pagerOver(page(), watchStatus = watchStatus)

            pager.start()

            assertEquals(AnimeListContent.Empty, pager.content, "for $watchStatus")
            assertEquals(watchStatus, pager.state.value.watchStatus)
            assertTrue(pager.state.value.queryControlsEnabled)
            assertFalse(pager.state.value.pagingArmed, "an empty list has no end to scroll towards")
        }
    }

    /**
     * Paging is armed over entries until MAL says the list is over — including while the next page is
     * in flight, which a trigger disarmed for every page it asked for would have to re-arm from the
     * top of the list. The controls stay usable throughout: a later page is not a replacement.
     */
    @Test
    fun paging_is_armed_over_entries_until_the_end_of_the_list() = runTest {
        lateinit var pager: AnimeListPager
        val whileMoreInFlight = mutableListOf<AnimeListState>()
        val (built, _) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                AnimeListResponse.Page(fakeEntries(2, firstId = offset + 1L), hasMore = offset == 0)
            },
            holdAnimeList = { offset -> if (offset > 0) whileMoreInFlight += pager.state.value },
        )
        pager = built

        pager.start()
        assertEquals(AnimeListTail.Idle, pager.shown().tail)
        assertTrue(pager.state.value.pagingArmed)
        assertTrue(pager.state.value.queryControlsEnabled)

        pager.next()
        val loadingMore = whileMoreInFlight.single()
        assertEquals(AnimeListTail.LoadingMore, loadingMore.tail())
        assertTrue(loadingMore.pagingArmed)
        assertTrue(loadingMore.queryControlsEnabled)

        assertEquals(AnimeListTail.End, pager.shown().tail)
        assertFalse(pager.state.value.pagingArmed, "nothing further to ask for")
    }

    /**
     * A replacement in flight keeps the old entries on screen and paging armed, with the controls
     * held off until it lands — the one state that is both "entries" and "a first page loading".
     */
    @Test
    fun a_replacement_in_flight_keeps_its_entries_and_paging_and_holds_the_controls() = runTest {
        lateinit var pager: AnimeListPager
        var resetting = false
        val whileReplacing = mutableListOf<AnimeListState>()
        val (built, _) = pagerOver(
            pageSize = 2,
            animeList = { AnimeListResponse.Page(fakeEntries(2), hasMore = true) },
            holdAnimeList = { if (resetting) whileReplacing += pager.state.value },
        )
        pager = built
        pager.start()

        resetting = true
        pager.reset(sortOrder = AnimeListSortOrder.Score)

        val replacing = whileReplacing.single()
        assertEquals(
            AnimeListContent.Entries(pager.shown().entries, AnimeListTail.Idle, replacing = true),
            replacing.content,
        )
        assertFalse(replacing.queryControlsEnabled)
        assertTrue(replacing.pagingArmed)
        assertTrue(pager.state.value.queryControlsEnabled, "and usable again once it lands")
    }

    /**
     * [AnimeListState.revision] counts replacements the user can see, which is what the screen scrolls
     * back to the top for: a first page landing, and every reset landing. A later page is not one, and
     * nor is a hole paged past on the way.
     */
    @Test
    fun the_revision_counts_first_pages_landing_and_nothing_else() = runTest {
        val (pager, _) = pagerOver(
            pageSize = 2,
            animeList = { offset ->
                when (offset) {
                    0 -> AnimeListResponse.Page(fakeEntries(2), hasMore = true)
                    2 -> AnimeListResponse.Page(emptyList(), hasMore = true)
                    else -> AnimeListResponse.Page(fakeEntries(2, firstId = 5), hasMore = true)
                }
            },
        )
        assertEquals(0, pager.state.value.revision)

        pager.start()
        assertEquals(1, pager.state.value.revision)

        pager.next()
        assertEquals(listOf(1L, 2L, 5L, 6L), pager.ids(), "a hole paged past on the way")
        assertEquals(1, pager.state.value.revision, "appending is not replacing")

        pager.reset()
        assertEquals(2, pager.state.value.revision)
    }
}
