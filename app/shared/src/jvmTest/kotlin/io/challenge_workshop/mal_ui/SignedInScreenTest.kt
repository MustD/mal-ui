@file:OptIn(ExperimentalTestApi::class)

package io.challenge_workshop.mal_ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import io.challenge_workshop.mal_ui.animelist.ANIME_LIST_SORT_ORDERS
import io.challenge_workshop.mal_ui.animelist.AnimeListLayout
import io.challenge_workshop.mal_ui.animelist.AnimeListSortOrder
import io.challenge_workshop.mal_ui.animelist.AnimeListState
import io.challenge_workshop.mal_ui.animelist.MY_ANIME_LIST_URL
import io.challenge_workshop.mal_ui.animelist.WatchStatus
import io.challenge_workshop.mal_ui.animelist.sortLabel
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_EMPTY_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_ERROR_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_FILTERS_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_LAYOUT_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_MORE_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_SKELETON_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_SORT_MENU_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_SORT_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_TAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The signed-in screen, which **is** the Anime List: the entries, the two Layouts, the five states a
 * page load can be in, and the three controls over it.
 *
 * *Which* of the five states a load is in is `AnimeListPager`'s and `ScreenStateSourceTest`'s, and
 * what a control *causes* is the pager's — all on four Targets. What only a rendering can answer is
 * that each state reaches a different part of the screen, that a person can read what it says, and
 * that the control they tap is wired to the right action.
 */
class SignedInScreenTest {

    /**
     * The signed-in branch **is** the Anime List, so "it renders something" is not enough: what has
     * to be on screen is the user's own entries.
     *
     * Every fact the spec asks a List Entry to carry is asserted, because they arrive from three
     * different places in MAL's response — the anime, the user's `list_status`, and a field MAL is
     * free to omit — and a card that quietly lost one would still render.
     */
    @Test
    fun the_signed_in_screen_renders_the_anime_list() {
        runComposeUiTest {
            setContent { SessionRoute(signedIn(), RecordedActions().actions) }

            onNodeWithTag(ANIME_LIST_TAG).assertIsDisplayed()
            for (title in FIXTURE_TITLES) {
                onNodeWithText(title).assertIsDisplayed()
            }
            // Watched-of-total and the user's own score share a line on a card, so this one node
            // carries both. `substring`, because what is asserted is that the two facts are there
            // and not how they are punctuated.
            onNodeWithText("3 / 26 · Score 8", substring = true).assertIsDisplayed()
            // The Watch Status, the media type and the Airing Status, which are what make an
            // unfiltered list legible. `onAllNodes`, because both fixture entries carry the same
            // three — a metadata line that were unique per entry would be testing the fixture.
            onAllNodesWithText("Watching · TV · Finished")[0].assertIsDisplayed()
        }
    }

    /**
     * Toggling to the dense Layout re-draws the List Entries that are already loaded.
     *
     * Driven by the control rather than by a parameter, because the toggle is the only thing a user
     * has — a test that reached past it would prove the dense rendering exists without proving anyone
     * can get to it. The state is a literal, so the toggle's action is what puts the new Layout back
     * on screen, which is exactly what `LayoutPreference` does in the app.
     *
     * The same facts as the card, because "the same entries, drawn densely" is the whole claim: a
     * dense row that dropped the Airing Status would be a second, quieter rendering of an entry.
     */
    @Test
    fun toggling_to_the_dense_layout_redraws_the_same_entries() {
        runComposeUiTest {
            var layout by mutableStateOf(AnimeListLayout.Cards)
            val recorded = RecordedActions()
            val actions = recorded.actions.let {
                it.copy(signedIn = it.signedIn.copy(onSelectLayout = { chosen -> layout = chosen }))
            }
            setContent { SessionRoute(signedIn(layout = layout), actions) }

            // Cards on a device that has never chosen: the feature was asked for as a grid of cover
            // art, so a first launch opens on the Layout the user would have picked.
            onNodeWithTag(ANIME_LIST_LAYOUT_TAG).assertIsDisplayed()
            onNodeWithText("Cards").assertIsSelected()
            onNodeWithText("List").performClick()
            waitForIdle()

            onNodeWithText("List").assertIsSelected()
            onNodeWithText("Cards").assertIsNotSelected()

            for (title in FIXTURE_TITLES) {
                onNodeWithText(title).assertIsDisplayed()
            }
            // A dense row splits what a card joins, so these are three nodes rather than two.
            // `onAllNodes` wherever both fixture entries share the value.
            onNodeWithText("3 / 26").assertIsDisplayed()
            onAllNodesWithText("Score 8")[0].assertIsDisplayed()
            onAllNodesWithText("Watching · TV · Finished")[0].assertIsDisplayed()
        }
    }

    /**
     * A Layout change asks for a Layout and for nothing else.
     *
     * The one property that separates this control from the two beside it: the filter row and the
     * Sort Order menu discard every loaded page and refetch from `offset=0`, while the page size is
     * 50 for both Layouts, so the entries already loaded are the entries the other Layout draws. That
     * the pager never hears of a Layout is structural — `AnimeListPager` has no Layout API at all —
     * so what is left to check is that this toggle is not quietly wired to something that does.
     */
    @Test
    fun the_layout_toggle_asks_for_a_layout_and_nothing_else() {
        val actions = RecordedActions()
        runComposeUiTest {
            setContent { SessionRoute(signedIn(), actions.actions) }

            onNodeWithText("List").performClick()
            onNodeWithText("Cards").performClick()

            assertEquals(listOf("selectLayout", "selectLayout"), actions.calls)
            assertEquals(listOf(AnimeListLayout.List, AnimeListLayout.Cards), actions.layouts)
        }
    }

    /**
     * The five Anime List screen states, each drawing its own part of the screen and none of the
     * others'.
     *
     * *Which* state a page load is in is `AnimeListPager`'s and `ScreenStateSourceTest`'s, on four
     * Targets. What only a rendering can answer is that each reaches a different part of the screen —
     * collapsing an empty slice into an empty account, or a failed first page into the retry row at
     * the bottom, is the easiest mistake in this feature and is invisible in the state.
     *
     * The two empty states share [ANIME_LIST_EMPTY_TAG] on purpose, so the only way to tell them
     * apart is the words; that comparison is
     * [a_filter_that_matches_nothing_names_it_and_offers_a_way_back]'s.
     */
    @Test
    fun each_anime_list_state_draws_its_own_part_of_the_screen() {
        val states = listOf(
            ANIME_LIST_SKELETON_TAG to AnimeListState(loadingFirstPage = true),
            ANIME_LIST_EMPTY_TAG to AnimeListState(loaded = true, exhausted = true),
            ANIME_LIST_ERROR_TAG to AnimeListState(firstPageError = "MAL said no"),
            ANIME_LIST_MORE_TAG to loadedList(moreError = "MAL said no", exhausted = false),
        )

        for ((expected, list) in states) {
            runComposeUiTest {
                setContent { SessionRoute(signedIn(list = list), RecordedActions().actions) }

                // `onAllNodesWithTag`, because the skeleton is one tagged placeholder per grid cell
                // rather than one tagged wrapper — which is what lets the grid lay the placeholders
                // out at the very column width the cards replacing them will get.
                assertTrue(
                    onAllNodesWithTag(expected).fetchSemanticsNodes().isNotEmpty(),
                    "$list drew nothing tagged $expected",
                )
                for ((other, _) in states.filterNot { it.first == expected }) {
                    onAllNodesWithTag(other).assertCountEquals(0)
                }
            }
        }
    }

    /**
     * An empty account is not a broken app, and the fix for it is not in this app at all — which is
     * why it comes with a way out to myanimelist.net.
     */
    @Test
    fun an_empty_anime_list_says_so_and_points_at_myanimelist() {
        val opened = mutableListOf<String>()
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalUriHandler provides RecordingUriHandler(opened)) {
                    SessionRoute(
                        signedIn(list = AnimeListState(loaded = true, exhausted = true)),
                        RecordedActions().actions,
                    )
                }
            }

            assertTrue(
                onNodeWithTag(ANIME_LIST_EMPTY_TAG).textContent().contains("nothing on your MyAnimeList"),
                "an empty account must be told it is empty, not left with a blank screen",
            )

            onNodeWithText("Open myanimelist.net").performClick()

            assertEquals(listOf(MY_ANIME_LIST_URL), opened)
        }
    }

    /**
     * The pair this spec is easiest to collapse by accident: an empty *slice* is a filter to undo,
     * and saying "your list is empty" to a user with four hundred completed shows is a false
     * statement about their account.
     *
     * So the message names the filter, and the way out is one tap — and that tap is the same gesture
     * as the All chip, so it goes through the same action rather than becoming a second way of
     * changing filter.
     */
    @Test
    fun a_filter_that_matches_nothing_names_it_and_offers_a_way_back() {
        val actions = RecordedActions()
        runComposeUiTest {
            setContent {
                SessionRoute(
                    signedIn(list = AnimeListState(loaded = true, exhausted = true, watchStatus = WatchStatus.OnHold)),
                    actions.actions,
                )
            }

            assertEquals(
                "Nothing on hold.",
                onNodeWithTag(ANIME_LIST_EMPTY_TAG).textContent(),
                "the message must name the filter rather than report an empty account",
            )

            onNodeWithText("Show all").performClick()

            assertEquals(listOf("selectWatchStatus"), actions.calls)
            assertEquals(listOf<WatchStatus?>(null), actions.watchStatuses)
        }
    }

    /**
     * Both retries, which are the way out of both failures.
     *
     * `AnimeListPager.next()` will not re-request a page that failed, so without these buttons a
     * scroll trigger that has given up is a dead end. What a retry then *does* — which offset it
     * re-requests, and what it keeps — is the pager's, on four Targets.
     */
    @Test
    fun both_failures_offer_a_retry_that_reaches_the_pager() {
        val failures = listOf(
            "Retry" to AnimeListState(firstPageError = "MAL said no"),
            "Try again" to loadedList(moreError = "MAL said no", exhausted = false),
        )
        for ((label, list) in failures) {
            val actions = RecordedActions()
            runComposeUiTest {
                setContent { SessionRoute(signedIn(list = list), actions.actions) }

                // Fully into view first: the later-page row is below the entries, and a click aimed
                // at a node that is only half on screen lands outside the window.
                onNodeWithTag(ANIME_LIST_TAG).performScrollToNode(hasText(label))
                onNodeWithText(label).performClick()

                // `loadMore` filtered out rather than asserted against: the retry row sits at the
                // bottom, so scrolling to it enters the prefetch zone and the trigger fires. That it
                // costs nothing is the pager's guard, on four Targets — `next()` refuses while a
                // page is showing its error.
                assertEquals(listOf("retry"), actions.calls.filterNot { it == "loadMore" }, label)
            }
        }
    }

    /**
     * A failure at the bottom keeps every entry exactly where it was — losing 50 entries and the
     * user's place to a flaky network is the failure this screen state exists to prevent.
     */
    @Test
    fun a_page_that_fails_mid_scroll_keeps_the_list_on_screen() {
        runComposeUiTest {
            setContent {
                SessionRoute(
                    signedIn(list = loadedList(moreError = "MAL said no", exhausted = false)),
                    RecordedActions().actions,
                )
            }

            for (title in FIXTURE_TITLES) {
                onNodeWithText(title).assertIsDisplayed()
            }
            onNodeWithTag(ANIME_LIST_MORE_TAG).assertIsDisplayed()
        }
    }

    /**
     * Paging is driven by proximity to the end of the list rather than by a button, so the trigger is
     * a scroll and nothing else.
     *
     * Which offsets that then produces is `AnimeListPagerTest`'s, on four Targets. What only a
     * rendering can show is that scrolling reaches the trigger at all — and that an exhausted list
     * costs nothing at its bottom, which is exactly where a user parks.
     */
    @Test
    fun scrolling_near_the_end_asks_for_the_next_page_unless_the_list_is_exhausted() {
        val many = (1..60).map { "Anime $it" }
        for ((exhausted, expected) in listOf(false to listOf("loadMore"), true to emptyList())) {
            val actions = RecordedActions()
            runComposeUiTest {
                setContent {
                    SessionRoute(
                        signedIn(list = loadedList(titles = many, exhausted = exhausted)),
                        actions.actions,
                    )
                }

                onNodeWithTag(ANIME_LIST_TAG).performScrollToIndex(many.size)
                waitForIdle()

                assertEquals(expected, actions.calls.distinct(), "exhausted=$exhausted")
            }
        }
    }

    /**
     * The replacement page has landed, so the content under the user's scroll position has been
     * swapped out and that position is into a list that no longer exists.
     *
     * Keyed on the pager's revision rather than on the filter, because the scroll has to happen when
     * the new page *arrives* — the old entries are deliberately still on screen in between. A user
     * who changed filter from the bottom of a long list and stayed there would be at the bottom of
     * the new one, which is where the paging trigger fires and fetches a page nobody scrolled to.
     */
    @Test
    fun a_replacement_page_returns_the_list_to_the_top() {
        val many = (1..60).map { "Anime $it" }
        runComposeUiTest {
            var revision by mutableStateOf(0)
            setContent {
                SessionRoute(
                    signedIn(list = loadedList(titles = many, revision = revision)),
                    RecordedActions().actions,
                )
            }

            onNodeWithTag(ANIME_LIST_TAG).performScrollToIndex(many.size)
            waitForIdle()
            onNodeWithText("Anime 1").assertDoesNotExist()

            revision = 1
            waitForIdle()

            onNodeWithText("Anime 1").assertIsDisplayed()
        }
    }

    /**
     * The window between the tap and the replacement, which is the whole reason the pager keeps the
     * old entries: a grid that empties to a spinner on every tap reads as broken.
     *
     * The filter row and the Sort Order have to be disabled through it as well — the entries on
     * screen are the *old* query's, so a live control would be inviting a second pick against a list
     * that has not changed yet. The Layout toggle deliberately stays live; it asks MAL for nothing.
     */
    @Test
    fun the_previous_entries_stay_on_screen_with_the_controls_disabled_until_the_replacement_lands() {
        runComposeUiTest {
            var loading by mutableStateOf(true)
            setContent {
                SessionRoute(
                    signedIn(list = loadedList().copy(loadingFirstPage = loading)),
                    RecordedActions().actions,
                )
            }

            onNodeWithText(FIXTURE_TITLES.first()).assertIsDisplayed()
            for (filter in listOf("All", "Watching", "Completed")) {
                onNodeWithText(filter).assertIsNotEnabled()
            }
            onNodeWithTag(ANIME_LIST_SORT_TAG).assertIsNotEnabled()
            onNodeWithText("List").assertIsEnabled()

            loading = false
            waitForIdle()

            onNodeWithText("All").assertIsEnabled()
            onNodeWithTag(ANIME_LIST_SORT_TAG).assertIsEnabled()
        }
    }

    /**
     * The filter row: exactly one chip active, the tapped one reaches the pager, and the row stays
     * reachable from wherever the user has scrolled to.
     *
     * That the chip's value reaches MAL's `status` parameter is `AnimeListPagerTest`'s, on four
     * Targets.
     */
    @Test
    fun choosing_a_watch_status_selects_exactly_that_chip_and_asks_for_that_slice() {
        val many = (1..60).map { "Anime $it" }
        val actions = RecordedActions()
        runComposeUiTest {
            setContent { SessionRoute(signedIn(list = loadedList(titles = many)), actions.actions) }

            // On launch it is All — the whole list rather than an arbitrary slice of it.
            onNodeWithText("All").assertIsSelected()
            for (other in listOf("Watching", "Completed", "On hold", "Dropped", "Plan to watch")) {
                onNodeWithText(other).assertIsNotSelected()
            }

            // Sixty entries down, and still on screen: the row is a sibling of the list rather than
            // an item in it, precisely so it does not scroll out of reach.
            onNodeWithTag(ANIME_LIST_TAG).performScrollToIndex(many.size)
            waitForIdle()
            onNodeWithTag(ANIME_LIST_FILTERS_TAG).assertIsDisplayed()

            onNodeWithText("Watching").performClick()

            assertEquals(listOf<WatchStatus?>(WatchStatus.Watching), actions.watchStatuses)
        }
    }

    /**
     * The Sort Order control names the ordering it is in — in words that say the *direction*, since
     * "Score" alone reads as ascending to about half of everyone — and picking another reaches the
     * pager.
     */
    @Test
    fun choosing_a_sort_order_names_it_and_asks_for_that_ordering() {
        val many = (1..60).map { "Anime $it" }
        val actions = RecordedActions()
        runComposeUiTest {
            setContent { SessionRoute(signedIn(list = loadedList(titles = many)), actions.actions) }

            onNodeWithTag(ANIME_LIST_SORT_TAG)
                .assertTextContains("Last updated (newest first)", substring = true)

            // Sixty entries down, and still on screen, for the reason the filter row is.
            onNodeWithTag(ANIME_LIST_TAG).performScrollToIndex(many.size)
            waitForIdle()
            onNodeWithTag(ANIME_LIST_SORT_TAG).assertIsDisplayed().performClick()
            onNodeWithText("Title (A–Z)").performClick()

            assertEquals(listOf(AnimeListSortOrder.Title), actions.sortOrders)
        }
    }

    /**
     * There are exactly four orderings and no way to reverse any of them, which is a design decision
     * (ADR-0003) rather than an omission — the list is paged, so a reverse toggle could only reverse
     * the pages already loaded.
     *
     * Asserted on the open menu because that is the only place a fifth entry could appear, and a
     * "Reverse" or "Descending" item is exactly the well-meant addition this is here to stop.
     */
    @Test
    fun the_sort_menu_offers_mals_four_orderings_and_no_direction_toggle() {
        runComposeUiTest {
            setContent { SessionRoute(signedIn(), RecordedActions().actions) }

            onNodeWithTag(ANIME_LIST_SORT_TAG).performClick()
            waitForIdle()

            for (label in ANIME_LIST_SORT_ORDERS.map { it.sortLabel() }) {
                onNodeWithText(label).assertIsDisplayed()
            }
            assertEquals(
                4,
                onNodeWithTag(ANIME_LIST_SORT_MENU_TAG).onChildren().fetchSemanticsNodes().size,
                "a fifth menu entry is either a Sort Order MAL does not have or a reverse toggle",
            )
        }
    }
}
