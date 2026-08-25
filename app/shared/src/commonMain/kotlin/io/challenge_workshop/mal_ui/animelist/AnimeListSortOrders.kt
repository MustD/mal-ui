package io.challenge_workshop.mal_ui.animelist

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_SORT_MENU_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_SORT_TAG

/**
 * The four orderings MAL supports, in the order they are drawn — the default first.
 *
 * There is no fifth entry, and in particular **no reverse or direction toggle**: MAL's `sort` takes
 * one of four values, each with a fixed direction and no direction parameter, and the Anime List is
 * paged, so reversing what happens to be loaded would reverse a slice rather than the list. See
 * ADR-0003.
 *
 * `anime_id` is a fifth value MAL's documentation marks "under development"; it is absent from
 * [AnimeListSortOrder] itself, which is why it cannot be offered here by accident.
 */
val ANIME_LIST_SORT_ORDERS: List<AnimeListSortOrder> = AnimeListSortOrder.entries.toList()

/**
 * What each Sort Order is called on screen — **field and direction together**, because that is what
 * a Sort Order is (see `CONTEXT.md`) and the direction is not something the user can change.
 *
 * A bare "Score" reads as ascending to about half of everyone, and a list that then opens on a 10
 * looks broken rather than differently ordered. Naming the direction is the only place this can be
 * said, since there is no toggle to say it with.
 *
 * Here rather than in `:core` for the reason [AnimeListSortOrder] gives: `:core` is the tier
 * `:server` also depends on, and display copy is not something a Ktor relay should be carrying.
 */
fun AnimeListSortOrder.sortLabel(): String = when (this) {
    AnimeListSortOrder.LastUpdated -> "Last updated (newest first)"
    AnimeListSortOrder.Score -> "Score (high to low)"
    AnimeListSortOrder.Title -> "Title (A–Z)"
    // **Newest first**, and deliberately not the "oldest first" the ticket's example copy said:
    // MAL's v2 reference marks `anime_start_date` Descending, alongside `list_score` and
    // `list_updated_at`. `anime_title` is the only Ascending one it offers. Still [unverified]
    // against a live account — see `docs/mal-api/anime-list-response.md`, which is where a probe
    // that contradicts this changes it.
    AnimeListSortOrder.StartDate -> "Start date (newest first)"
}

/**
 * The Sort Order control: a button naming the current ordering, and a menu of the four.
 *
 * A menu rather than a second chip row, because only one of the two controls above the list should
 * read as a set of alternatives the user picks between at a glance — and because the labels are
 * sentences rather than words, which is what a row of chips cannot carry.
 *
 * [enabled] is false while the replacement first page is in flight, for the same reason the filter
 * row's is: the entries still on screen are the *previous* ordering's, so a live control would be
 * inviting a second pick against a list that has not changed yet.
 */
@Composable
fun AnimeListSortMenu(
    selected: AnimeListSortOrder,
    enabled: Boolean,
    onSelect: (AnimeListSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.testTag(ANIME_LIST_SORT_TAG),
        ) {
            // The current ordering, not a bare "Sort": it is the only place the direction is
            // written down, and the menu is shut for almost all of the time the list is on screen.
            Text("Sort: ${selected.sortLabel()}")
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.testTag(ANIME_LIST_SORT_MENU_TAG),
        ) {
            for (sortOrder in ANIME_LIST_SORT_ORDERS) {
                DropdownMenuItem(
                    text = { Text(sortOrder.sortLabel()) },
                    onClick = {
                        open = false
                        onSelect(sortOrder)
                    },
                )
            }
        }
    }
}
