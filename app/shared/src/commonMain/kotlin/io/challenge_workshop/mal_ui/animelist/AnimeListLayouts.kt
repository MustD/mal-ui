package io.challenge_workshop.mal_ui.animelist

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_LAYOUT_TAG

/** The two Layouts, in the order they are drawn — the default first. */
val ANIME_LIST_LAYOUTS: List<AnimeListLayout> = AnimeListLayout.entries.toList()

/**
 * What each Layout is called on screen.
 *
 * Words rather than icons, and that is a constraint rather than a preference: this project pulls in
 * no Material icon dependency, and a grid-versus-list glyph drawn by hand for four Targets would be
 * a `Canvas` apiece to say what two words say. Here rather than in `:core` for the reason
 * [AnimeListSortOrder] gives — `:server` depends on that tier and has no business carrying display
 * copy.
 */
fun AnimeListLayout.layoutLabel(): String = when (this) {
    AnimeListLayout.Cards -> "Cards"
    AnimeListLayout.List -> "List"
}

/**
 * The Layout toggle: two mutually exclusive ways of drawing the same List Entries.
 *
 * A segmented button rather than a chip row, because the two controls beside it are already chips
 * and a menu — and because these two are not a filter over anything. Nothing here is disabled while
 * a page is in flight, unlike the filter row and the Sort Order menu: those change *which* entries
 * are on screen and have to wait for MAL, while this redraws whatever is already loaded and makes
 * no request at all. A Layout toggle that greyed out mid-scroll would be claiming a dependency it
 * does not have.
 */
@Composable
fun AnimeListLayoutToggle(
    selected: AnimeListLayout,
    onSelect: (AnimeListLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.testTag(ANIME_LIST_LAYOUT_TAG)) {
        ANIME_LIST_LAYOUTS.forEachIndexed { index, layout ->
            SegmentedButton(
                selected = layout == selected,
                onClick = { onSelect(layout) },
                shape = SegmentedButtonDefaults.itemShape(index, ANIME_LIST_LAYOUTS.size),
                label = { Text(layout.layoutLabel()) },
            )
        }
    }
}
