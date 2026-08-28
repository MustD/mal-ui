@file:OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.lifecycle.ViewModelStore
import io.challenge_workshop.mal_ui.animelist.ANIME_LIST_SORT_ORDERS
import io.challenge_workshop.mal_ui.animelist.AnimeListLayout
import io.challenge_workshop.mal_ui.animelist.AnimeListSortOrder
import io.challenge_workshop.mal_ui.animelist.AnimeListState
import io.challenge_workshop.mal_ui.animelist.AnimeListViewModel
import io.challenge_workshop.mal_ui.animelist.LayoutPreference
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
import io.challenge_workshop.mal_ui.auth.LoopbackRedirectListener
import io.challenge_workshop.mal_ui.auth.LoopbackRedirectListenerTest
import io.challenge_workshop.mal_ui.auth.MalSessionViewModel
import io.challenge_workshop.mal_ui.auth.SESSION_DIAGNOSTICS_TAG
import io.challenge_workshop.mal_ui.auth.SESSION_MENU_BUTTON_TAG
import io.challenge_workshop.mal_ui.auth.SESSION_MENU_TAG
import io.challenge_workshop.mal_ui.auth.SESSION_TOP_BAR_TAG
import io.challenge_workshop.mal_ui.auth.SESSION_USER_NAME_TAG
import io.challenge_workshop.mal_ui.auth.SIGNED_OUT_REASON_TAG
import io.challenge_workshop.mal_ui.auth.SessionScreenTag
import io.challenge_workshop.mal_ui.auth.StartupRedirect
import io.challenge_workshop.mal_ui.auth.awaitLoopbackPortFree
import io.challenge_workshop.mal_ui.auth.fakeMal
import io.challenge_workshop.mal_ui.mal.DESKTOP_LOOPBACK_PORT
import io.challenge_workshop.mal_ui.mal.DESKTOP_REDIRECT_URI
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.mal.MalUser
import io.challenge_workshop.mal_ui.screen.ScreenState
import io.challenge_workshop.mal_ui.screen.SignInForm
import io.challenge_workshop.mal_ui.session.FakeKeyValueStore
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.SessionDiagnostics
import io.challenge_workshop.mal_ui.session.SessionState
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Every screen this app draws, rendered for real from a [ScreenState] literal.
 *
 * **What is left here is what genuinely needs a rendered tree.** Everything expressible as a value —
 * which screen a Session produces, which of the five Anime List states a page load is in, what the
 * four Signed Out Reasons say, what goes into the authorization URL — moved to
 * `ScreenStateSourceTest` in `:core`, where it runs on all four Targets instead of only on jvm. What
 * a control *causes* belongs to the ViewModel and pager tests, which are also in `:core`. So the
 * assertions below are about pixels, semantics and clicks: that a state draws *something*, that the
 * something is what a person can read, and that the control they tap is wired to the right action.
 *
 * Exhaustiveness over `ScreenState` is already a compiler guarantee, but "compiles" is not "renders
 * something" — a branch could route to a composable that draws nothing, and the tagged screen roots
 * all `fillMaxSize()`, so their mere presence proves nothing. That gap is the whole reason this file
 * still exists.
 *
 * JVM-only, deliberately. [SessionRoute] is common code with no `expect`/`actual` in it, so running
 * it on a second Target would re-test Compose rather than this app: the web and Android Targets would
 * each need their own harness — karma and Robolectric — to prove nothing this module owns. The
 * *mapping* is what reaches four Targets, and it does.
 */
class SessionRouteTest {

    /**
     * One case per [ScreenState] variant. [every_screen_state_is_covered_by_this_test] holds this
     * list to the sealed interface, so adding a variant without adding a case here fails.
     */
    private val cases: List<Pair<ScreenState, SessionScreenTag>> = listOf(
        ScreenState.Restoring to SessionScreenTag.Restoring,
        signedOut() to SessionScreenTag.SignIn,
        authorizing() to SessionScreenTag.Authorizing,
        signedIn() to SessionScreenTag.SignedIn,
    )

    @Test
    fun every_screen_state_renders_one_screen_with_something_on_it() {
        for ((state, expected) in cases) {
            runComposeUiTest {
                setContent { SessionRoute(state, RecordedActions().actions) }

                onNodeWithTag(expected.tag).assertIsDisplayed()
                for (other in SessionScreenTag.entries - expected) {
                    onNodeWithTag(other.tag).assertDoesNotExist()
                }

                // The blank-screen check. A screen root that `fillMaxSize()`s is "displayed" whether
                // or not it drew anything, so the assertion that matters is on its contents: layout
                // nodes with no semantics do not appear here, so an empty branch has zero children.
                val drawn = onNodeWithTag(expected.tag).onChildren().fetchSemanticsNodes()
                assertTrue(
                    drawn.isNotEmpty(),
                    "$state routed to ${expected.name}, which rendered nothing — a blank screen.",
                )
            }
        }
    }

    /**
     * A [ScreenState] variant that nobody draws fails here.
     *
     * Against `sealedSubclasses` and against [SessionScreenTag] both, because the two failures are
     * different: a variant with no case is one nothing proves anything about, and a variant with no
     * tag is one no test could find on screen.
     */
    @Test
    fun every_screen_state_is_covered_by_this_test() {
        assertEquals(
            ScreenState::class.sealedSubclasses.map { it.simpleName }.toSet(),
            cases.map { (state, _) -> state::class.simpleName }.toSet(),
            "A ScreenState variant has no case in this test, so nothing proves it renders anything.",
        )
        assertEquals(
            cases.size,
            SessionScreenTag.entries.size,
            "Every ScreenState variant gets one tagged screen, and no tag is left over.",
        )
    }

    // --- The sign-in screen ---------------------------------------------------------------------

    /**
     * The seam the channel abstraction rests on, exercised for real: `rememberAuthRedirectChannel()`
     * resolves to this Target's actual — a [LoopbackRedirectListener] that binds 18040 — the click
     * reaches the ViewModel through it, and the browser is opened by the *channel* rather than by the
     * screen. Paste-the-code stays on offer throughout regardless.
     *
     * **The one case in this file that still builds the whole stack**, and the reason it does is that
     * the stack *is* what is being asserted. Everything else here renders a literal, so this one owns
     * its `Dispatchers.setMain`, its repository and its port check rather than making every other
     * case pay for them in a `@BeforeTest`.
     *
     * Rendered through [AppScreen] rather than [SessionRoute], because the channel and the actions
     * record are exactly what that layer wires: a [SessionRoute] given a literal would prove the
     * screen draws and nothing about the capture.
     *
     * The waits are real. Arming binds a socket off the main dispatcher and the browser launch runs
     * off it too, so nothing here completes inside `performClick`.
     *
     * What the listener then does with a redirect is [LoopbackRedirectListenerTest]'s; this test only
     * proves the wiring reaches it.
     */
    @Test
    fun signing_in_arms_this_targets_capture_and_still_offers_paste_the_code() {
        // viewModelScope runs on Dispatchers.Main, which the JVM test platform does not provide.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = JsonTokenStore(FakeKeyValueStore())
        val repository = MalSessionRepository(
            store,
            initialConfig = MalAuthConfig(clientId = "a-client-id"),
            // Not the default factory: the signed-in screen loads the Anime List as soon as it is
            // composed, and a unit test must not make that a real request to myanimelist.net.
            clientFactory = fakeMal(),
        )
        val viewModel = MalSessionViewModel(repository, StartupRedirect.None)
        val animeList = AnimeListViewModel(
            repository,
            LayoutPreference(store, CoroutineScope(Dispatchers.Unconfined)),
        )
        val opened = Collections.synchronizedList(mutableListOf<String>())
        try {
            runComposeUiTest {
                setContent {
                    // Otherwise the desktop `UriHandler` really does launch a browser from a unit
                    // test.
                    CompositionLocalProvider(LocalUriHandler provides RecordingUriHandler(opened)) {
                        AppScreen(viewModel, animeList)
                    }
                }

                onNodeWithText("Sign in with MyAnimeList").performClick()
                waitUntil("the sign-in reaches Authorizing", WAIT_MS) {
                    repository.state.value is SessionState.Authorizing
                }

                val state = repository.state.value as SessionState.Authorizing
                assertEquals(DESKTOP_REDIRECT_URI, state.pending.redirectUri)
                val authorizationUrl = repository.authorizationUrlFor(state.pending)
                waitUntil("the channel opens the browser", WAIT_MS) {
                    opened.toList() == listOf(authorizationUrl)
                }

                // Paste-the-code stays reachable throughout: the URL is on screen to copy by hand,
                // since no platform's browser-opening call reliably reports whether it worked.
                onNodeWithTag(SessionScreenTag.Authorizing.tag).assertIsDisplayed()
                onNodeWithText(authorizationUrl).assertIsDisplayed()
                onNodeWithText("Redirect URL or authorization code").assertIsDisplayed()

                // Not tidying up: cancelling is the *only* thing that gives 18040 back, and a test
                // that walked away from an armed listener would make the next sign-in — here or in
                // the app — fail to arm. The port check below holds this to it.
                onNodeWithText("Cancel").performClick()
                waitUntil("cancelling leaves Authorizing", WAIT_MS) {
                    repository.state.value !is SessionState.Authorizing
                }
            }
        } finally {
            clear(animeList)
            repository.close()
            Dispatchers.resetMain()
            assertTrue(
                awaitLoopbackPortFree(),
                "Port $DESKTOP_LOOPBACK_PORT was left bound by this test.",
            )
        }
    }

    /**
     * The Signed Out Reason is on screen, in a node of its own, so a person reads why rather than a
     * bare "signed out".
     *
     * That the four reasons *differ from each other* is `ScreenStateSourceTest`'s, on four Targets —
     * it is a comparison between four strings, and it needed a rendered tree only for as long as the
     * copy was built inside a composable. What is left here is that the screen draws whichever one it
     * is handed, which no value can say.
     */
    @Test
    fun the_sign_in_screen_shows_the_signed_out_reason_it_is_given() {
        val state = signedOut()
        runComposeUiTest {
            setContent { SessionRoute(state, RecordedActions().actions) }

            assertEquals(state.explanation, onNodeWithTag(SIGNED_OUT_REASON_TAG).textContent())
        }
    }

    /** The Client ID field is the source of truth for the Client ID, and it reaches the ViewModel. */
    @Test
    fun the_client_id_field_reports_what_is_typed_into_it() {
        val actions = RecordedActions()
        runComposeUiTest {
            setContent { SessionRoute(signedOut(), actions.actions) }

            onNodeWithText("a-client-id").performTextReplacement("another-client-id")

            assertEquals(listOf("another-client-id"), actions.clientIds)
        }
    }

    // --- The authorizing screen ------------------------------------------------------------------

    /**
     * The authorization URL is the whole of Paste-the-code's first half, and it is long enough that
     * selecting it out of a text field by hand is where people give up. No platform's browser-opening
     * call reports failure, so this button is the only guaranteed way to it.
     */
    @Test
    fun the_authorization_url_can_be_copied_without_selecting_it() {
        val clipboard = RecordingClipboard()
        runComposeUiTest {
            @Suppress("DEPRECATION")
            setContent {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    SessionRoute(authorizing(), RecordedActions().actions)
                }
            }

            onNodeWithText("Copy").performClick()

            assertEquals(TEST_AUTHORIZATION_URL, clipboard.getText()?.text)
        }
    }

    /**
     * Paste-the-code's second half: the field and the two buttons beside it.
     *
     * Every outcome lands on the same call the platform Redirect Captures funnel into, so what a
     * paste then does is `MalSessionViewModelRedirectTest`'s — one parser and one set of error
     * messages, whichever way the redirect arrived. What is left here is that the field reports what
     * was typed and the two buttons are not wired to each other's action.
     */
    @Test
    fun paste_the_code_reaches_the_view_model() {
        val actions = RecordedActions()
        val pasted = "$DESKTOP_REDIRECT_URI?code=the-code&state=a-state"
        runComposeUiTest {
            setContent {
                SessionRoute(
                    authorizing(form = SignInForm(clientId = "a-client-id", pastedRedirect = "half a")),
                    actions.actions,
                )
            }

            onNodeWithText("half a").performTextReplacement(pasted)
            onNodeWithText("Complete sign-in").performClick()
            onNodeWithText("Cancel").performClick()

            // First, not only: the field is a controlled input over a literal here, so the value it
            // is handed never changes and Compose re-reports the old one behind the new.
            assertEquals(pasted, actions.pastes.first())
            assertEquals(
                listOf("completeSignIn", "cancelSignIn"),
                actions.clicks().filterNot { it == "pastedRedirectChange" },
            )
        }
    }

    // --- The signed-in screen: the Anime List ----------------------------------------------------

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
     * The first page is asked for by the screen once it exists, and not by the ViewModel's `init`.
     *
     * The pager must only ask MAL for a list once there is a signed-in screen to show one on, and
     * this effect is the only thing that says so.
     */
    @Test
    fun the_signed_in_screen_asks_for_the_first_page_when_it_appears() {
        val actions = RecordedActions()
        runComposeUiTest {
            setContent { SessionRoute(signedIn(list = AnimeListState()), actions.actions) }

            assertEquals(listOf("loadFirstPage"), actions.calls)
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

            assertEquals(listOf("selectLayout", "selectLayout"), actions.clicks())
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

            assertEquals(listOf("selectWatchStatus"), actions.clicks())
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
                assertEquals(listOf("retry"), actions.clicks().filterNot { it == "loadMore" }, label)
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

                assertEquals(expected, actions.clicks().distinct(), "exhausted=$exhausted")
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

    // --- The signed-in screen: the top app bar ---------------------------------------------------

    /**
     * The Anime List taking over the signed-in screen must not cost the things that were on it. The
     * top app bar's overflow menu is where they went.
     *
     * Asserted on the open menu rather than on the screen, because that is now the only place any of
     * them can be — a "Sign out" that were still lying loose on the screen would pass an assertion
     * over the whole tree and mean the rehousing never happened. Two of the three are also clicked,
     * since entries wired to the same action would pass every assertion about what is *in* the menu;
     * the third is [session_diagnostics_opens_the_debug_panel_in_a_dialog]'s.
     */
    @Test
    fun the_overflow_menu_carries_reload_sign_out_and_session_diagnostics() {
        val entries = listOf("Reload", "Sign out", "Session diagnostics")
        val actions = RecordedActions()
        runComposeUiTest {
            setContent { SessionRoute(signedIn(), actions.actions) }

            // None of the three is on the screen itself: they live behind one button.
            for (entry in entries) {
                onNodeWithText(entry).assertDoesNotExist()
            }

            onNodeWithTag(SESSION_TOP_BAR_TAG).assertIsDisplayed()
            onNodeWithTag(SESSION_MENU_BUTTON_TAG).performClick()
            waitForIdle()

            for (entry in entries) {
                onNodeWithText(entry).assertIsDisplayed()
            }
            assertEquals(
                entries.size,
                onNodeWithTag(SESSION_MENU_TAG).onChildren().fetchSemanticsNodes().size,
                "a fourth entry is something the Anime List did not displace, so it belongs on the " +
                    "screen where the user can see it rather than behind a menu",
            )

            // Each entry closes the menu before it acts, so each has to be reopened.
            onNodeWithText("Reload").performClick()
            onNodeWithTag(SESSION_MENU_BUTTON_TAG).performClick()
            onNodeWithText("Sign out").performClick()

            assertEquals(listOf("reload", "signOut"), actions.clicks())
        }
    }

    /**
     * The top app bar's whole job besides the menu: say who is signed in.
     *
     * Measured rather than read, because truncation is not in the semantics tree — an ellipsised name
     * and a wrapped one both read back as the same string, and a bar that wrapped a long name would
     * push the list down by however many lines the name happened to need. So the assertion is that a
     * name nobody could fit takes exactly the height a short one does.
     */
    @Test
    fun the_top_app_bar_names_the_user_and_truncates_rather_than_wrapping() {
        fun heightOf(name: String): Dp {
            var height = 0.dp
            runComposeUiTest {
                setContent {
                    SessionRoute(signedIn(user = MalUser(1, name)), RecordedActions().actions)
                }
                waitForIdle()
                onNodeWithTag(SESSION_USER_NAME_TAG).assertTextContains(name.take(1), substring = true)
                height = onNodeWithTag(SESSION_USER_NAME_TAG).getBoundsInRoot().height
            }
            return height
        }

        val long = "someone-with-a-name-far-too-long-to-fit-in-a-top-app-bar-".repeat(4)

        assertEquals(
            heightOf("someone"),
            heightOf(long),
            "a long name wrapped instead of truncating, so the bar grows with whatever MAL returns",
        )
    }

    /**
     * A Session refresh is not a navigation. `ScreenState.SignedIn` carries `refreshing` precisely so
     * the screen can say so without being swapped out, and a list that unmounted for it would lose
     * every page the user has scrolled through.
     */
    @Test
    fun a_session_refresh_does_not_blank_the_signed_in_screen() {
        runComposeUiTest {
            var refreshing by mutableStateOf(false)
            setContent { SessionRoute(signedIn(refreshing = refreshing), RecordedActions().actions) }
            onNodeWithText(FIXTURE_TITLES.first()).assertIsDisplayed()

            refreshing = true
            waitForIdle()

            onNodeWithTag(ANIME_LIST_TAG).assertIsDisplayed()
            onNodeWithText(FIXTURE_TITLES.first()).assertIsDisplayed()
            onNodeWithTag(SESSION_USER_NAME_TAG).assertIsDisplayed()
        }
    }

    // --- The signed-in screen: the diagnostics dialog ---------------------------------------------

    /**
     * `SessionDebugPanel` is a real tool rather than scaffolding — it is the only way a human ever
     * sees the refresh path execute — so rehousing it must not cost any of its controls. The dialog
     * is where it went; the menu entry is its disclosure now, in place of the panel's own.
     */
    @Test
    fun session_diagnostics_opens_the_debug_panel_in_a_dialog() {
        runComposeUiTest {
            setContent { SessionRoute(signedIn(), RecordedActions().actions) }

            // "Force 401" writes an invalid token into the store, so it must not be a stray tap away.
            onNodeWithText("Force 401").assertDoesNotExist()

            openDiagnostics()

            onNodeWithTag(SESSION_DIAGNOSTICS_TAG).assertIsDisplayed()
            for (control in listOf("Force 401", "Reload diagnostics", "Reload profile", "Close")) {
                onNodeWithText(control).assertIsDisplayed()
            }
            // The other half of the profile row that was on this screen. The name is in the bar;
            // the MAL id is the half nobody reads until something is wrong, which is a diagnostic.
            onNodeWithText("MAL id 1", substring = true).assertIsDisplayed()
            // The screen is still underneath it: a dialog is not a third destination.
            onNodeWithTag(ANIME_LIST_TAG).assertIsDisplayed()

            onNodeWithText("Close").performClick()
            waitForIdle()

            onNodeWithText("Force 401").assertDoesNotExist()
        }
    }

    /**
     * The panel's three buttons, each reaching its own action, and the warning that only appears once
     * the access token has been deliberately invalidated.
     *
     * What each button then does is `:core`'s: whether a forced 401 drives exactly one real refresh
     * is `MalSessionRefreshTest.forcing_the_access_token_to_expire_drives_exactly_one_real_refresh`,
     * and that the refresh token survives it is the same file's. What a rendering can say is that the
     * button a person presses is the one wired to it — three buttons on one action would be invisible
     * everywhere else.
     */
    @Test
    fun each_debug_panel_button_reaches_its_own_action() {
        val actions = RecordedActions()
        runComposeUiTest {
            setContent { SessionRoute(signedIn(diagnostics = TEST_DIAGNOSTICS), actions.actions) }
            openDiagnostics()

            onNodeWithText("Reload diagnostics").performClick()
            onNodeWithText("Reload profile").performClick()
            onNodeWithText("Force 401").performClick()

            assertEquals(
                listOf("reloadDiagnostics", "refreshUser", "forceExpireAccessToken"),
                actions.clicks(),
            )
            onNodeWithText("deliberately invalidated", substring = true).assertIsDisplayed()
        }
    }

    // --- Helpers ---------------------------------------------------------------------------------

    /**
     * Ends a hand-built ViewModel's `viewModelScope`, which is otherwise never ended.
     *
     * A ViewModel built by a test rather than by a `ViewModelStore` has nothing that will ever clear
     * it, so its scope outlives the test with a page request still in it — to land after the
     * repository underneath it has been closed and the Main dispatcher reset. Through a
     * `ViewModelStore` because `ViewModel.clear()` is `internal` and this is the public door to it.
     */
    private fun clear(viewModel: AnimeListViewModel) {
        ViewModelStore().apply { put("animeList", viewModel) }.clear()
    }

    /**
     * Opens the diagnostics dialog the way a person does: the overflow menu, then its last entry.
     *
     * The menu closes on the way, which is what keeps "Session diagnostics" unambiguous — the menu
     * entry and the dialog's own title share those words and are never on screen at once.
     */
    private fun ComposeUiTest.openDiagnostics() {
        onNodeWithTag(SESSION_MENU_BUTTON_TAG).performClick()
        onNodeWithText("Session diagnostics").performClick()
        waitForIdle()
    }

    /** Stands in for the desktop clipboard, which a unit test must not actually write to. */
    @Suppress("DEPRECATION")
    private class RecordingClipboard : ClipboardManager {
        private var copied: AnnotatedString? = null

        override fun setText(annotatedString: AnnotatedString) {
            copied = annotatedString
        }

        override fun getText(): AnnotatedString? = copied
    }

    /** Stands in for the platform's browser, which a unit test must not actually start. */
    private class RecordingUriHandler(private val opened: MutableList<String>) : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }
}

/**
 * Generous, because the one test that waits is waiting for a real socket and a real dispatcher rather
 * than for a frame. A wait that is too short reads as a flaky test; one that is too long only costs
 * time when something is already broken.
 */
private const val WAIT_MS: Long = 5_000

private val TEST_DIAGNOSTICS = SessionDiagnostics(
    obtainedAtEpochMs = 1_754_000_000_000,
    ageMillis = 60_000,
    accessTokenLength = 512,
    hasRefreshToken = true,
    accessTokenIsDeliberatelyInvalid = true,
)

/** The concatenated text a node draws, for assertions about copy rather than about structure. */
private fun SemanticsNodeInteraction.textContent(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("") { it.text }
        .orEmpty()
