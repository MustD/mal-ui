package io.challenge_workshop.mal_ui.animelist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_EMPTY_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_ERROR_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_MORE_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_SKELETON_TAG
import io.challenge_workshop.mal_ui.auth.ErrorCard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * The Anime List: the user's own List Entries as plain rows.
 *
 * **Items contributed to the caller's lazy list, not a composable of its own.** Two things need that.
 * The list has to be lazy, because it is now unbounded — [LoadMoreWhenNearEnd] reads the trigger off
 * the layout's own last-visible index. And a lazy list cannot be nested inside the signed-in
 * screen's scrolling column, so there is exactly one scroll container and the chrome around the list
 * scrolls with it.
 *
 * Still plain rows: no cover art and no layout toggle — tickets 07 and 08 widen that. Everything
 * this screen can be *other* than a list of entries is here, though: the skeleton, the two empty
 * states, and the two failures, each read off `AnimeListPager`'s own fields.
 */
fun LazyListScope.animeListItems(
    state: AnimeListState,
    onRetry: () -> Unit,
    onShowAll: () -> Unit,
    itemModifier: Modifier = Modifier,
) {
    item {
        Text("Your Anime List", style = MaterialTheme.typography.titleMedium, modifier = itemModifier)
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
        item {
            Column(
                itemModifier.testTag(ANIME_LIST_SKELETON_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(SKELETON_ROWS) { AnimeListRowSkeleton() }
            }
        }
    }

    // (5) First page failed. Nothing is loaded — `AnimeListPager.fail` discards on a first page
    // precisely so this cannot become an error card floating over somebody else's slice — so this
    // *is* the screen, and it reuses `ErrorCard` rather than growing one of its own.
    state.firstPageError?.let { message ->
        item {
            Column(
                itemModifier.testTag(ANIME_LIST_ERROR_TAG),
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
        item {
            Column(itemModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    // in a lazy list — which is reachable, because `list_updated_at` reorders under us between two
    // page requests and can hand the same entry back on both sides of a page boundary. Nothing here
    // needs keys: pages only ever append, so positional identity is already stable.
    items(state.entries.size) { index ->
        Column(itemModifier) {
            AnimeListRow(state.entries[index])
            HorizontalDivider()
        }
    }

    // The bottom of an unbounded list, which is where the user finds out whether there is more.
    val moreError = state.moreError
    if (state.loadingMore || moreError != null) {
        item {
            Column(
                itemModifier.testTag(ANIME_LIST_MORE_TAG),
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
 * Asks for the next page about a screenful before the end of what is laid out.
 *
 * Reads the last-visible index off the lazy layout rather than counting pixels or attaching to a
 * scroll offset, so it behaves identically on all four Targets — a mouse wheel, a fling and a
 * `Page Down` all move the same index. "A screenful" is measured in items rather than fixed at a
 * number, so it adapts to whatever the current Layout fits on screen; [MIN_PREFETCH_DISTANCE] is
 * only a floor for the case where one item fills the window.
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
    listState: LazyListState,
    loadedCount: Int,
    revision: Int,
    enabled: Boolean,
    onLoadMore: () -> Unit,
) {
    if (!enabled) return
    // Read through `rememberUpdatedState`, not captured: the effect is keyed on the list and the
    // revision, so it survives every recomposition — and a captured `loadedCount` would be the count
    // from the composition that started it, which is the one count guaranteed to be stale. Reading a
    // `State` inside `snapshotFlow` also makes the change itself an emission, which is the re-arm.
    val currentCount = rememberUpdatedState(loadedCount)
    val currentLoadMore = rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, revision) {
        // Read off the *laid-out* items rather than off `firstVisibleItemIndex`: the screen sends
        // the list back to the top with `requestScrollToItem`, which moves that index immediately
        // and leaves the layout itself untouched until the next measure. Waiting on the index would
        // therefore wave through the very layout this is here to reject. Returns at once when the
        // list is already at the top, so the first page pays nothing for this.
        if (revision > 0) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index }.first { it == 0 }
        }
        snapshotFlow {
            val loaded = currentCount.value
            val layout = listState.layoutInfo
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
 * How many placeholder rows the first-page skeleton draws.
 *
 * Enough to fill a phone and to make the shape read as a list; not enough to matter on a desktop,
 * where the real page lands before anyone counts. A skeleton is a promise about shape, not about
 * length — the page holds fifty.
 */
private const val SKELETON_ROWS: Int = 8

/**
 * One row of the first-page skeleton: [AnimeListRow]'s own shape, drawn with nothing in it.
 *
 * A skeleton rather than a centred spinner because the two are different shapes, and the spinner's
 * is not the one the data arrives in — so the whole page jumps at the moment the list lands. This
 * has the row's height and its two columns, so landing a page changes the pixels and not the layout.
 *
 * Static, with no shimmer. An indefinitely animating placeholder on all four Targets is a cost paid
 * on every launch for a frame or two of decoration, and Compose on web renders it into the canvas.
 */
@Composable
private fun AnimeListRowSkeleton() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkeletonBar(Modifier.weight(1f))
        SkeletonBar(Modifier.width(48.dp))
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(16.dp)
            .background(
                // The same surface the divider and the secondary text use, so an unresolved
                // skeleton reads as chrome rather than as content that failed to arrive.
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                RoundedCornerShape(4.dp),
            ),
    )
}

/** One List Entry: what it is, and how far through it the user is. */
@Composable
private fun AnimeListRow(entry: AnimeListEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            entry.title,
            style = MaterialTheme.typography.bodyLarge,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        Text(
            entry.progress(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Watched-of-total episodes.
 *
 * MAL reports `0` for a total it does not know — a currently-airing show whose run is unannounced —
 * rather than omitting it, and "3 / 0" reads as a bug. So an unknown total shows as `?`.
 */
internal fun AnimeListEntry.progress(): String =
    "$episodesWatched / ${if (totalEpisodes > 0) totalEpisodes.toString() else "?"}"
