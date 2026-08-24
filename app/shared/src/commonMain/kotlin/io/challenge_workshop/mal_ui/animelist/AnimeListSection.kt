package io.challenge_workshop.mal_ui.animelist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.challenge_workshop.mal_ui.auth.ErrorCard

/**
 * The Anime List: the user's own List Entries as plain rows.
 *
 * The tracer bullet's UI, and only that — no cover art, no filter chips, no Sort Order control, no
 * layout toggle. Tickets 03–09 widen this; what it proves today is that the whole path from MAL's
 * response to pixels is connected.
 *
 * An ordinary [Column] rather than a `LazyColumn`, because it is nested in the signed-in screen's
 * scrolling column and there is exactly one page in it. Ticket 03 is the one that needs a lazy
 * layout, because what it adds — fetching on approach to the end — is read off the lazy layout's own
 * last-visible index.
 */
@Composable
fun AnimeListSection(
    state: AnimeListState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your Anime List", style = MaterialTheme.typography.titleMedium)

        // Only while there is nothing behind it. A reload keeps the loaded entries on screen, and
        // ticket 06 replaces this with skeletons in the shape of the Layout.
        if (state.loadingFirstPage && state.entries.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text("Loading your list…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        state.firstPageError?.let { message ->
            ErrorCard("Could not load your Anime List", message)
            OutlinedButton(onClick = onRetry, enabled = !state.loadingFirstPage) { Text("Retry") }
        }

        if (state.loaded && state.entries.isEmpty() && state.firstPageError == null) {
            Text(
                "There is nothing on your MyAnimeList yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        for (entry in state.entries) {
            AnimeListRow(entry)
            HorizontalDivider()
        }
    }
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
