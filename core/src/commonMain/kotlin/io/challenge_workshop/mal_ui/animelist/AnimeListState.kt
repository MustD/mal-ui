package io.challenge_workshop.mal_ui.animelist

/**
 * The Anime List as the screen sees it: which of its screens it is, the query it is of, and the two
 * decisions about the controls around it.
 *
 * Everything here is decided in `:core`. The screen draws [content] and reads the two decisions; it
 * makes none of its own, and there is no flag left for it to combine. The paging facts these are
 * decided from are the pager's private bookkeeping.
 */
data class AnimeListState(
    val content: AnimeListContent = AnimeListContent.NotRequested,
    val watchStatus: WatchStatus? = null,
    val sortOrder: AnimeListSortOrder = AnimeListSortOrder.LastUpdated,
    /**
     * Bumped every time a first page *replaces* what is on screen — the first page of a Session, a
     * filter change, a Sort Order change, a Reload, or the retry of a failed first page.
     *
     * It is here rather than being inferred by the screen because a replacement is not visible in
     * [content] alone: the entries can come back identical, and `replacing` has already gone false
     * again by the time anything collects. What the screen does with it is scroll back to the top,
     * and a scroll position into a list that no longer exists is what it is avoiding.
     */
    val revision: Int = 0,
) {
    /**
     * Whether the filter and the Sort Order can be changed.
     *
     * Not while a first page is in flight, with or without the previous query's entries behind it:
     * both controls go through the same reset, and a live control would invite a second pick against
     * a list that has not changed yet — a pile of requests for lists the user has already moved past.
     */
    val queryControlsEnabled: Boolean
        get() = when (val content = content) {
            AnimeListContent.FirstPageLoading -> false
            is AnimeListContent.Entries -> !content.replacing
            else -> true
        }

    /**
     * Whether scrolling towards the end of the list should ask for the next page.
     *
     * Only over entries, and only until MAL has said the list is over — so the trigger costs nothing
     * at the bottom of a finished list and never fires over a skeleton or an error.
     *
     * **Stays armed through a page in flight, a failed page and a replacement.** Asking then is
     * harmless — the pager drops it — while disarming is not: the trigger restarts when re-armed, and
     * after a replacement a restarted trigger waits for the list to be back at the top, so a list
     * disarmed for every page it loads would stop paging the moment the user had changed filter once.
     */
    val pagingArmed: Boolean
        get() = when (val content = content) {
            is AnimeListContent.Entries -> content.tail != AnimeListTail.End
            else -> false
        }
}

/**
 * Which of its screens the Anime List is — one variant per thing the screen can show, and never two
 * at once.
 *
 * Decided here, in `:core`, rather than in the composable that draws it: which screen a combination
 * of paging facts means used to be worked out inside the screen from a bag of booleans whose
 * invariants lived only in comments, and was tested only by rendering it on jvm. As a value it is
 * `./gradlew :core:allTests`'s, on four Targets, and a contradictory combination has no variant to
 * be. The same principle as ADR-0004, one level down: the choice is `:core`'s, the drawing
 * `:app:shared`'s.
 */
sealed interface AnimeListContent {

    /** No page asked for. Only outside a Session in practice: [AnimeListRepository] asks as one starts. */
    data object NotRequested : AnimeListContent

    /**
     * A first page in flight with nothing loaded to keep on screen behind it — including a run of
     * empty pages being paged past, which MAL has said are not the end. Drawn as the skeleton.
     */
    data object FirstPageLoading : AnimeListContent

    /**
     * The first page failed, and nothing is on screen: a failed replacement discards the previous
     * query's entries rather than leaving them under a filter they are not. Drawn as a full-screen
     * error with a retry.
     */
    data class FirstPageFailed(val message: String) : AnimeListContent

    /**
     * MAL has said the list is over, and there was nothing in it. An empty account and an empty
     * filter are both this: the Watch Status beside it is what tells them apart, and the screen says
     * something different for each.
     */
    data object Empty : AnimeListContent

    /**
     * Entries to show, and what is happening at the bottom of them.
     *
     * @param replacing a reset — a filter, Sort Order or Reload — is in flight, and [entries] are the
     * *previous* query's, kept on screen until the replacement lands so the screen does not flash
     * empty on every tap.
     */
    data class Entries(
        val entries: List<AnimeListEntry>,
        val tail: AnimeListTail,
        val replacing: Boolean,
    ) : AnimeListContent
}

/** The bottom of a list of [AnimeListContent.Entries], which is where the user finds out whether there is more. */
sealed interface AnimeListTail {

    /** There is more, and nothing is asking for it yet. */
    data object Idle : AnimeListTail

    data object LoadingMore : AnimeListTail

    /** A later page failed. Everything above it stays exactly where it is, and a retry asks again. */
    data class MoreFailed(val message: String) : AnimeListTail

    /** MAL sent no `paging.next`: there is nothing further to ask for. */
    data object End : AnimeListTail
}
