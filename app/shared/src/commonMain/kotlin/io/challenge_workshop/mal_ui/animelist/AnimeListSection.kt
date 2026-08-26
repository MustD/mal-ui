package io.challenge_workshop.mal_ui.animelist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_EMPTY_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_ERROR_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_MORE_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_SKELETON_TAG
import io.challenge_workshop.mal_ui.auth.ErrorCard
import io.challenge_workshop.mal_ui.auth.PANE_MAX_WIDTH
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * The Anime List: the user's own List Entries, as a grid of cards or as a dense list.
 *
 * **Items contributed to the caller's lazy grid, not a composable of its own.** Two things need
 * that. The list has to be lazy, because it is unbounded — [LoadMoreWhenNearEnd] reads the trigger
 * off the layout's own last-visible index. And a lazy layout cannot be nested inside the signed-in
 * screen's scrolling column, so there is exactly one scroll container and the chrome around the list
 * scrolls with it.
 *
 * **A grid for both Layouts, not a grid and a list.** The dense Layout is the same grid at one
 * column. Two lazy layouts would mean two scroll states, two paging triggers and two sets of the
 * five screen states below, and switching Layout would drop the user's scroll position on the floor
 * — which is what the Layout toggle would then have to be forgiven for, on every tap.
 *
 * Everything this screen can be *other* than a list of entries is here: the skeleton, the two empty
 * states, and the two failures, each read off `AnimeListPager`'s own fields.
 */
fun LazyGridScope.animeListItems(
    state: AnimeListState,
    layout: AnimeListLayout,
    onRetry: () -> Unit,
    onShowAll: () -> Unit,
) {
    item(span = fullLineSpan) {
        Text("Your Anime List", style = MaterialTheme.typography.titleMedium)
    }

    // The five screen states, in the order they can happen. They are mutually exclusive by
    // construction rather than by an `else`: a failed first page has no entries and is not loading,
    // a reset clears `exhausted` before it starts, and `loaded` is false until a page has landed.
    // Which of them a state is comes off the pager's own fields — none of it is inferred from "is
    // the list empty", which is the one question that cannot tell a failed first page from an empty
    // account.

    // (1) First page loading. Only while there is nothing behind it: a reload or a filter change
    // keeps the loaded entries on screen instead, which is what stops the screen flashing on a tap.
    if (state.loadingFirstPage && state.entries.isEmpty()) {
        items(SKELETON_ITEMS) {
            // Ordinary cells, not one spanning item with its own row of placeholders inside. A
            // skeleton is a promise about the shape the content arrives in, and the only thing that
            // can keep that promise on an `Adaptive` grid — whose columns stretch to whatever width
            // is left over — is the grid itself. Laying the placeholders out by hand beside it would
            // put them at a column width nothing else uses, and the page would jump on arrival:
            // precisely the jump the skeleton exists to prevent.
            when (layout) {
                AnimeListLayout.Cards -> AnimeCardSkeleton(Modifier.testTag(ANIME_LIST_SKELETON_TAG))
                AnimeListLayout.List -> AnimeRowSkeleton(Modifier.testTag(ANIME_LIST_SKELETON_TAG))
            }
        }
    }

    // (5) First page failed. Nothing is loaded — `AnimeListPager.fail` discards on a first page
    // precisely so this cannot become an error card floating over somebody else's slice — so this
    // *is* the screen, and it reuses `ErrorCard` rather than growing one of its own.
    state.firstPageError?.let { message ->
        item(span = fullLineSpan) {
            Column(
                Modifier.testTag(ANIME_LIST_ERROR_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ErrorCard("Could not load your Anime List", message)
                OutlinedButton(onClick = onRetry, enabled = !state.loadingFirstPage) { Text("Retry") }
            }
        }
    }

    // (2) and (3): an empty account, and a filter that matched nothing. Two states and not one,
    // because the fix for each is different and collapsing them tells a user with four hundred
    // completed shows that their MyAnimeList is empty.
    //
    // `exhausted` is part of the condition, not decoration: a first page can come back empty and
    // still carry a `paging.next`, in which case MAL has just said the opposite of both of these.
    // `AnimeListPager` pages past that hole rather than leaving it on screen, so while it does, this
    // is still state (1) above.
    if (state.loaded && state.exhausted && state.entries.isEmpty() && state.firstPageError == null) {
        val watchStatus = state.watchStatus
        item(span = fullLineSpan) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    watchStatus?.emptyListMessage() ?: "You have nothing on your MyAnimeList yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ANIME_LIST_EMPTY_TAG),
                )
                if (watchStatus == null) {
                    // The way out of an empty account is not in this app: this release only reads,
                    // so there is nothing here that could add the first entry.
                    val uriHandler = LocalUriHandler.current
                    OutlinedButton(onClick = { uriHandler.openUri(MY_ANIME_LIST_URL) }) {
                        Text("Open myanimelist.net")
                    }
                } else {
                    // The way out of an empty slice is one tap, rather than the user having to work
                    // out that the chip they tapped is what emptied the screen.
                    OutlinedButton(onClick = onShowAll) { Text("Show all") }
                }
            }
        }
    }

    // Deliberately **unkeyed**. The obvious key is the anime id, and a duplicate one is a hard crash
    // in a lazy layout — which is reachable, because `list_updated_at` reorders under us between two
    // page requests and can hand the same entry back on both sides of a page boundary. Nothing here
    // needs keys: pages only ever append, so positional identity is already stable.
    //
    // One cell each, and the Layout is what decides how many cells fit a line — the grid's own
    // `GridCells`, chosen in `SignedInScreen`. That is the whole of the difference between the two
    // Layouts, which is why there is no second copy of anything above or below this.
    items(state.entries.size) { index ->
        val entry = state.entries[index]
        when (layout) {
            AnimeListLayout.Cards -> AnimeListCard(entry)
            // No divider under a dense row any more. The rows are grid cells now and the grid
            // spaces them itself, so a rule drawn at the bottom of each one lands 8dp above the next
            // row rather than between the two — a line that belongs to nothing.
            AnimeListLayout.List -> AnimeListRow(entry)
        }
    }

    // The bottom of an unbounded list, which is where the user finds out whether there is more.
    val moreError = state.moreError
    if (state.loadingMore || moreError != null) {
        item(span = fullLineSpan) {
            Column(
                Modifier.testTag(ANIME_LIST_MORE_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (moreError != null) {
                    // (4) A later page failed. Everything already loaded stays exactly where it
                    // is: a transient failure at the bottom must not cost the user their place, and
                    // the retry re-requests only the offset that failed.
                    ErrorCard("Could not load more", moreError)
                    OutlinedButton(onClick = onRetry) { Text("Try again") }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text("Loading more…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * A grid item that takes the whole line, whatever the current column count is.
 *
 * Every part of this screen that is not a List Entry gets it: an error card, an empty-state message,
 * a "loading more" row or the profile chrome squeezed into one cell of a five-column grid would be a
 * column of text a hundred pixels wide. `internal` rather than private because the signed-in screen
 * contributes chrome of its own to the same grid and must span it the same way.
 */
internal val fullLineSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/**
 * Asks for the next page about a screenful before the end of what is laid out.
 *
 * Reads the last-visible index off the lazy layout rather than counting pixels or attaching to a
 * scroll offset, so it behaves identically on all four Targets — a mouse wheel, a fling and a
 * `Page Down` all move the same index. "A screenful" is measured in items rather than fixed at a
 * number, so it adapts to whatever the current Layout fits on screen — which is also what makes it
 * correct for a grid, where a screenful is a dozen cards rather than five rows;
 * [MIN_PREFETCH_DISTANCE] is only a floor for the case where one item fills the window.
 *
 * **[loadedCount] is both the re-arm key and the staleness guard**, and it is deliberately the
 * pager's count rather than the layout's:
 *
 * - As the *emitted* value it is what makes landing a page re-arm the trigger. A bare boolean would
 *   go true once and never change, and the list would stop at two pages. The laid-out item count
 *   looks like it would do as well and does not: firing inserts the "loading more" row and landing
 *   removes it again, so a page of exactly one entry nets zero change and the trigger latches.
 * - As a *floor* on `totalItemsCount` it rejects a layout from before the entries were measured.
 *   That frame is real — the first page can land before the layout that draws it, and the effect
 *   can be dispatched inside the same frame on Android — and a chrome-only layout is trivially
 *   "near its end", so without the floor the trigger fires over a list nobody has seen a row of.
 *
 * **[revision] disarms it across a replacement.** When a filter change swaps the content out, the
 * layout in hand was measured against the list that is gone — and if the user changed filter from
 * the bottom of a long list, that layout says "near the end" while the replacement is fifty entries
 * that nobody has scrolled a pixel of. Firing there fetches a second page of the new list before its
 * first page has been looked at. So the effect restarts on a new revision and waits for the list to
 * be back at the top, which is where the screen has already asked it to go.
 *
 * [enabled] is the caller's business: it is what stops this asking a pager that is exhausted or has
 * not loaded anything yet.
 *
 * Being asked more often than a page is wanted is normal and expected — a fling crosses the
 * threshold on every frame. `AnimeListPager` holds every guard against that; nothing here counts.
 */
@Composable
fun LoadMoreWhenNearEnd(
    gridState: LazyGridState,
    loadedCount: Int,
    revision: Int,
    enabled: Boolean,
    onLoadMore: () -> Unit,
) {
    if (!enabled) return
    // Read through `rememberUpdatedState`, not captured: the effect is keyed on the grid and the
    // revision, so it survives every recomposition — and a captured `loadedCount` would be the count
    // from the composition that started it, which is the one count guaranteed to be stale. Reading a
    // `State` inside `snapshotFlow` also makes the change itself an emission, which is the re-arm.
    val currentCount = rememberUpdatedState(loadedCount)
    val currentLoadMore = rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState, revision) {
        // Read off the *laid-out* items rather than off `firstVisibleItemIndex`: the screen sends
        // the list back to the top with `requestScrollToItem`, which moves that index immediately
        // and leaves the layout itself untouched until the next measure. Waiting on the index would
        // therefore wave through the very layout this is here to reject. Returns at once when the
        // list is already at the top, so the first page pays nothing for this.
        if (revision > 0) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index }.first { it == 0 }
        }
        snapshotFlow {
            val loaded = currentCount.value
            val layout = gridState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow NOT_NEAR_END
            if (layout.totalItemsCount < loaded) return@snapshotFlow NOT_NEAR_END
            val remaining = layout.totalItemsCount - 1 - last.index
            if (remaining <= maxOf(layout.visibleItemsInfo.size, MIN_PREFETCH_DISTANCE)) {
                loaded
            } else {
                NOT_NEAR_END
            }
        }
            .distinctUntilChanged()
            .filter { it != NOT_NEAR_END }
            .collect { currentLoadMore.value() }
    }
}

/** Not a possible entry count, so it cannot collide with the value the trigger emits. */
private const val NOT_NEAR_END: Int = -1

/** A floor under "a screenful", for a Layout whose items are taller than the window. */
private const val MIN_PREFETCH_DISTANCE: Int = 5

/**
 * Where the user goes to do something about an empty Anime List, since this release only reads.
 *
 * The site root rather than a deep link to the user's own list: that URL contains their MAL
 * username, and the one screen this is reachable from is the one where nothing has been loaded to
 * read it off.
 */
const val MY_ANIME_LIST_URL: String = "https://myanimelist.net"

/**
 * How many placeholders the first-page skeleton draws.
 *
 * Enough to fill a phone in either Layout, and to make the shape read as a list of things; not
 * enough to matter on a desktop, where the real page lands before anyone counts. A skeleton is a
 * promise about shape, not about length — the page holds fifty.
 *
 * Every one of them carries [ANIME_LIST_SKELETON_TAG], so a test asks `onAllNodesWithTag`: the
 * alternative was one tagged wrapper, and a wrapper is the thing that cannot be laid out by the
 * grid. See the note beside the skeleton itself.
 */
private const val SKELETON_ITEMS: Int = 8

/**
 * How many columns each Layout gets, and therefore what the Layout *is*.
 *
 * [AnimeListLayout.Cards] is [GridCells.Adaptive], not a column count per breakpoint: the grid
 * divides whatever width it is given by [ANIME_CARD_MIN_WIDTH] and stretches the cards to fit, so a
 * phone lands on two columns, a desktop window on five or six and a browser dragged between the two
 * changes column count as it goes — with no size class, no `WindowSizeClass` dependency and nothing
 * to keep in step with the four Targets' idea of a screen.
 *
 * [AnimeListLayout.List] is the same grid at one column, which is what makes the dense Layout a
 * Layout rather than a second lazy container. See [animeListItems].
 */
internal fun AnimeListLayout.gridCells(): GridCells = when (this) {
    AnimeListLayout.Cards -> GridCells.Adaptive(ANIME_CARD_MIN_WIDTH)
    AnimeListLayout.List -> GridCells.Fixed(1)
}

/**
 * How wide the Anime List is allowed to get, which is not the same answer for the two Layouts.
 *
 * A dense row is a line of text and stops being readable much past [LIST_MAX_WIDTH] — the same cap
 * the rest of the app's panes use. A grid of cards is not text: capping it there would leave a
 * maximised desktop window three columns of cover art in the middle and two feet of empty surface
 * around them, which is the responsive behaviour ticket 07 asks for the opposite of.
 */
internal fun AnimeListLayout.contentMaxWidth(): Dp = when (this) {
    AnimeListLayout.Cards -> CARDS_MAX_WIDTH
    AnimeListLayout.List -> LIST_MAX_WIDTH
}

/** The same cap the rest of the app's panes use — see `Modifier.paneItem()`. */
private val LIST_MAX_WIDTH = PANE_MAX_WIDTH

/** Seven columns of cover art. Past that a grid stops reading as a grid and starts as wallpaper. */
private val CARDS_MAX_WIDTH = 1160.dp
