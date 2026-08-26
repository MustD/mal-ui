package io.challenge_workshop.mal_ui.animelist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure half of a List Entry's rendering: which cover-art URL is asked for, and what the four
 * facts beside it say.
 *
 * The composables themselves are deliberately not a test seam — the spec says so, and a screenshot
 * of a card proves nothing a reader could not see. What *is* worth pinning is here: MAL omits fields
 * it has no value for and adds values this build has never heard of, and every one of those cases is
 * a string this code has to produce anyway.
 *
 * In `commonTest`, so `./gradlew :app:shared:testAndroidHostTest`, `:jvmTest`, `:jsTest` and
 * `:wasmJsTest` each run it — the labels are common code and a divergence would be a per-Target one.
 */
class AnimeListEntriesTest {

    @Test
    fun a_card_asks_for_the_large_cover_and_a_row_for_the_medium_one() {
        val entry = entry(picture = AnimePicture(medium = "https://cdn/m.jpg", large = "https://cdn/l.jpg"))

        assertEquals("https://cdn/l.jpg", entry.coverUrl(preferLarge = true))
        assertEquals("https://cdn/m.jpg", entry.coverUrl(preferLarge = false))
    }

    @Test
    fun either_size_stands_in_for_the_other_when_mal_sends_only_one() {
        val mediumOnly = entry(picture = AnimePicture(medium = "https://cdn/m.jpg"))
        val largeOnly = entry(picture = AnimePicture(large = "https://cdn/l.jpg"))

        assertEquals("https://cdn/m.jpg", mediumOnly.coverUrl(preferLarge = true))
        assertEquals("https://cdn/l.jpg", largeOnly.coverUrl(preferLarge = false))
    }

    /**
     * The placeholder case, decided here rather than in a composable: no URL means no `AsyncImage`
     * is built at all, and what is on screen is the placeholder that was drawn underneath it. MAL
     * omits `main_picture` entirely for an entry whose art it has none of, and has been seen to send
     * an empty string, so both have to arrive as the same nothing.
     */
    @Test
    fun an_entry_with_no_usable_picture_asks_for_no_url_at_all() {
        assertNull(entry(picture = null).coverUrl(preferLarge = true))
        assertNull(entry(picture = AnimePicture()).coverUrl(preferLarge = true))
        assertNull(entry(picture = AnimePicture(medium = "", large = "")).coverUrl(preferLarge = true))
    }

    @Test
    fun progress_is_watched_of_total() {
        assertEquals("3 / 26", entry(episodesWatched = 3, totalEpisodes = 26).progress())
    }

    /** MAL sends `0` for a run whose length is not yet announced, and "3 / 0" reads as a bug. */
    @Test
    fun an_unknown_episode_total_shows_as_a_question_mark() {
        assertEquals("3 / ?", entry(episodesWatched = 3, totalEpisodes = 0).progress())
    }

    /** MAL's scale starts at 1, so `0` is "not scored" and not a score of zero. */
    @Test
    fun a_score_of_zero_is_no_score() {
        assertEquals("Score 8", entry(score = 8).scoreLabel())
        assertEquals("No score", entry(score = 0).scoreLabel())
    }

    @Test
    fun the_metadata_line_names_the_watch_status_the_media_type_and_the_airing_status() {
        val entry = entry(
            watchStatus = WatchStatus.Watching,
            mediaType = "tv",
            airingStatus = AiringStatus.CurrentlyAiring,
        )

        assertEquals("Watching · TV · Airing", entry.metadataLabel())
    }

    /**
     * The two "MAL did not say" cases close up rather than leaving a gap between two separators.
     * Both are reachable: `media_type` is an omitted field, and [AiringStatus.Unknown] is where a
     * status this build has never seen lands.
     */
    @Test
    fun facts_mal_did_not_send_are_dropped_from_the_metadata_line() {
        val entry = entry(
            watchStatus = WatchStatus.Completed,
            mediaType = null,
            airingStatus = AiringStatus.Unknown,
        )

        assertEquals("Completed", entry.metadataLabel())
    }

    @Test
    fun mals_media_types_are_spelled_the_way_a_person_says_them() {
        assertEquals("TV", entry(mediaType = "tv").mediaTypeLabel())
        assertEquals("OVA", entry(mediaType = "ova").mediaTypeLabel())
        assertEquals("Movie", entry(mediaType = "movie").mediaTypeLabel())
        assertEquals(null, entry(mediaType = "unknown").mediaTypeLabel())
    }

    /**
     * `media_type` is left a string in `:core` because MAL adds them, so a type this build has never
     * heard of has to read as something rather than vanish.
     */
    @Test
    fun a_media_type_this_build_has_never_seen_is_tidied_rather_than_dropped() {
        assertEquals("Music Video", entry(mediaType = "music_video").mediaTypeLabel())
    }

    private fun entry(
        title: String = "Cowboy Bebop",
        picture: AnimePicture? = null,
        totalEpisodes: Int = 26,
        mediaType: String? = "tv",
        airingStatus: AiringStatus = AiringStatus.FinishedAiring,
        watchStatus: WatchStatus = WatchStatus.Watching,
        score: Int = 8,
        episodesWatched: Int = 3,
    ): AnimeListEntry = AnimeListEntry(
        animeId = 1,
        title = title,
        picture = picture,
        totalEpisodes = totalEpisodes,
        mediaType = mediaType,
        airingStatus = airingStatus,
        watchStatus = watchStatus,
        score = score,
        episodesWatched = episodesWatched,
        updatedAt = null,
    )
}
