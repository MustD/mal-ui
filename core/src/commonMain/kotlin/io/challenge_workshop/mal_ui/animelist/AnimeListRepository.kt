package io.challenge_workshop.mal_ui.animelist

import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Anime List, for exactly as long as there is a Session — and the one place that rule lives.
 *
 * **One list per Session.** On entering `SignedIn` a fresh [AnimeListPager] is built and its first
 * page asked for at once, with nothing on screen having to ask; on leaving `SignedIn`, for any
 * reason — a sign-out, a refresh MAL rejected — every request it still has in flight is cancelled and
 * it is discarded. The next Session opens on the default filter and Sort Order, whoever signs in. A
 * `SignedIn` → `SignedIn` emission — the user fetched, the refresh flag toggling — is the same Session
 * and rebuilds nothing. See **Anime List** in `CONTEXT.md`.
 *
 * That rule used to rest on the list's ViewModel being discarded with the screen, which nothing
 * did: it lived in the same ViewModel store as the Session's. Here it is part of the interface, so no
 * change to how something is scoped elsewhere can break it.
 *
 * Process-scoped, like the [MalSessionRepository] it watches: a list that outlived its screen was
 * never the problem, one that outlived its Session was. The Layout is not this module's — it is a
 * device preference, [LayoutPreference], and carries over from one Session to the next.
 *
 * The operations do not suspend. Each launches into the current Session's own `Job`, which is what
 * ending the Session cancels, and outside a Session there is no `Job` and they do nothing.
 *
 * @param scope where everything runs. `Dispatchers.Main.immediate` in the app, which confines the
 * pager to one thread and makes an operation start inside the click that asked for it — the same
 * arrangement as [LayoutPreference].
 */
class AnimeListRepository(
    private val session: MalSessionRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AnimeListState())

    /** The current Session's Anime List, or the empty default when there is no Session. */
    val state: StateFlow<AnimeListState> = _state.asStateFlow()

    /** The current Session's list, and the `Job` everything it does runs under. Null outside a Session. */
    private var current: SessionList? = null

    private class SessionList(val pager: AnimeListPager, val job: Job)

    init {
        scope.launch {
            // Entering and leaving `SignedIn` are the only two events: every other change to the
            // Session happens within one and is none of this module's business.
            //
            // A `StateFlow` conflates, so a sign-out and a sign-in with no suspension between them
            // would read as one Session. None of the real ways back into `SignedIn` can do that — each
            // is a round trip to MyAnimeList or a read of the store.
            session.state
                .map { it is SessionState.SignedIn }
                .distinctUntilChanged()
                .collect { signedIn -> if (signedIn) begin() else end() }
        }
    }

    private fun begin() {
        // A child of the scope's own job, and a supervisor: one operation failing must not take the
        // Session's list down with it. The pager catches every failure it can report, so this is a
        // floor rather than a path.
        val job = SupervisorJob(scope.coroutineContext[Job])
        val pager = AnimeListPager(session.animeListClient())
        current = SessionList(pager, job)
        scope.launch(job) { pager.state.collect { _state.value = it } }
        scope.launch(job) { pager.start() }
    }

    private fun end() {
        current?.job?.cancel()
        current = null
        _state.value = AnimeListState()
    }

    /** Runs [block] against the current Session's pager, under its `Job`. Nothing outside a Session. */
    private fun inSession(block: suspend (AnimeListPager) -> Unit) {
        val list = current ?: return
        scope.launch(list.job) { block(list.pager) }
    }

    /**
     * The next page, asked for by proximity to the end of the list rather than by a button.
     *
     * Asked far more often than a page is wanted — every frame the user spends near the bottom. That
     * is the pager's problem by design: it holds the in-flight, exhausted and failed guards.
     */
    fun loadMore() = inSession { it.next() }

    /** Re-requests whichever page failed, and keeps everything else. */
    fun retry() = inSession { it.retry() }

    /**
     * Refetches the Anime List from `offset=0`, keeping the filter and the Sort Order on screen — the
     * way to pick up a change made on myanimelist.net.
     *
     * Unguarded, unlike [setWatchStatus] and [setSortOrder], which drop a re-pick of what is already
     * on screen. Re-picking a filter is not a request for anything; this is a request for exactly
     * that, and dropping it would make the menu entry do nothing on the one screen it exists for.
     */
    fun reload() = inSession { it.reset() }

    /**
     * Filters the Anime List to one Watch Status, or to the whole list for null.
     *
     * Filtering is MAL's job: the list is paged, so it is never wholly in memory, so this discards
     * every loaded page and refetches from `offset=0` — see ADR-0003.
     *
     * Re-picking the active filter is dropped rather than refetched — a chip is a filter, not a
     * reload, and the two are separate controls. Unless that filter's own first page failed, in
     * which case the chip is the gesture a person reaches for and there is nothing on screen for it
     * to disturb.
     */
    fun setWatchStatus(watchStatus: WatchStatus?) {
        val shown = _state.value
        if (shown.watchStatus == watchStatus && shown.content !is AnimeListContent.FirstPageFailed) return
        inSession { it.reset(watchStatus = watchStatus) }
    }

    /**
     * Re-orders the Anime List by one of the four orderings MAL supports, through the *same* reset
     * as the filter. Re-picking the current one is dropped on the same terms as the filter.
     *
     * Held for the Session only. A Sort Order that outlived it would be a preference nobody set, and
     * the default is the one a Session should open on. The Layout is the choice that persists.
     */
    fun setSortOrder(sortOrder: AnimeListSortOrder) {
        val shown = _state.value
        if (shown.sortOrder == sortOrder && shown.content !is AnimeListContent.FirstPageFailed) return
        inSession { it.reset(sortOrder = sortOrder) }
    }
}
