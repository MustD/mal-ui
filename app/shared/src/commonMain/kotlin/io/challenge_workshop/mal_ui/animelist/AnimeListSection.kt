package io.challenge_workshop.mal_ui.animelist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_MORE_TAG
import io.challenge_workshop.mal_ui.auth.ErrorCard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * The Anime List: the user's own List Entries as plain rows.
 *
 * **Items contributed to the caller's lazy list, not a composable of its own.** Two things need that.
 * The list has to be lazy, because it is now unbounded — [LoadMoreWhenNearEnd] reads the trigger off
 * the layout's own last-visible index. And a lazy list cannot be nested inside the signed-in
 * screen's scrolling column, so there is exactly one scroll container and the chrome around the list
 * scrolls with it.
 *
 * Still plain rows: no cover art, no filter chips, no Sort Order control, no layout toggle. Tickets
 * 04–09 widen this.
 */
fun LazyListScope.animeListItems(
    state: AnimeListState,
    onRetry: () -> Unit,
    itemModifier: Modifier = Modifier,
) {
    item {
        Text("Your Anime List", style = MaterialTheme.typography.titleMedium, modifier = itemModifier)
    }

    // Only while there is nothing behind it. A reload keeps the loaded entries on screen, and
    // ticket 06 replaces this with skeletons in the shape of the Layout.
    if (state.loadingFirstPage && state.entries.isEmpty()) {
        item {
            Row(
                modifier = itemModifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text("Loading your list…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    state.firstPageError?.let { message ->
        item {
            Column(itemModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ErrorCard("Could not load your Anime List", message)
                OutlinedButton(onClick = onRetry, enabled = !state.loadingFirstPage) { Text("Retry") }
            }
        }
    }

    // `exhausted` is part of the condition, not decoration: a first page can come back empty and
    // still carry a `paging.next`, and "there is nothing on your MyAnimeList" would then be a claim
    // MAL has just contradicted. Ticket 06 splits this from the empty-because-of-the-filter case.
    if (state.loaded && state.exhausted && state.entries.isEmpty() && state.firstPageError == null) {
        item {
            Text(
                "There is nothing on your MyAnimeList yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = itemModifier,
            )
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
                    // Everything already loaded stays exactly where it is: a transient failure at
                    // the bottom must not cost the user their place. Ticket 06 dresses this.
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
    enabled: Boolean,
    onLoadMore: () -> Unit,
) {
    if (!enabled) return
    // Read through `rememberUpdatedState`, not captured: the effect is keyed on the list alone, so
    // it survives every recomposition — and a captured `loadedCount` would be the count from the
    // composition that started it, which is the one count guaranteed to be stale. Reading a `State`
    // inside `snapshotFlow` also makes the change itself an emission, which is the re-arm.
    val currentCount = rememberUpdatedState(loadedCount)
    val currentLoadMore = rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState) {
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
