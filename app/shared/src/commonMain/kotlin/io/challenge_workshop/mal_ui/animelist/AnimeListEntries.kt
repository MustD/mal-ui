package io.challenge_workshop.mal_ui.animelist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** The proportions MyAnimeList's cover art is drawn at, and close enough to what the CDN serves. */
private const val COVER_ASPECT_RATIO: Float = 2f / 3f

/** The narrowest a card may be. Also what the grid divides the window by to pick a column count. */
val ANIME_CARD_MIN_WIDTH = 156.dp

/** The cover on a dense list row: a thumbnail, sized so the row stays a row. */
private val ROW_COVER_WIDTH = 44.dp

/**
 * The geometry a rendering and its skeleton have to agree on.
 *
 * Named rather than repeated, because the whole claim a skeleton makes is that the real thing lands
 * in the same place — and two copies of `padding(vertical = 6.dp)` are two things that drift apart
 * one commit at a time, silently, into a page that jumps when the data arrives.
 */
private val ROW_PADDING = 6.dp
private val ROW_SPACING = 12.dp
private val CARD_TEXT_PADDING = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
private val COVER_CORNER = RoundedCornerShape(6.dp)

/**
 * One List Entry as a card: the cover art, the title, and the four facts about it that fit.
 *
 * The card Layout is what the feature was asked for — cover art is how a person recognises a title
 * without reading it — so this is the default and [AnimeListRow] is the dense alternative.
 */
@Composable
internal fun AnimeListCard(entry: AnimeListEntry, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        AnimeCover(
            entry = entry,
            preferLarge = true,
            modifier = Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT_RATIO),
        )
        Column(
            modifier = Modifier.padding(CARD_TEXT_PADDING),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                overflow = TextOverflow.Ellipsis,
                // Both bounds, not just the maximum: a grid row is as tall as its tallest card, and
                // a one-line title beside a two-line one would otherwise leave the shorter card's
                // metadata floating half a line up from its neighbour's.
                minLines = 2,
                maxLines = 2,
            )
            EntrySubtitle(entry.progress() + PART_SEPARATOR + entry.scoreLabel())
            EntrySubtitle(entry.metadataLabel())
        }
    }
}

/**
 * One List Entry as a dense row — ticket 02's rendering, now with the thumbnail and the metadata the
 * spec asks every List Entry to carry.
 *
 * Kept as the *other* Layout rather than replaced: a card grid is for browsing and a list is for
 * scanning four hundred completed shows, and the Layout toggle is what lets a person pick.
 */
@Composable
internal fun AnimeListRow(entry: AnimeListEntry, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_SPACING),
    ) {
        AnimeCover(
            entry = entry,
            preferLarge = false,
            modifier = Modifier.width(ROW_COVER_WIDTH).aspectRatio(COVER_ASPECT_RATIO),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyLarge,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
            EntrySubtitle(entry.metadataLabel())
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EntrySubtitle(entry.progress())
            EntrySubtitle(entry.scoreLabel())
        }
    }
}

/**
 * The cover art, over a placeholder that is always there.
 *
 * **The placeholder is drawn first and the image over it, which is the whole error handling.** An
 * entry MAL sent no `main_picture` for never builds an [AsyncImage] at all; one whose art fails to
 * load draws nothing over the placeholder, and Coil neither throws nor propagates that failure — so
 * a broken cover costs its own card and takes the row, the grid and the scroll with it nowhere.
 *
 * **Nothing here looks at a status code, and nothing can.** On the web Targets a missing image is a
 * network-level CORS failure with no status at all — the CDN sends `Access-Control-*` headers on a
 * 200 and none on a 404 — while jvm and android see a plain 404. A branch on "was it a 404" would
 * therefore be right on two Targets and silently wrong on the other two; the placeholder-behind is
 * the same on all four.
 *
 * [contentDescription] is null on purpose: the title is drawn beside the art in both Layouts, so the
 * cover is decorative and a screen reader announcing it would read the title twice.
 */
@Composable
private fun AnimeCover(entry: AnimeListEntry, preferLarge: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(COVER_CORNER)
            .background(placeholderColor()),
        contentAlignment = Alignment.Center,
    ) {
        // A mark rather than an empty box, so a cover that never arrives still identifies its entry
        // — and so a grid of them does not read as a grid of failures.
        entry.title.firstOrNull()?.let {
            Text(
                it.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.coverUrl(preferLarge)?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Every secondary line on a card or a row, so they cannot drift apart one at a time. */
@Composable
private fun EntrySubtitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
    )
}

/**
 * The colour behind an absent or unloaded cover, and behind every skeleton.
 *
 * One function rather than a literal at each site: the skeleton is a promise about the shape the
 * real content arrives in, and a placeholder that is a different grey from the skeleton it replaces
 * makes the arrival look like a failure.
 */
@Composable
internal fun placeholderColor(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)

/** [AnimeListCard]'s shape with nothing in it. See `AnimeListSection`'s note on skeletons. */
@Composable
internal fun AnimeCardSkeleton(modifier: Modifier = Modifier) {
    Card(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT_RATIO).background(placeholderColor()))
        Column(
            modifier = Modifier.padding(CARD_TEXT_PADDING),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SkeletonBar(Modifier.fillMaxWidth())
            SkeletonBar(Modifier.fillMaxWidth(0.6f))
        }
    }
}

/** [AnimeListRow]'s shape with nothing in it. */
@Composable
internal fun AnimeRowSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_SPACING),
    ) {
        Box(
            Modifier
                .width(ROW_COVER_WIDTH)
                .aspectRatio(COVER_ASPECT_RATIO)
                .clip(COVER_CORNER)
                .background(placeholderColor()),
        )
        SkeletonBar(Modifier.weight(1f))
        SkeletonBar(Modifier.width(48.dp))
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier = Modifier) {
    Box(modifier.height(12.dp).background(placeholderColor(), RoundedCornerShape(4.dp)))
}

/**
 * Which of MAL's two cover-art sizes to ask the CDN for.
 *
 * A card asks for `large` and a dense row for `medium`, falling back to whichever one is present:
 * MAL omits fields it has no value for, so `main_picture` can be absent altogether, present with
 * only one size, or — on an entry whose art was never uploaded — present with an empty string in it.
 * All three arrive here as null and render the placeholder.
 */
internal fun AnimeListEntry.coverUrl(preferLarge: Boolean): String? {
    val picture = picture ?: return null
    val preferred = if (preferLarge) picture.large else picture.medium
    val fallback = if (preferLarge) picture.medium else picture.large
    return (preferred ?: fallback)?.takeIf { it.isNotBlank() }
}

/**
 * Watched-of-total episodes.
 *
 * MAL reports `0` for a total it does not know — a currently-airing show whose run is unannounced —
 * rather than omitting it, and "3 / 0" reads as a bug. So an unknown total shows as `?`.
 */
internal fun AnimeListEntry.progress(): String =
    "$episodesWatched / ${if (totalEpisodes > 0) totalEpisodes.toString() else "?"}"

/**
 * The user's own score.
 *
 * MAL sends `0` both for "not scored" and for a score of zero, and does not distinguish them — its
 * own scale starts at 1 — so `0` is read as unscored. Spelled out rather than shown as a bare
 * number, because the number beside it is an episode count.
 */
internal fun AnimeListEntry.scoreLabel(): String = if (score in 1..10) "Score $score" else "No score"

/**
 * The three facts about an entry that are not numbers: the Watch Status, the media type and the
 * Airing Status, in that order.
 *
 * One line rather than three, because a card has room for one — and joined from a list that drops
 * its nulls, so an entry MAL sent no `media_type` for closes up rather than showing a gap with two
 * separators around it.
 */
internal fun AnimeListEntry.metadataLabel(): String =
    listOfNotNull(watchStatus.filterLabel(), mediaTypeLabel(), airingStatus.airingLabel())
        .joinToString(PART_SEPARATOR)

private const val PART_SEPARATOR: String = " · "

/**
 * MAL's `media_type`, in the spelling a person uses.
 *
 * A `when` over a **string**, with the raw value tidied up as the fallback, because `media_type` is
 * left a string in `:core` on purpose: MAL adds types, and an entry whose type is one this build has
 * never heard of should read as "Music Video", not vanish and not crash.
 */
internal fun AnimeListEntry.mediaTypeLabel(): String? = when (val type = mediaType?.lowercase()) {
    null, "" -> null
    "tv" -> "TV"
    "ova" -> "OVA"
    "ona" -> "ONA"
    "movie" -> "Movie"
    "special" -> "Special"
    "music" -> "Music"
    "unknown" -> null
    else -> type.split('_').joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
    }
}

/**
 * What the anime itself is doing, in three words at most.
 *
 * Null for [AiringStatus.Unknown] — the entry MAL sent a status this build has never seen for —
 * because "Unknown" on a card is a word that tells the reader nothing and takes the room the media
 * type needed.
 */
internal fun AiringStatus.airingLabel(): String? = when (this) {
    AiringStatus.CurrentlyAiring -> "Airing"
    AiringStatus.FinishedAiring -> "Finished"
    AiringStatus.NotYetAired -> "Not yet aired"
    AiringStatus.Unknown -> null
}
