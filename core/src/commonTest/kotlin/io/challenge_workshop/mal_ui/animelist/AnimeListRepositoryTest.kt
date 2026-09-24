@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui.animelist

import io.challenge_workshop.mal_ui.session.FakeClock
import io.challenge_workshop.mal_ui.session.FakeKeyValueStore
import io.challenge_workshop.mal_ui.session.FakeMal
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.RefreshResponse
import io.challenge_workshop.mal_ui.session.SessionState
import io.challenge_workshop.mal_ui.session.SignedOutReason
import io.challenge_workshop.mal_ui.session.TEST_CONFIG
import io.challenge_workshop.mal_ui.session.TEST_USER
import io.challenge_workshop.mal_ui.session.VALID_TOKENS
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The Anime List lasts exactly one Session, and this is the module that says so.
 *
 * Built over a **real** [MalSessionRepository] and the fake MAL behind it, and driven by real
 * sign-ins, sign-outs, restores and refresh rejections — the lifetime rule is about what the Session
 * does, so a stubbed `StateFlow<SessionState>` would test the rule against a Session that cannot
 * actually happen.
 *
 * What is asserted is what a caller can see: the list's state and the requests the fake MAL was
 * asked. Never which pager exists or which coroutine did what.
 *
 * The repository runs in `backgroundScope`, on the test's own single-threaded scheduler — the stand-in
 * for the one main thread it is confined to in the app — and is cancelled with the test. Pages land
 * whenever the engine answers, so every step waits on the list's state rather than on the clock.
 */
class AnimeListRepositoryTest {

    private class Harness(
        val mal: FakeMal,
        val store: JsonTokenStore,
        val session: MalSessionRepository,
        val list: AnimeListRepository,
    ) {
        suspend fun awaitList(predicate: (AnimeListState) -> Boolean): AnimeListState =
            list.state.first(predicate)

        /** A first page has landed and nothing is in flight. */
        suspend fun awaitSettled(): AnimeListState =
            awaitList { it.loaded && !it.loadingFirstPage && !it.loadingMore }

        /** The list a Session that has ended leaves behind: nothing at all. */
        suspend fun awaitDiscarded(): AnimeListState = awaitList { it == AnimeListState() }

        /** A real sign-in: a Pending Authorization minted, then completed by a redirect carrying its `state`. */
        suspend fun signIn() {
            session.beginAuthorization()
            val pending = assertIs<SessionState.Authorizing>(session.state.value).pending
            session.completeAuthorization("${TEST_CONFIG.redirectUri}?code=a-code&state=${pending.state}")
        }

        fun close() = session.close()
    }

    /**
     * @param signedIn whether the store starts out holding a Session, for [MalSessionRepository.restore]
     * to find.
     */
    private suspend fun TestScope.harness(
        signedIn: Boolean,
        refreshResponse: RefreshResponse = RefreshResponse.Rotated("fresh-access", "fresh-refresh"),
        animeList: (Int) -> AnimeListResponse = { AnimeListResponse.Page(fakeEntries(3), hasMore = false) },
        holdAnimeList: suspend (Int) -> Unit = {},
    ): Harness {
        val mal = FakeMal(
            refreshResponse = refreshResponse,
            acceptedAccessToken = VALID_TOKENS.accessToken,
            animeList = animeList,
            holdAnimeList = holdAnimeList,
        )
        val store = JsonTokenStore(FakeKeyValueStore(), clock = FakeClock())
        if (signedIn) store.writeSession(VALID_TOKENS, TEST_USER)
        val session = MalSessionRepository(
            store = store,
            clock = FakeClock(),
            initialConfig = TEST_CONFIG,
            clientFactory = mal.clientFactory,
        )
        return Harness(mal, store, session, AnimeListRepository(session, backgroundScope))
    }

    private val FakeMal.statuses: List<String?> get() = animeListRequests.map { it.parameters["status"] }
    private val FakeMal.sorts: List<String?> get() = animeListRequests.map { it.parameters["sort"] }

    @Test
    fun a_session_restored_from_storage_starts_loading_its_list_at_once() = runTest {
        val h = harness(signedIn = true)

        h.session.restore()

        assertEquals(3, h.awaitSettled().entries.size)
        assertEquals(1, h.mal.animeListRequests.size, "nothing on screen asked — the Session starting did")
        h.close()
    }

    @Test
    fun signing_in_starts_loading_the_list_at_once() = runTest {
        val h = harness(signedIn = false)
        h.session.restore()
        runCurrent()
        assertEquals(0, h.mal.animeListRequests.size, "no Session, so nothing to ask for")

        h.signIn()

        assertEquals(3, h.awaitSettled().entries.size)
        assertEquals(1, h.mal.animeListRequests.size)
        h.close()
    }

    /**
     * The bug this module exists for: sign out, sign in, and the previous Session's list — under its
     * filter and Sort Order — was still on screen, with no request made for the new one.
     */
    @Test
    fun signing_out_discards_the_list_and_the_next_session_opens_fresh_on_the_defaults() = runTest {
        val h = harness(signedIn = true)
        h.session.restore()
        h.awaitSettled()
        h.list.setWatchStatus(WatchStatus.OnHold)
        h.awaitList { it.watchStatus == WatchStatus.OnHold && it.loaded && !it.loadingFirstPage }
        h.list.setSortOrder(AnimeListSortOrder.Title)
        h.awaitList { it.sortOrder == AnimeListSortOrder.Title && it.loaded && !it.loadingFirstPage }

        h.session.signOut()
        h.awaitDiscarded()

        h.signIn()
        val fresh = h.awaitSettled()

        assertNull(fresh.watchStatus, "the next Session opens unfiltered")
        assertEquals(AnimeListSortOrder.LastUpdated, fresh.sortOrder, "and on the default Sort Order")
        assertEquals(3, fresh.entries.size)
        assertEquals(4, h.mal.animeListRequests.size, "the new Session asked for its own first page")
        assertNull(h.mal.statuses.last())
        assertEquals(AnimeListSortOrder.LastUpdated.wireValue, h.mal.sorts.last())
        h.close()
    }

    /** A refresh MAL rejected ends the Session as surely as a sign-out, and the list goes with it. */
    @Test
    fun a_rejected_refresh_discards_the_list_the_same_way_a_sign_out_does() = runTest {
        val h = harness(
            signedIn = true,
            refreshResponse = RefreshResponse.Rejected(HttpStatusCode.BadRequest, "invalid_grant"),
        )
        h.session.restore()
        h.awaitSettled()
        h.list.setWatchStatus(WatchStatus.Dropped)
        h.awaitList { it.watchStatus == WatchStatus.Dropped && it.loaded && !it.loadingFirstPage }

        // The next request meets a 401, the refresh it drives is rejected, and the Session is over.
        h.session.forceExpireAccessToken()
        h.list.reload()
        h.session.state.first { it is SessionState.SignedOut }
        assertEquals(
            SignedOutReason.RefreshRejected,
            assertIs<SessionState.SignedOut>(h.session.state.value).reason,
        )
        h.awaitDiscarded()

        // Signing in again — here, a Session put back in the store and restored — fetches afresh.
        h.store.writeSession(VALID_TOKENS, TEST_USER)
        h.session.restore()
        val fresh = h.awaitSettled()

        assertNull(fresh.watchStatus)
        assertNull(h.mal.statuses.last())
        h.close()
    }

    /**
     * A page still in flight when the Session ends is abandoned rather than allowed to land: a late
     * response must not put a list on screen for a Session that no longer exists.
     */
    @Test
    fun a_page_in_flight_at_sign_out_is_cancelled_and_never_lands() = runTest {
        val gate = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        val abandoned = CompletableDeferred<Unit>()
        val h = harness(
            signedIn = true,
            holdAnimeList = {
                held.complete(Unit)
                try {
                    gate.await()
                } catch (e: CancellationException) {
                    abandoned.complete(Unit)
                    throw e
                }
            },
        )
        h.session.restore()
        // Genuinely at MAL, not merely claimed: a request cancelled before it got there proves nothing.
        held.await()

        h.session.signOut()
        h.awaitDiscarded()
        abandoned.await()
        gate.complete(Unit)
        runCurrent()

        assertEquals(AnimeListState(), h.list.state.value, "the abandoned page must not have landed")
        h.close()
    }

    /**
     * The Session changing *within* itself — its user fetched, a refresh flag set and cleared — is not
     * a new Session, and rebuilding the list on it would throw away the user's filter mid-scroll.
     */
    @Test
    fun a_signed_in_session_changing_within_itself_does_not_rebuild_the_list() = runTest {
        val h = harness(signedIn = false)
        h.session.restore()

        // A sign-in emits `SignedIn(user = null)` and then `SignedIn(user)` once the user is fetched.
        h.signIn()
        h.awaitSettled()
        assertEquals(1, h.mal.animeListRequests.size, "the user arriving did not ask for a second list")

        h.list.setWatchStatus(WatchStatus.Watching)
        h.awaitList { it.watchStatus == WatchStatus.Watching && it.loaded && !it.loadingFirstPage }

        // A real refresh: `refreshing` goes true and back to false on the same Session.
        h.session.forceExpireAccessToken()
        h.session.fetchUser()
        assertEquals(2, h.mal.tokenEndpointHits, "the sign-in's exchange, then the one refresh the 401 drove")
        runCurrent()

        assertEquals(WatchStatus.Watching, h.list.state.value.watchStatus)
        assertEquals(2, h.mal.animeListRequests.size, "the refresh did not rebuild the list")
        h.close()
    }

    @Test
    fun re_picking_the_filter_or_sort_order_on_screen_is_dropped_and_reload_is_not() = runTest {
        val h = harness(signedIn = true)
        h.session.restore()
        h.awaitSettled()

        h.list.setWatchStatus(null)
        h.list.setSortOrder(AnimeListSortOrder.LastUpdated)
        runCurrent()
        assertEquals(1, h.mal.animeListRequests.size, "re-picking what is on screen asks for nothing")

        h.list.reload()
        h.awaitList { it.revision == 2 && !it.loadingFirstPage }
        assertEquals(2, h.mal.animeListRequests.size, "Reload is a request for exactly that")
        h.close()
    }

    /** Unless the query on screen failed, when picking it again is the obvious retry. */
    @Test
    fun re_picking_a_filter_or_sort_order_whose_first_page_failed_fetches_it_again() = runTest {
        var failing = true
        val h = harness(
            signedIn = true,
            animeList = {
                if (failing) AnimeListResponse.Failure() else AnimeListResponse.Page(fakeEntries(2), hasMore = false)
            },
        )
        h.session.restore()
        h.awaitList { it.firstPageError != null }

        h.list.setWatchStatus(null)
        // The launch has to have run before the wait, or the failure being waited for is the old one.
        runCurrent()
        h.awaitList { it.firstPageError != null && !it.loadingFirstPage }
        assertEquals(2, h.mal.animeListRequests.size, "the failed filter re-picked is a retry")

        failing = false
        h.list.setSortOrder(AnimeListSortOrder.LastUpdated)
        assertEquals(2, h.awaitSettled().entries.size)
        assertEquals(3, h.mal.animeListRequests.size, "and so is the failed Sort Order")
        h.close()
    }

    @Test
    fun list_operations_outside_a_session_do_nothing() = runTest {
        val h = harness(signedIn = false)
        h.session.restore()
        runCurrent()

        h.list.loadMore()
        h.list.retry()
        h.list.reload()
        h.list.setWatchStatus(WatchStatus.Completed)
        h.list.setSortOrder(AnimeListSortOrder.Score)
        runCurrent()

        assertEquals(0, h.mal.animeListRequests.size)
        assertEquals(AnimeListState(), h.list.state.value)
        h.close()
    }
}
