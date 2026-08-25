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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The primary seam for the Anime List: nearly everything that can be wrong is behind the pager, and
 * `:core:allTests` runs this on jvm, js, wasmJs and android.
 *
 * The assertions are on what a caller can observe — the entries the pager exposes, the markers
 * beside them, and the requests it made. In particular the **outgoing request is asserted, not just
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

    @Test
    fun the_first_page_asks_for_the_agreed_query() = runTest {
        val (pager, mal) = pagerOver(page(FakeEntry(1, "Cowboy Bebop")))

        pager.loadFirstPage()

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

            pager.loadFirstPage()

            assertEquals(wire, mal.animeListRequests.single().parameters["status"], "for $status")
        }
    }

    @Test
    fun each_sort_order_sends_its_own_sort_value() = runTest {
        for (sortOrder in AnimeListSortOrder.entries) {
            val (pager, mal) = pagerOver(page(), sortOrder = sortOrder)

            pager.loadFirstPage()

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

        pager.loadFirstPage()

        val entry = pager.state.value.entries.single()
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
        assertTrue(pager.state.value.loaded)
        assertNull(pager.state.value.firstPageError)
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

        pager.loadFirstPage()

        val entry = pager.state.value.entries.single()
        assertEquals(WatchStatus.Unknown, entry.watchStatus)
        assertEquals(AiringStatus.Unknown, entry.airingStatus)
        // Not an enum at all, precisely so an unrecognised one is still displayable.
        assertEquals("a_type_mal_added_later", entry.mediaType)
    }

    @Test
    fun a_response_without_paging_next_exhausts_the_pager_and_stops_it_asking() = runTest {
        val (pager, mal) = pagerOver(page(FakeEntry(1, "Cowboy Bebop"), hasMore = false))

        pager.loadFirstPage()
        assertTrue(pager.state.value.exhausted)

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

        pager.loadFirstPage()
        pager.next()

        assertContentEquals(
            listOf(1L, 2L, 3L, 4L),
            pager.state.value.entries.map { it.animeId },
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
        pager.loadFirstPage()

        val inFlight = backgroundScope.launch { pager.next() }
        pager.state.first { it.loadingMore }

        pager.next()

        secondPageLanded.complete(Unit)
        inFlight.join()
        assertEquals(
            listOf("0", "2"),
            mal.animeListRequests.map { it.parameters["offset"] },
            "the second `next()` landed while offset=2 was in flight and must have been a no-op",
        )
        assertContentEquals(listOf(1L, 2L, 3L, 4L), pager.state.value.entries.map { it.animeId })
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
        pager.loadFirstPage()
        pager.next()
        assertNotNull(pager.state.value.moreError)

        pager.next()
        pager.next()

        assertEquals(
            listOf("0", "2"),
            mal.animeListRequests.map { it.parameters["offset"] },
            "a standing 'more failed' must not be retried by scrolling",
        )
    }

    @Test
    fun a_failed_first_page_surfaces_as_the_first_page_error_with_nothing_loaded() = runTest {
        val (pager, _) = pagerOver({ AnimeListResponse.Failure(HttpStatusCode.ServiceUnavailable) })

        pager.loadFirstPage()

        val state = pager.state.value
        assertTrue(state.entries.isEmpty())
        assertNotNull(state.firstPageError)
        assertNull(state.moreError, "a first-page failure is not a 'more failed' — the screens differ")
        assertTrue(!state.loadingFirstPage)
        assertTrue(!state.loaded, "nothing landed, so this must not read as an empty Anime List")
    }

    @Test
    fun retry_re_requests_the_same_offset_after_a_first_page_failure() = runTest {
        var fail = true
        val (pager, mal) = pagerOver({
            if (fail) AnimeListResponse.Failure() else AnimeListResponse.Page(fakeEntries(1), hasMore = false)
        })

        pager.loadFirstPage()
        fail = false
        pager.retry()

        assertEquals(listOf("0", "0"), mal.animeListRequests.map { it.parameters["offset"] })
        assertEquals(1, pager.state.value.entries.size)
        assertNull(pager.state.value.firstPageError)
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

        pager.loadFirstPage()
        pager.next()

        val failed = pager.state.value
        assertEquals(listOf(1L, 2L), failed.entries.map { it.animeId }, "nothing loaded may be discarded")
        assertNotNull(failed.moreError)
        assertNull(failed.firstPageError, "the loaded page is still good — this is not a dead screen")

        failFrom = Int.MAX_VALUE
        pager.retry()

        assertEquals(listOf("0", "2", "2"), mal.animeListRequests.map { it.parameters["offset"] })
        assertContentEquals(listOf(1L, 2L, 3L, 4L), pager.state.value.entries.map { it.animeId })
        assertNull(pager.state.value.moreError)
    }

    @Test
    fun reset_refetches_from_zero_and_keeps_the_old_entries_observable_until_it_lands() = runTest {
        lateinit var pager: AnimeListPager
        var resetting = false
        val entriesWhileInFlight = mutableListOf<List<Long>>()
        val (built, mal) = pagerOver(
            pageSize = 2,
            animeList = { _ ->
                // Sampled from *inside* the request: this is exactly the window in which changing a
                // filter must not flash the screen empty.
                entriesWhileInFlight += pager.state.value.entries.map { it.animeId }
                if (resetting) {
                    AnimeListResponse.Page(fakeEntries(1, firstId = 100), hasMore = false)
                } else {
                    AnimeListResponse.Page(fakeEntries(2, firstId = 1), hasMore = true)
                }
            },
        )
        pager = built

        pager.loadFirstPage()
        resetting = true
        pager.reset(watchStatus = WatchStatus.Completed)

        assertEquals(
            listOf(1L, 2L),
            entriesWhileInFlight.last(),
            "the previously loaded entries must still be observable while the new first page is in flight",
        )
        assertEquals(listOf("0", "0"), mal.animeListRequests.map { it.parameters["offset"] })
        assertEquals("completed", mal.animeListRequests.last().parameters["status"])
        assertEquals(listOf(100L), pager.state.value.entries.map { it.animeId }, "the old page is replaced, not appended")
        assertEquals(WatchStatus.Completed, pager.state.value.watchStatus)
        assertTrue(pager.state.value.exhausted, "the new page carries no `paging.next`")
    }
}
