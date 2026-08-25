package io.challenge_workshop.mal_ui.animelist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/**
 * Everything the Anime List screen can be in, as one value.
 *
 * The two failures are **separate fields, not one nullable error**, because the screen does
 * genuinely different things with them: a failed first page is a full-screen error, while a failed
 * later page must leave every loaded entry exactly where it was and offer a retry at the bottom.
 * Collapsing them would leave the UI inferring which is which from "is the list empty", which is
 * wrong for an empty list whose *first* page failed.
 */
data class AnimeListState(
    val entries: List<AnimeListEntry> = emptyList(),
    val watchStatus: WatchStatus? = null,
    val sortOrder: AnimeListSortOrder = AnimeListSortOrder.LastUpdated,
    /** A page is in flight and there is nothing loaded to keep on screen behind it. */
    val loadingFirstPage: Boolean = false,
    val loadingMore: Boolean = false,
    val firstPageError: String? = null,
    val moreError: String? = null,
    /** MAL sent no `paging.next`, so there is nothing further to ask for. */
    val exhausted: Boolean = false,
    /**
     * A first page has landed at least once. Distinguishes "your list is empty" from "we have not
     * asked yet", which are the same [entries] and must not be the same screen.
     */
    val loaded: Boolean = false,
    /**
     * Bumped every time a first page *replaces* [entries] — a filter change, a Sort Order change, a
     * reload, or the retry of a failed first page.
     *
     * It is here rather than being inferred by the screen because a replacement is not visible in
     * any other field: the entries can come back identical, and `loadingFirstPage` has already gone
     * false again by the time anything collects. What the screen does with it is scroll back to the
     * top, and a scroll position into a list that no longer exists is what it is avoiding.
     */
    val revision: Int = 0,
)

/**
 * The Anime List's paging state machine: what has been loaded, what is in flight, and what failed.
 *
 * In `:core` rather than in a ViewModel so `./gradlew :core:allTests` covers it on all four Targets,
 * and so it stays free of Compose and of Koin — the same seam `MalSessionRepository` draws.
 *
 * **`offset` is driven from here.** MAL's `paging.next` is an absolute URL to `api.myanimelist.net`,
 * which the web target must never follow: it has to stay on the Relay's origin. Only its presence is
 * read, as [AnimeListState.exhausted].
 *
 * Every method suspends rather than launching into a scope of its own. A pager that owned a scope
 * would have to be closed, and its caller — a ViewModel that already has `viewModelScope` — would be
 * the one thing that must not forget to.
 */
class AnimeListPager(
    private val client: MalAnimeListClient,
    private val pageSize: Int = MalAnimeListClient.DEFAULT_PAGE_SIZE,
    watchStatus: WatchStatus? = null,
    sortOrder: AnimeListSortOrder = AnimeListSortOrder.LastUpdated,
) {
    private val _state = MutableStateFlow(AnimeListState(watchStatus = watchStatus, sortOrder = sortOrder))
    val state: StateFlow<AnimeListState> = _state.asStateFlow()

    /**
     * The offset the *next* request uses. Advanced only on success, so a retry re-requests the page
     * that failed rather than skipping it — and by [pageSize] rather than by however many entries
     * came back, because MAL pages by position and a short page is not the end of the list.
     */
    private var nextOffset: Int = 0

    /**
     * Bumped by [reset]. A [load] that finishes holding a stale one discards its page.
     *
     * The next page is fetched by proximity, so a prefetch is in flight for much of the time the
     * user spends scrolling — and disabling the filter controls while the *first* page loads does
     * not cover that window. Appending the old filter's page under the new filter reads as the
     * filter not having worked at all, which is why this is the pager's problem rather than the
     * screen's.
     */
    private var generation: Int = 0

    /**
     * The first page, unless one has already landed or is already in flight.
     *
     * Safe to call from a `LaunchedEffect` that recomposition re-runs: the screen asking twice is
     * ordinary, and a second request would be neither. A previous failure stops it too — that path
     * is [retry], driven by the user, not by an effect that would re-fire the same failing request
     * on every recomposition.
     */
    suspend fun loadFirstPage() {
        val current = _state.value
        if (current.loaded || current.loadingFirstPage || current.firstPageError != null) return
        load()
    }

    /**
     * The next page, unless the list is exhausted, a request is already in flight, or the last one
     * failed.
     *
     * Driven by proximity to the end of the list rather than by a button, so it is asked on every
     * frame the user spends near the bottom. All four guards are therefore about the same thing:
     * being asked repeatedly must cost at most one request. The failure guard is the one with teeth
     * — a user parked at the bottom of a failed page would otherwise hammer a MAL that is already
     * failing, forever. [retry] is the way back out of that, and a person asks for it.
     */
    suspend fun next() {
        val current = _state.value
        if (current.exhausted || current.loadingFirstPage || current.loadingMore) return
        if (current.moreError != null || current.firstPageError != null) return
        load()
    }

    /** Re-requests whichever page failed — [nextOffset] did not move when it did. */
    suspend fun retry() {
        val current = _state.value
        if (current.loadingFirstPage || current.loadingMore) return
        load()
    }

    /**
     * Starts again from `offset=0` under a possibly different filter and Sort Order.
     *
     * **The loaded entries stay observable until the new first page lands**, and are replaced rather
     * than cleared first, so changing a filter does not flash the screen empty on every tap.
     */
    suspend fun reset(
        watchStatus: WatchStatus? = _state.value.watchStatus,
        sortOrder: AnimeListSortOrder = _state.value.sortOrder,
    ) {
        nextOffset = 0
        generation++
        _state.update {
            it.copy(
                watchStatus = watchStatus,
                sortOrder = sortOrder,
                exhausted = false,
                moreError = null,
                firstPageError = null,
                // Released rather than waited for: whatever holds the slot is now superseded, and
                // its page will be discarded when it lands. Leaving it claimed would make the reset
                // itself a no-op — a chip tap during a prefetch that quietly did nothing.
                loadingFirstPage = false,
                loadingMore = false,
            )
        }
        load()
    }

    /**
     * One request, and the only place [nextOffset] moves.
     *
     * Which of the two loading/error pairs it drives is decided by the offset it is about to ask
     * for, not by whether the list is empty: an `offset=0` request is a first page even when a
     * previous filter left entries on screen behind it.
     */
    private suspend fun load() {
        val first = nextOffset == 0
        if (!begin(first)) return
        val mine = generation
        val query = _state.value
        try {
            val page = client.page(
                offset = nextOffset,
                limit = pageSize,
                watchStatus = query.watchStatus,
                sortOrder = query.sortOrder,
            )
            // A reset landed while this was in flight, so this page is of a list the user has
            // stopped looking at. Nothing to clear either: the reset released the loading slot and
            // its own load owns it now.
            if (mine != generation) return
            nextOffset += pageSize
            _state.update {
                it.copy(
                    entries = if (first) page.entries else it.entries + page.entries,
                    loaded = true,
                    exhausted = !page.hasMore,
                    loadingFirstPage = false,
                    loadingMore = false,
                    revision = if (first) it.revision + 1 else it.revision,
                )
            }
        } catch (e: CancellationException) {
            // The screen went away. Not a failure to report — and clearing the loading flag is
            // the caller's problem, because there is no caller left. Unless a reset superseded
            // this, in which case the flags now belong to its load and must not be cleared here.
            if (mine == generation) {
                _state.update { it.copy(loadingFirstPage = false, loadingMore = false) }
            }
            throw e
        } catch (e: Exception) {
            // `MalAnimeListClient` has already wrapped a transport failure into a `MalAuthException`
            // with a message worth showing; anything else that got this far is a bug, and a bug that
            // shows as a retryable error beats one that takes the screen down.
            //
            // Superseded the same way a success is: a stale page's failure is not the new list's
            // error, and showing it would put a retry on screen that re-requests the wrong slice.
            if (mine != generation) return
            fail(first, e.message ?: e.toString())
        }
    }

    /**
     * Claims the in-flight slot, or reports that something else already holds it.
     *
     * The check and the set are one `update` so a fast scroll cannot fire the same offset twice —
     * `update` re-runs its block until the compare-and-set wins, and the winning run is the last one
     * to have assigned [claimed].
     */
    private fun begin(first: Boolean): Boolean {
        var claimed = false
        _state.update { current ->
            if (current.loadingFirstPage || current.loadingMore) {
                claimed = false
                current
            } else {
                claimed = true
                if (first) {
                    current.copy(loadingFirstPage = true, firstPageError = null)
                } else {
                    current.copy(loadingMore = true, moreError = null)
                }
            }
        }
        return claimed
    }

    /**
     * A failed page, as one of the two error states.
     *
     * A failed **first** page also discards the entries, which only matters after a [reset]: those
     * entries are the *previous* filter's, and leaving them under the chip the user just tapped
     * shows them somebody else's slice labelled as theirs. Discarding them is also what makes a
     * failed reset reach the same full-screen error as a failed initial load, rather than a third
     * state that is an error card floating over a stale list.
     */
    private fun fail(first: Boolean, message: String) {
        _state.update {
            if (first) {
                it.copy(
                    entries = emptyList(),
                    loaded = false,
                    loadingFirstPage = false,
                    firstPageError = message,
                )
            } else {
                it.copy(loadingMore = false, moreError = message)
            }
        }
    }
}
