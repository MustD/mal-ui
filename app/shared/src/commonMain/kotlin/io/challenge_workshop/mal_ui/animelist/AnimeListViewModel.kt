package io.challenge_workshop.mal_ui.animelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Compose's adapter onto [AnimeListPager], and deliberately nothing more.
 *
 * Every decision about paging — which offset is next, what counts as exhausted, which of the two
 * failures a failure is — lives in the pager in `:core`, where `./gradlew :core:allTests` covers it
 * on all four Targets. What is left here is turning a click into a coroutine.
 *
 * The pager is built from [MalSessionRepository.animeListClient], so it rides the **one**
 * authenticated `HttpClient` that owns refresh. It holds no Session state of its own: a rejected
 * refresh moves the repository to `SignedOut`, and `App.kt` swaps this whole screen out.
 */
class AnimeListViewModel(repository: MalSessionRepository) : ViewModel() {

    private val pager = AnimeListPager(repository.animeListClient())

    val state: StateFlow<AnimeListState> = pager.state

    /**
     * Safe to call from a `LaunchedEffect`: the pager ignores it once a page has landed or one is in
     * flight, so a recomposition cannot re-fetch.
     */
    fun loadFirstPage() {
        viewModelScope.launch { pager.loadFirstPage() }
    }

    /**
     * The next page, asked for by proximity to the end of the list rather than by a button.
     *
     * Called from a scroll trigger, so it is asked far more often than a page is wanted — every
     * frame the user spends near the bottom. That is the pager's problem by design: it holds the
     * in-flight, exhausted and failed guards, so this stays a plain `launch`.
     */
    fun loadMore() {
        viewModelScope.launch { pager.next() }
    }

    fun retry() {
        viewModelScope.launch { pager.retry() }
    }

    /**
     * Filters the Anime List to one Watch Status, or to the whole list for null.
     *
     * Filtering is MAL's job: the list is paged, so it is never wholly in memory, so this discards
     * every loaded page and refetches from `offset=0`. The pager keeps the old entries observable
     * until the replacement lands, which is what stops the screen flashing empty on every tap.
     *
     * Re-picking the active filter is dropped rather than refetched — a chip is a filter, not a
     * reload, and the two are separate controls. Unless that filter's own first page failed, in
     * which case the chip is the gesture a person reaches for and there is nothing on screen for it
     * to disturb.
     */
    fun setWatchStatus(watchStatus: WatchStatus?) {
        val current = state.value
        if (current.watchStatus == watchStatus && current.firstPageError == null) return
        viewModelScope.launch { pager.reset(watchStatus = watchStatus) }
    }

    /**
     * Re-orders the Anime List by one of the four orderings MAL supports.
     *
     * Ordering is MAL's job for the same reason filtering is — the list is paged, so it is never
     * wholly in memory — so this goes through the *same* [AnimeListPager.reset] as the filter, with
     * the same discard, the same old-entries-until-the-swap and the same scroll to the top. There is
     * deliberately no second version of that behaviour, and no reverse toggle to need one: see
     * ADR-0003.
     *
     * Re-picking the current ordering is dropped rather than refetched, on the same terms as the
     * filter — unless its own first page failed, in which case picking it again is the gesture a
     * person reaches for and there is nothing on screen for it to disturb.
     *
     * Held in memory only. A Sort Order that outlived the launch would be a preference nobody set,
     * and the default is the one the app should open on. The Layout is the choice that persists.
     */
    fun setSortOrder(sortOrder: AnimeListSortOrder) {
        val current = state.value
        if (current.sortOrder == sortOrder && current.firstPageError == null) return
        viewModelScope.launch { pager.reset(sortOrder = sortOrder) }
    }
}
