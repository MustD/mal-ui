package io.challenge_workshop.mal_ui.animelist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_FILTERS_TAG

/**
 * The six mutually exclusive filter choices, in the order they are drawn.
 *
 * `null` is **All**, and it is the absence of MAL's `status` parameter rather than a sixth value of
 * it: MAL takes one Watch Status or none, which is also why these are chips with one active at a
 * time and not checkboxes.
 *
 * [WatchStatus.Unknown] is deliberately absent. It is where a Watch Status MAL adds later lands, it
 * has no wire value, and offering it would be offering a filter that filters nothing.
 * `AnimeListFiltersTest` holds this list to the enum so a new MAL status cannot be silently
 * unreachable.
 */
val ANIME_LIST_FILTERS: List<WatchStatus?> = listOf(
    null,
    WatchStatus.Watching,
    WatchStatus.Completed,
    WatchStatus.OnHold,
    WatchStatus.Dropped,
    WatchStatus.PlanToWatch,
)

/**
 * What each filter is called on screen.
 *
 * Here rather than in `:core` for the reason [AnimeListSortOrder] gives: `:core` is the tier
 * `:server` also depends on, and display copy is not something a Ktor relay should be carrying.
 */
fun WatchStatus?.filterLabel(): String = when (this) {
    null -> "All"
    WatchStatus.Watching -> "Watching"
    WatchStatus.Completed -> "Completed"
    WatchStatus.OnHold -> "On hold"
    WatchStatus.Dropped -> "Dropped"
    WatchStatus.PlanToWatch -> "Plan to watch"
    // Unreachable through [ANIME_LIST_FILTERS], and named rather than defaulted so a `when` that
    // stops being exhaustive fails the build instead of quietly labelling something "All".
    WatchStatus.Unknown -> "Other"
}

/**
 * What a filter that matched nothing says.
 *
 * **Deliberately not the same sentence as the empty-account one, and deliberately not built from
 * [filterLabel].** The fix is different — an empty account is something to go and do on
 * myanimelist.net, an empty slice is a filter to undo — and a message that read "Nothing matches
 * Completed" would be about the control rather than about the list. Naming the filter in the words
 * a person would use is the whole of what makes it actionable.
 *
 * Never reached for `null`: All matching nothing *is* the empty account, and there is no filter to
 * name or to undo. That is why this takes a non-null [WatchStatus] rather than the nullable one the
 * row is built from.
 */
fun WatchStatus.emptyListMessage(): String = when (this) {
    WatchStatus.Watching -> "Nothing you are watching right now."
    WatchStatus.Completed -> "Nothing completed."
    WatchStatus.OnHold -> "Nothing on hold."
    WatchStatus.Dropped -> "Nothing dropped."
    WatchStatus.PlanToWatch -> "Nothing you plan to watch."
    // Unreachable through [ANIME_LIST_FILTERS] — it has no wire value, so it cannot be a query —
    // and named rather than defaulted for the same reason [filterLabel] names it.
    WatchStatus.Unknown -> "Nothing under this filter."
}

/**
 * The filter row: one Watch Status at a time, All to begin with.
 *
 * **A horizontally scrollable [Row] rather than a tab row**, because six tabs do not fit the width
 * of a phone and one scrolling row is the arrangement that works unchanged on all four Targets.
 *
 * [enabled] is false while the replacement first page is in flight. The previously loaded entries
 * stay on screen behind it — see [AnimeListPager.reset] — so without this the row would invite a
 * second tap against a list that has not changed yet.
 */
@Composable
fun AnimeListFilters(
    selected: WatchStatus?,
    enabled: Boolean,
    onSelect: (WatchStatus?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.testTag(ANIME_LIST_FILTERS_TAG).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (filter in ANIME_LIST_FILTERS) {
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                enabled = enabled,
                label = { Text(filter.filterLabel()) },
            )
        }
    }
}
