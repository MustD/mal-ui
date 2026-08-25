package io.challenge_workshop.mal_ui.animelist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Sort Order menu's contents, as data rather than as pixels.
 *
 * The same shape as `AnimeListFiltersTest`, and for the same reason: what can go wrong is the
 * *list* and its copy, not the drawing of it. A Sort Order MAL supports and this menu omits is an
 * ordering the user cannot reach, and a label that does not name its direction is a promise the
 * ordering will not keep.
 */
class AnimeListSortOrdersTest {

    @Test
    fun every_sort_order_mal_supports_is_offered() {
        assertEquals(
            AnimeListSortOrder.entries.toList(),
            ANIME_LIST_SORT_ORDERS,
            "A Sort Order with no menu entry is an ordering the user cannot reach.",
        )
    }

    /** The default, and the one the app launches on — "whatever I watched last night, at the top". */
    @Test
    fun last_updated_is_the_first_entry_and_the_default() {
        assertEquals(AnimeListSortOrder.LastUpdated, ANIME_LIST_SORT_ORDERS.first())
        assertEquals(AnimeListSortOrder.LastUpdated, AnimeListState().sortOrder)
    }

    /**
     * MAL's four sorts each have a **fixed direction and no direction parameter**, so the direction
     * can only be told to the user in the label. "Score" alone reads as ascending to about half of
     * everyone, and the list then looks broken rather than differently ordered. See ADR-0003.
     */
    @Test
    fun every_label_names_the_direction_it_actually_sorts_in() {
        assertEquals(
            listOf(
                "Last updated (newest first)",
                "Score (high to low)",
                "Title (A–Z)",
                // Descending, like every MAL sort except `anime_title` — see `sortLabel`.
                "Start date (newest first)",
            ),
            ANIME_LIST_SORT_ORDERS.map { it.sortLabel() },
        )
    }

    @Test
    fun every_sort_order_has_its_own_label() {
        val labels = ANIME_LIST_SORT_ORDERS.map { it.sortLabel() }
        assertEquals(labels.size, labels.toSet().size, "two entries share a label: $labels")
        assertTrue(labels.none { it.isBlank() }, "a blank menu entry is an unpressable one: $labels")
    }
}
