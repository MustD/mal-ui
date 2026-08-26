package io.challenge_workshop.mal_ui.animelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * The Layout is the one piece of state here that is *not* the pager's, and deliberately so: it
 * changes no data and issues no request, so the pager has never heard of it. It is held beside the
 * pager rather than in the composable because it outlives the composition — see [layout].
 */
class AnimeListViewModel(
    repository: MalSessionRepository,
    private val store: JsonTokenStore,
) : ViewModel() {

    private val pager = AnimeListPager(repository.animeListClient())

    val state: StateFlow<AnimeListState> = pager.state

    private val _layout = MutableStateFlow(AnimeListLayout.Cards)

    /**
     * How the Anime List is drawn, as the user last left it.
     *
     * Starts at [AnimeListLayout.Cards] and is replaced by the stored record as soon as the read
     * lands. That ordering is not a race the user can see: this ViewModel is resolved at the root of
     * `App()`, so the read starts while `SessionState.Restoring` is still the screen — the same
     * store is being read for the Session at that moment — and the signed-in screen it applies to
     * does not exist yet. A default that were *not* the shipped one would be visible; this one is
     * the frame the screen would have drawn anyway.
     *
     * A `StateFlow` and not a `remember`: the Layout has to survive the composable, and the store
     * read is a suspending call with nowhere in a composition to live.
     *
     * **Not durable on the web Targets.** `KeyValueStore`'s browser actual is `sessionStorage`,
     * which is per-tab and goes when the tab does — see `docs/adr/0001-refresh-token-in-web-session-storage.md`.
     * So this survives a reload but not a closed tab there, and survives everything on jvm and
     * android. Deliberately not split: one store, one record, and a preference that lived somewhere
     * the Session does not would be a second persistence rule to keep in step across four Targets.
     */
    val layout: StateFlow<AnimeListLayout> = _layout.asStateFlow()

    /**
     * Whether the user has picked a Layout on this screen, which is what the startup read must not
     * overwrite.
     *
     * The read is a suspending store call and the toggle is a tap, so on a cold start with a slow
     * first read the tap can land first — and an unguarded `_layout.value = store.readLayout()`
     * would then put the *stored* Layout back on screen a moment after the user chose another one,
     * leaving the screen and the store disagreeing until the next launch. Plain `Boolean` rather
     * than anything atomic: both sides of it run on the main dispatcher.
     */
    private var layoutChosen = false

    init {
        viewModelScope.launch {
            val stored = store.readLayout()
            if (!layoutChosen) _layout.value = stored
        }
    }

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

    /**
     * Switches how the loaded List Entries are drawn, and remembers the choice.
     *
     * **The pager is not touched and no request is made.** A Layout is a presentation choice: the
     * page size is 50 for both, so the entries already on screen are the entries the other Layout
     * draws. That is the whole difference between this and [setWatchStatus] or [setSortOrder],
     * which are MAL's business and discard every loaded page.
     *
     * The switch is immediate and the write follows it. Persisting first would put a filesystem or
     * a `sessionStorage` round trip between the tap and the redraw for a preference, and a write
     * that failed would leave the user looking at a Layout they did not pick — the failure is worth
     * less than the frame.
     *
     * **Every tap writes, including one that picks the Layout already on screen.** Skipping that
     * write looks free and is not: before the startup read lands, the Layout on screen is the
     * shipped default rather than the stored one, so a user tapping "Cards" over a stored "List" is
     * making a choice that changes nothing visible and everything stored. Dropping it would leave
     * the screen and the store disagreeing, and the next launch would undo the tap. One small
     * idempotent write per tap is the cheaper half of that trade.
     */
    fun setLayout(layout: AnimeListLayout) {
        // Before anything else, so a tap that changes nothing on screen still counts as a choice and
        // still stops a slow startup read replacing it.
        layoutChosen = true
        _layout.value = layout
        viewModelScope.launch { store.writeLayout(layout) }
    }
}
