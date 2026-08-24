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

    fun retry() {
        viewModelScope.launch { pager.retry() }
    }
}
