package io.challenge_workshop.mal_ui.animelist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The filter row's contents, as data rather than as pixels.
 *
 * There is no composable here on purpose: what can go wrong is the *list*, not the drawing of it. A
 * Watch Status this app adds without adding a chip would be a slice of the user's list with no way
 * to reach it, and that is not something a rendering test would notice.
 */
class AnimeListFiltersTest {

    @Test
    fun every_watch_status_mal_defines_is_reachable_as_a_filter() {
        assertEquals(
            WatchStatus.entries - WatchStatus.Unknown,
            ANIME_LIST_FILTERS.filterNotNull(),
            "A Watch Status with no chip is a slice of the list the user cannot reach.",
        )
    }

    /**
     * All is the *absence* of MAL's `status` parameter, not a sixth value of it — MAL takes one
     * status or none. Modelling it as null is what keeps that true all the way down to the query.
     */
    @Test
    fun all_is_the_first_filter_and_is_the_absence_of_a_watch_status() {
        assertEquals(null, ANIME_LIST_FILTERS.first())
        assertEquals(1, ANIME_LIST_FILTERS.count { it == null })
    }

    @Test
    fun every_filter_has_its_own_label() {
        val labels = ANIME_LIST_FILTERS.map { it.filterLabel() }
        assertEquals(labels.size, labels.toSet().size, "two chips share a label: $labels")
        assertTrue(labels.none { it.isBlank() }, "a blank chip is an unpressable one: $labels")
    }
}
