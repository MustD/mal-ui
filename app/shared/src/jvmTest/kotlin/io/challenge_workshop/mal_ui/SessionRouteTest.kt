@file:OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.lifecycle.ViewModelStore
import io.challenge_workshop.mal_ui.animelist.ANIME_LIST_SORT_ORDERS
import io.challenge_workshop.mal_ui.animelist.AnimeListLayout
import io.challenge_workshop.mal_ui.animelist.AnimeListSortOrder
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
import io.challenge_workshop.mal_ui.auth.FAKE_MAL_ANIME_TITLES
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
import io.challenge_workshop.mal_ui.mal.MalTokens
import io.challenge_workshop.mal_ui.mal.MalUser
import io.challenge_workshop.mal_ui.session.FakeKeyValueStore
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.PendingAuthorization
import io.challenge_workshop.mal_ui.session.SessionState
import io.challenge_workshop.mal_ui.session.SignedOutReason
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Url
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * The routing `when` in [App], rendered for real.
 *
 * Exhaustiveness over `SessionState` is already a compiler guarantee, but "compiles" is not
 * "renders something" — a branch could route to a composable that draws nothing, and the tagged
 * screen roots all `fillMaxSize()`, so their mere presence proves nothing. Every branch is therefore
 * checked for *content*, not just for its tag.
 *
 * JVM-only, deliberately. [SessionRoute] is common code with no `expect`/`actual` in it, so running
 * it on a second target would re-test Compose rather than this app. The web and Android targets
 * would each need their own test harness — karma and Robolectric — to prove nothing this module
 * owns.
 */
class SessionRouteTest {

    private lateinit var store: JsonTokenStore
    private lateinit var repository: MalSessionRepository
    private lateinit var viewModel: MalSessionViewModel
    private lateinit var animeList: AnimeListViewModel

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which the JVM test platform does not provide.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = JsonTokenStore(FakeKeyValueStore())
        repository = MalSessionRepository(
            store,
            initialConfig = MalAuthConfig(clientId = "a-client-id"),
            // Not the default factory: the signed-in screen loads the Anime List as soon as it is
            // composed, and a unit test must not make that a real request to myanimelist.net.
            clientFactory = fakeMal(),
        )
        viewModel = MalSessionViewModel(repository, StartupRedirect.None)
        animeList = animeListViewModel(repository)
    }

    @AfterTest
    fun tearDown() {
        repository.close()
        Dispatchers.resetMain()
        // This target's channel binds a real port, and only cancelling releases it. Leaving one
        // armed would break every later test that needs 18040 — and, in the app, the next sign-in.
        assertTrue(
            awaitLoopbackPortFree(),
            "Port $DESKTOP_LOOPBACK_PORT was left bound by this test.",
        )
    }

    /**
     * One case per `SessionState` subtype. [every_session_state_is_covered_by_this_test] holds this
     * list to the sealed interface, so adding a state without adding a case here fails.
     */
    private val cases: List<Pair<SessionState, SessionScreenTag>> = listOf(
        SessionState.Restoring to SessionScreenTag.Restoring,
        SessionState.SignedOut(SignedOutReason.NeverSignedIn) to SessionScreenTag.SignIn,
        SessionState.Authorizing(pendingAuthorization()) to SessionScreenTag.Authorizing,
        SessionState.SignedIn(MalUser(1, "someone")) to SessionScreenTag.SignedIn,
    )

    @Test
    fun every_session_state_renders_one_screen_with_something_on_it() {
        for ((state, expected) in cases) {
            runComposeUiTest {
                setContent { SessionRoute(state, viewModel, animeList) }

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

    @Test
    fun every_session_state_is_covered_by_this_test() {
        assertEquals(
            SessionState::class.sealedSubclasses.map { it.simpleName }.toSet(),
            cases.map { (state, _) -> state::class.simpleName }.toSet(),
            "A SessionState subtype has no case in this test, so nothing proves it renders anything.",
        )
    }

    @Test
    fun each_signed_out_reason_explains_itself_differently() {
        val explanations = SignedOutReason.entries.associateWith { reason ->
            var text = ""
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedOut(reason), viewModel, animeList) }
                text = onNodeWithTag(SIGNED_OUT_REASON_TAG).textContent()
            }
            text
        }

        assertEquals(
            SignedOutReason.entries.size,
            explanations.values.toSet().size,
            "Two reasons share the same copy, which is the bare 'signed out' this enum exists to " +
                "avoid: $explanations",
        )
        // The one the spec names: an expired session must not read like a deliberate sign-out.
        assertTrue(
            "expired" in explanations.getValue(SignedOutReason.RefreshRejected).lowercase(),
            explanations.getValue(SignedOutReason.RefreshRejected),
        )
    }

    /**
     * The seam the channel abstraction rests on, exercised for real: `rememberAuthRedirectChannel()`
     * resolves to this target's actual — a [LoopbackRedirectListener] that binds 18040 — the click
     * reaches the ViewModel through it, and the browser is opened by the *channel* rather than by
     * the screen. Paste-the-code stays on offer throughout regardless.
     *
     * Routed off the live state rather than a fixed one, because the transition to `Authorizing` is
     * half of what is being checked.
     *
     * The waits are real. Arming binds a socket off the main dispatcher and the browser launch runs
     * off it too, so nothing here completes inside `performClick` any more.
     *
     * What the listener then does with a redirect is [LoopbackRedirectListenerTest]'s; this test
     * only proves the wiring reaches it.
     */
    @Test
    fun signing_in_arms_this_targets_capture_and_still_offers_paste_the_code() {
        val opened = Collections.synchronizedList(mutableListOf<String>())
        runComposeUiTest {
            setContent {
                // Otherwise the desktop `UriHandler` really does launch a browser from a unit test.
                CompositionLocalProvider(LocalUriHandler provides RecordingUriHandler(opened)) {
                    SessionRoute(viewModel.state.collectAsState().value, viewModel, animeList)
                }
            }

            onNodeWithText("Sign in with MyAnimeList").performClick()
            waitUntil("the sign-in reaches Authorizing", WAIT_MS) {
                repository.state.value is SessionState.Authorizing
            }

            val state = repository.state.value as SessionState.Authorizing
            assertEquals(DESKTOP_REDIRECT_URI, state.pending.redirectUri)
            val authorizationUrl = viewModel.authorizationUrlFor(state.pending)
            waitUntil("the channel opens the browser", WAIT_MS) {
                opened.toList() == listOf(authorizationUrl)
            }

            // Paste-the-code stays reachable throughout: the URL is on screen to copy by hand, since
            // no platform's browser-opening call reliably reports whether it worked.
            onNodeWithTag(SessionScreenTag.Authorizing.tag).assertIsDisplayed()
            onNodeWithText(authorizationUrl).assertIsDisplayed()
            onNodeWithText("Redirect URL or authorization code").assertIsDisplayed()

            // Not tidying up: cancelling is the *only* thing that gives 18040 back, and a test that
            // walked away from an armed listener would make the next sign-in — here or in the app —
            // fail to arm. [tearDown] holds this to it.
            onNodeWithText("Cancel").performClick()
            waitUntil("cancelling leaves Authorizing", WAIT_MS) {
                repository.state.value !is SessionState.Authorizing
            }
        }
    }

    /**
     * The authorization URL is the whole of Paste-the-code's first half, and it is long enough that
     * selecting it out of a text field by hand is where people give up. No platform's
     * browser-opening call reports failure, so this button is the only guaranteed way to it.
     */
    @Test
    fun the_authorization_url_can_be_copied_without_selecting_it() {
        val clipboard = RecordingClipboard()
        runComposeUiTest {
            val pending = pendingAuthorization()
            @Suppress("DEPRECATION")
            setContent {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    SessionRoute(SessionState.Authorizing(pending), viewModel, animeList)
                }
            }

            onNodeWithText("Copy").performClick()

            assertEquals(viewModel.authorizationUrlFor(pending), clipboard.getText()?.text)
        }
    }

    /**
     * The signed-in branch is the Anime List now, so "it renders something" is no longer enough:
     * what has to be on screen is the user's own entries, fetched through the repository's
     * authenticated client. The fetch is real — [fakeMal] answers it — so this covers the whole path
     * from a MAL response to a card, which is the ticket's tracer bullet.
     *
     * Every fact the spec asks a List Entry to carry is asserted, because they arrive from three
     * different places in MAL's response — the anime, the user's `list_status`, and a field MAL is
     * free to omit — and a card that quietly lost one would still render.
     *
     * Cover art itself is not asserted and [fakeMal] deliberately sends no `main_picture`: fetching
     * one is Coil's job over a real network, which a unit test must not do. What that leaves on
     * screen is the placeholder path, which is the case the spec cares most about anyway — MAL omits
     * the field for entries whose art it has none of.
     */
    @Test
    fun the_signed_in_screen_renders_the_anime_list() {
        runComposeUiTest {
            setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, animeList) }

            waitUntil("the first page lands", WAIT_MS) {
                animeList.state.value.entries.size == FAKE_MAL_ANIME_TITLES.size
            }

            onNodeWithTag(ANIME_LIST_TAG).assertIsDisplayed()
            for (title in FAKE_MAL_ANIME_TITLES) {
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
     * Driven by the control rather than by a parameter, because the Layout is a remembered choice
     * now and the toggle is the only thing that sets it — a test that reached past the control
     * would prove the dense rendering exists without proving anyone can get to it.
     *
     * The same facts as the card, because "the same entries, drawn densely" is the whole claim: a
     * dense row that dropped the Airing Status would be a second, quieter rendering of an entry.
     */
    @Test
    fun toggling_to_the_dense_layout_redraws_the_same_entries() {
        runComposeUiTest {
            setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, animeList) }

            waitUntil("the first page lands", WAIT_MS) {
                animeList.state.value.entries.size == FAKE_MAL_ANIME_TITLES.size
            }

            // Cards on a device that has never chosen: the feature was asked for as a grid of cover
            // art, so a first launch opens on the Layout the user would have picked.
            onNodeWithTag(ANIME_LIST_LAYOUT_TAG).assertIsDisplayed()
            onNodeWithText("Cards").assertIsSelected()
            onNodeWithText("List").performClick()
            waitForIdle()

            assertEquals(AnimeListLayout.List, animeList.layout.value)
            onNodeWithText("List").assertIsSelected()
            onNodeWithText("Cards").assertIsNotSelected()

            for (title in FAKE_MAL_ANIME_TITLES) {
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
     * A Layout change is a presentation change: it asks MAL for nothing.
     *
     * The one property that separates this control from the two beside it. The filter row and the
     * Sort Order menu discard every loaded page and refetch from `offset=0`; the page size is 50 for
     * both Layouts, so the entries already loaded are the entries the other Layout draws. A toggle
     * that refetched would cost a user on a slow connection a page for a change of mind about
     * column count — and would be invisible to every assertion about what is on screen, which is why
     * this one is on the requests.
     */
    @Test
    fun toggling_the_layout_refetches_nothing() {
        val offsets = Collections.synchronizedList(mutableListOf<String>())
        val toggled = pagedRepository(onAnimeListRequest = { offsets += it.parameters["offset"].orEmpty() })
        val toggledList = animeListViewModel(toggled)
        try {
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, toggledList) }

                waitUntil("the first page lands", WAIT_MS) { toggledList.state.value.entries.size == 50 }
                waitForIdle()
                assertEquals(listOf("0"), offsets.toList())

                onNodeWithText("List").performClick()
                waitForIdle()
                onNodeWithText("Cards").performClick()
                waitForIdle()

                assertEquals(
                    listOf("0"),
                    offsets.toList(),
                    "a Layout change is presentation only and must not have asked MAL for anything",
                )
                assertEquals(
                    50,
                    toggledList.state.value.entries.size,
                    "a Layout change must not have discarded the loaded entries either",
                )
            }
        } finally {
            toggled.close()
        }
    }

    /**
     * The Layout the user last chose is the Layout the next launch opens on.
     *
     * There is no process to restart in a unit test, so the restart is a *second*
     * [AnimeListViewModel] over the same [JsonTokenStore] — which is exactly what a relaunch is from
     * the store's point of view, and the only part of a relaunch this behaviour depends on. The
     * control's own state is asserted alongside the rendering, because a screen that drew dense rows
     * under a toggle still reading "Cards" is the same bug seen from the other side.
     */
    @Test
    fun the_layout_is_remembered_for_the_next_launch() {
        runComposeUiTest {
            setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, animeList) }
            waitUntil("the first page lands", WAIT_MS) {
                animeList.state.value.entries.size == FAKE_MAL_ANIME_TITLES.size
            }
            onNodeWithText("List").performClick()
            waitForIdle()
        }

        val relaunched = animeListViewModel(repository)
        try {
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, relaunched) }
                waitUntil("the remembered Layout is read back", WAIT_MS) {
                    relaunched.layout.value == AnimeListLayout.List
                }
                waitForIdle()

                onNodeWithText("List").assertIsSelected()
                // ...and it is the *drawing* that changed, not only the control: the dense row
                // splits the line a card joins.
                onNodeWithText("3 / 26").assertIsDisplayed()
            }
        } finally {
            clear(relaunched)
        }
    }

    /**
     * A device that has never chosen opens on cards, and nothing about that is an error.
     *
     * The store's own default is asserted in `JsonTokenStoreTest`; what this adds is that the screen
     * reaches it — a read that threw, or one whose absent record surfaced as a failure the screen
     * had to handle, would show up here and nowhere else.
     */
    @Test
    fun a_device_that_has_never_chosen_a_layout_opens_on_cards() {
        val fresh = animeListViewModel(repository, store = JsonTokenStore(FakeKeyValueStore()))
        try {
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, fresh) }
                waitForIdle()

                assertEquals(AnimeListLayout.Cards, fresh.layout.value)
                onNodeWithText("Cards").assertIsSelected()
            }
        } finally {
            clear(fresh)
        }
    }

    /**
     * Ticket 03's whole point, driven the way a user drives it: by scrolling, not by a button.
     *
     * The fake holds 120 entries and pages off the `offset` and `limit` the app actually sends, so
     * the assertion on the offsets requested is what says **we** drove them — following MAL's
     * absolute `paging.next` would have gone to api.myanimelist.net and this would not be `[0, 50,
     * 100]`. Scrolling to the last loaded entry is what the proximity trigger reads, and the third
     * page arrives without a `paging.next`, which is what has to stop it asking.
     */
    @Test
    fun scrolling_to_the_end_loads_the_next_page_until_the_list_is_exhausted() {
        val offsets = Collections.synchronizedList(mutableListOf<String>())
        val paged = pagedRepository(onAnimeListRequest = { offsets += it.parameters["offset"].orEmpty() })
        val pagedList = animeListViewModel(paged)
        try {
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, pagedList) }

                waitUntil("the first page lands", WAIT_MS) { pagedList.state.value.entries.size == 50 }
                waitForIdle()
                assertEquals(
                    listOf("0"),
                    offsets.toList(),
                    "a first page that has not been scrolled past must not have fetched a second",
                )

                scrollToLastLoadedEntry(pagedList)
                waitUntil("scrolling near the end fetches the second page", WAIT_MS) {
                    pagedList.state.value.entries.size == 100
                }

                scrollToLastLoadedEntry(pagedList)
                waitUntil("and the third", WAIT_MS) { pagedList.state.value.entries.size == 120 }

                assertTrue(
                    pagedList.state.value.exhausted,
                    "the last page carried no `paging.next`, so the pager must stop asking",
                )
                // Still at the bottom of an exhausted list: the trigger keeps firing and must cost
                // nothing. Scrolling again is exactly what a user parked at the end does.
                scrollToLastLoadedEntry(pagedList)
                waitForIdle()
                onNodeWithText("Anime 120").assertIsDisplayed()
                assertEquals(
                    listOf("0", "50", "100"),
                    offsets.toList(),
                    "offsets are driven from our side, once each, and stop at the true end",
                )
            }
        } finally {
            paged.close()
        }
    }

    /**
     * The other half of an unbounded list: the page that fails on the way down.
     *
     * Everything already loaded has to stay exactly where it is — losing 50 entries and the user's
     * place to a flaky network is the failure this screen state exists to prevent — and the retry
     * at the bottom has to work, because `AnimeListPager.next()` will not re-request a page that
     * failed. Without that button a scroll trigger that has given up is a dead end.
     */
    @Test
    fun a_page_that_fails_mid_scroll_keeps_the_list_and_offers_a_retry_that_works() {
        var failing = true
        val flaky = pagedRepository(failAnimeListAt = { offset -> offset == 50 && failing })
        val flakyList = animeListViewModel(flaky)
        try {
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, flakyList) }
                waitUntil("the first page lands", WAIT_MS) { flakyList.state.value.entries.size == 50 }

                scrollToLastLoadedEntry(flakyList)
                waitUntil("the second page fails", WAIT_MS) { flakyList.state.value.moreError != null }

                assertEquals(
                    50,
                    flakyList.state.value.entries.size,
                    "a failure at the bottom must not discard what is already loaded",
                )
                onNodeWithTag(ANIME_LIST_MORE_TAG).assertIsDisplayed()

                failing = false
                // Fully into view first: the row is at the very bottom, and a click aimed at a node
                // that is only half on screen lands outside the window. Scrolling here is safe —
                // the trigger fires and `next()` refuses, which is the guard this ticket added.
                onNodeWithTag(ANIME_LIST_TAG).performScrollToNode(hasTestTag(ANIME_LIST_MORE_TAG))
                onNodeWithText("Try again").performClick()

                waitUntil("the retry lands the page that failed", WAIT_MS) {
                    flakyList.state.value.entries.size == 100
                }
                onNodeWithTag(ANIME_LIST_MORE_TAG).assertDoesNotExist()
            }
        } finally {
            flaky.close()
        }
    }

    /**
     * Ticket 04, end to end: the chip reaches MAL's `status` parameter, the answer replaces the
     * list, and the list comes back to the top.
     *
     * The two slices are given different titles deliberately — a fake that served the same entries
     * under every filter could not tell "the filter reached MAL" from "the chip did nothing". The
     * scroll matters for the same reason: the assertion that `Episode 1` is *displayed* is only
     * meaningful because the screen was 100 entries down a different list when the chip was tapped,
     * and the replacement is far too long to fit on screen.
     */
    @Test
    fun choosing_a_watch_status_refetches_that_slice_and_returns_to_the_top() {
        val requests = Collections.synchronizedList(mutableListOf<Url>())
        val filtered = pagedRepository(
            animeListTitles = { url ->
                if (url.parameters["status"] == "watching") (1..60).map { "Episode $it" }
                else (1..120).map { "Anime $it" }
            },
            onAnimeListRequest = { requests += it },
        )
        val filteredList = animeListViewModel(filtered)
        try {
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, filteredList) }

                waitUntil("the unfiltered first page lands", WAIT_MS) {
                    filteredList.state.value.entries.size == 50
                }
                // Exactly one chip is active, and on launch it is All — the whole list rather than
                // an arbitrary slice of it.
                onNodeWithText("All").assertIsSelected()
                for (other in listOf("Watching", "Completed", "On hold", "Dropped", "Plan to watch")) {
                    onNodeWithText(other).assertIsNotSelected()
                }

                scrollToLastLoadedEntry(filteredList)
                waitUntil("a second page of the whole list lands", WAIT_MS) {
                    filteredList.state.value.entries.size == 100
                }

                // A hundred entries down, and still on screen: the row is a sibling of the list
                // rather than an item in it, precisely so it does not scroll out of reach.
                onNodeWithTag(ANIME_LIST_FILTERS_TAG).assertIsDisplayed()

                onNodeWithText("Watching").performClick()

                waitUntil("the filtered first page replaces it", WAIT_MS) {
                    filteredList.state.value.entries.size == 50 &&
                        filteredList.state.value.entries.first().title == "Episode 1"
                }
                waitForIdle()

                assertEquals(
                    listOf(null to "0", null to "50", "watching" to "0"),
                    requests.map { it.parameters["status"] to it.parameters["offset"] },
                    "All sends no `status` at all, the chip sends exactly one value, and the new " +
                        "list is fetched from its own start — with nothing prefetched off the " +
                        "scroll position the old list was left at",
                )
                // Scrolled back to the top of a list far longer than the window, so this is only
                // on screen if the swap took the old scroll position with it.
                onNodeWithText("Episode 1").assertIsDisplayed()
                onNodeWithText("Anime 1").assertDoesNotExist()
                // Still exactly one, and now the one that was tapped.
                onNodeWithText("Watching").assertIsSelected()
                onNodeWithText("All").assertIsNotSelected()
            }
        } finally {
            filtered.close()
        }
    }

    /**
     * The window between the tap and the replacement, which is the whole reason the pager keeps the
     * old entries: a grid that empties to a spinner on every tap reads as broken.
     *
     * The filter row has to be disabled through it as well — the entries on screen are the *old*
     * filter's, so a row that still looked live would be inviting a second tap against a list that
     * has not changed yet.
     */
    @Test
    fun the_previous_entries_stay_on_screen_with_the_filters_disabled_until_the_replacement_lands() {
        val releaseFilteredPage = CompletableDeferred<Unit>()
        val holding = pagedRepository(
            animeListTitles = { url ->
                if (url.parameters["status"] == null) (1..120).map { "Anime $it" } else listOf("Episode 1")
            },
            holdAnimeList = { request ->
                if (request.url.parameters["status"] != null) releaseFilteredPage.await()
            },
        )
        val holdingList = animeListViewModel(holding)
        try {
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, holdingList) }
                waitUntil("the unfiltered first page lands", WAIT_MS) {
                    holdingList.state.value.entries.size == 50
                }

                onNodeWithText("Completed").performClick()
                waitUntil("the replacement page is in flight", WAIT_MS) {
                    holdingList.state.value.loadingFirstPage
                }
                waitForIdle()

                onNodeWithText("Anime 1").assertIsDisplayed()
                for (filter in listOf("All", "Watching", "Completed")) {
                    onNodeWithText(filter).assertIsNotEnabled()
                }

                releaseFilteredPage.complete(Unit)
                waitUntil("the replacement lands", WAIT_MS) {
                    holdingList.state.value.entries.map { it.title } == listOf("Episode 1")
                }
                waitForIdle()

                onNodeWithText("Anime 1").assertDoesNotExist()
                onNodeWithText("All").assertIsEnabled()
            }
        } finally {
            holding.close()
        }
    }

    /**
     * Ticket 05, end to end: the menu entry reaches MAL's `sort` parameter, the re-ordered answer
     * replaces the list, and the list comes back to the top.
     *
     * The re-ordered slice is given different titles for the same reason the filter test does — a
     * fake that served the same entries under every `sort` could not tell "the menu reached MAL"
     * from "the entry did nothing". Ordering is MAL's job: the list is paged, so nothing here
     * re-orders what is loaded, and that is also why there is no reverse toggle to click. See
     * ADR-0003.
     */
    @Test
    fun choosing_a_sort_order_refetches_in_that_order_and_returns_to_the_top() {
        val requests = Collections.synchronizedList(mutableListOf<Url>())
        val sorted = pagedRepository(
            animeListTitles = { url ->
                if (url.parameters["sort"] == "anime_title") (1..60).map { "Alphabetical $it" }
                else (1..120).map { "Anime $it" }
            },
            onAnimeListRequest = { requests += it },
        )
        val sortedList = animeListViewModel(sorted)
        try {
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, sortedList) }

                waitUntil("the first page lands", WAIT_MS) { sortedList.state.value.entries.size == 50 }
                // The launch default, and it says which way it sorts rather than just naming a field.
                onNodeWithTag(ANIME_LIST_SORT_TAG).assertTextContains("Last updated (newest first)", substring = true)

                scrollToLastLoadedEntry(sortedList)
                waitUntil("a second page lands", WAIT_MS) { sortedList.state.value.entries.size == 100 }

                // A hundred entries down, and still on screen: the control is a sibling of the list
                // rather than an item in it, precisely so it does not scroll out of reach.
                onNodeWithTag(ANIME_LIST_SORT_TAG).assertIsDisplayed().performClick()
                onNodeWithText("Title (A–Z)").performClick()

                waitUntil("the re-ordered first page replaces it", WAIT_MS) {
                    sortedList.state.value.entries.size == 50 &&
                        sortedList.state.value.entries.first().title == "Alphabetical 1"
                }
                waitForIdle()

                assertEquals(
                    listOf("list_updated_at" to "0", "list_updated_at" to "50", "anime_title" to "0"),
                    requests.map { it.parameters["sort"] to it.parameters["offset"] },
                    "the menu sends exactly one `sort` value and the re-ordered list is fetched " +
                        "from its own start — `offset` counts positions in whichever order the " +
                        "query names, so carrying the old one would skip the first fifty of it",
                )
                // Scrolled back to the top of a list far longer than the window, so this is only on
                // screen if the swap took the old scroll position with it.
                onNodeWithText("Alphabetical 1").assertIsDisplayed()
                onNodeWithText("Anime 1").assertDoesNotExist()
                onNodeWithTag(ANIME_LIST_SORT_TAG).assertTextContains("Title (A–Z)", substring = true)
            }
        } finally {
            sorted.close()
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
            setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, animeList) }
            waitUntil("the first page lands", WAIT_MS) { animeList.state.value.loaded }

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

    /**
     * The Anime List taking over the signed-in screen must not cost the things that were on it.
     * Ticket 09 rehouses them into the top app bar's overflow menu, which is this.
     *
     * Asserted on the open menu rather than on the screen, because that is now the only place any of
     * them can be — a "Sign out" that were still lying loose on the screen would pass an assertion
     * over the whole tree and mean the rehousing never happened.
     */
    @Test
    fun the_overflow_menu_carries_reload_sign_out_and_session_diagnostics() {
        runComposeUiTest {
            setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, animeList) }

            waitUntil("the first page lands", WAIT_MS) { animeList.state.value.loaded }

            // One list, so the count asserted below is the count of the entries named above it
            // rather than a number that can drift away from them.
            val entries = listOf("Reload", "Sign out", "Session diagnostics")

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
        }
    }

    /**
     * The top app bar's whole job besides the menu: say who is signed in.
     *
     * Measured rather than read, because truncation is not in the semantics tree — an ellipsised
     * name and a wrapped one both read back as the same string, and a bar that wrapped a long name
     * would push the list down by however many lines the name happened to need. So the assertion is
     * that a name nobody could fit takes exactly the height a short one does.
     */
    @Test
    fun the_top_app_bar_names_the_user_and_truncates_rather_than_wrapping() {
        fun heightOf(name: String): Dp {
            var height = 0.dp
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, name)), viewModel, animeList) }
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
     * A Session refresh is not a navigation. `SessionState.SignedIn` carries `refreshing` precisely
     * so the screen can say so without being swapped out, and a list that unmounted for it would
     * lose every page the user has scrolled through.
     */
    @Test
    fun a_session_refresh_does_not_blank_the_signed_in_screen() {
        runComposeUiTest {
            var refreshing by mutableStateOf(false)
            setContent {
                SessionRoute(SessionState.SignedIn(MalUser(1, "someone"), refreshing), viewModel, animeList)
            }
            waitUntil("the first page lands", WAIT_MS) { animeList.state.value.loaded }
            onNodeWithText(FAKE_MAL_ANIME_TITLES.first()).assertIsDisplayed()

            refreshing = true
            waitForIdle()

            onNodeWithTag(ANIME_LIST_TAG).assertIsDisplayed()
            onNodeWithText(FAKE_MAL_ANIME_TITLES.first()).assertIsDisplayed()
            onNodeWithTag(SESSION_USER_NAME_TAG).assertIsDisplayed()
        }
    }

    /**
     * Reload is the way to pick up a change made on myanimelist.net, so it has to be a refetch of
     * the list the user is actually looking at — not of the default one. The filter and the Sort
     * Order are both moved off their defaults first, because a Reload that quietly dropped either
     * would look identical to a working one on a screen that had never been touched.
     */
    @Test
    fun reload_refetches_the_first_page_with_the_current_filter_and_sort_order() {
        val requests = Collections.synchronizedList(mutableListOf<Url>())
        val reloading = pagedRepository(onAnimeListRequest = { requests += it })
        val reloadingList = animeListViewModel(reloading)
        try {
            runComposeUiTest {
                setContent {
                    SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, reloadingList)
                }
                waitUntil("the first page lands", WAIT_MS) { reloadingList.state.value.entries.size == 50 }

                onNodeWithText("Watching").performClick()
                waitUntil("the filtered page lands", WAIT_MS) {
                    reloadingList.state.value.watchStatus == WatchStatus.Watching &&
                        !reloadingList.state.value.loadingFirstPage
                }
                onNodeWithTag(ANIME_LIST_SORT_TAG).performClick()
                onNodeWithText("Title (A–Z)").performClick()
                waitUntil("the re-ordered page lands", WAIT_MS) {
                    reloadingList.state.value.sortOrder == AnimeListSortOrder.Title &&
                        !reloadingList.state.value.loadingFirstPage
                }
                // Two pages deep, so a Reload that started from where the scroll left off rather
                // than from `offset=0` would show up in the request below.
                scrollToLastLoadedEntry(reloadingList)
                waitUntil("a second page lands", WAIT_MS) { reloadingList.state.value.entries.size == 100 }
                requests.clear()

                onNodeWithTag(SESSION_MENU_BUTTON_TAG).performClick()
                onNodeWithText("Reload").performClick()

                waitUntil("the reloaded first page lands", WAIT_MS) {
                    reloadingList.state.value.entries.size == 50
                }
                assertEquals(
                    listOf(Triple("watching", "anime_title", "0")),
                    requests.map {
                        Triple(it.parameters["status"], it.parameters["sort"], it.parameters["offset"])
                    },
                    "Reload must refetch the list on screen from its start, and ask for it once",
                )
            }
        } finally {
            reloading.close()
        }
    }

    /**
     * `SessionDebugPanel` is a real tool rather than scaffolding — it is the only way a human ever
     * sees the refresh path execute — so rehousing it must not cost any of its controls. The dialog
     * is where it went; the menu entry is its disclosure now, in place of the panel's own.
     */
    @Test
    fun session_diagnostics_opens_the_debug_panel_in_a_dialog() {
        runComposeUiTest {
            setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, animeList) }
            waitUntil("the Anime List settles", WAIT_MS) { animeList.state.value.loaded }

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
     * The wiring the `MockEngine` test in `:core` cannot see: that the button is connected to
     * [MalSessionViewModel.forceExpireAccessToken] at all. Whether the resulting 401 then drives
     * exactly one refresh is
     * `MalSessionRefreshTest.forcing_the_access_token_to_expire_drives_exactly_one_real_refresh`.
     */
    @Test
    fun the_force_401_button_invalidates_the_stored_access_token() {
        runComposeUiTest {
            store.writeSession(
                MalTokens("Bearer", 2_415_600, "a-valid-access-token", "a-refresh-token"),
                MalUser(1, "someone"),
            )
            setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, animeList) }
            waitUntil("the Anime List settles", WAIT_MS) { animeList.state.value.loaded }
            openDiagnostics()

            onNodeWithText("Force 401").performClick()

            assertEquals(
                "a-refresh-token",
                store.readSession()?.tokens?.refreshToken,
                "Force 401 must keep the refresh token — there is nothing to refresh with otherwise.",
            )
            onNodeWithText("deliberately invalidated", substring = true).assertIsDisplayed()
        }
    }

    /**
     * Ticket 06's first state. A centred spinner and a list are different shapes, so the page jumps
     * when the data lands; a skeleton is the list, drawn empty.
     *
     * `onAllNodesWithTag`, because the skeleton is one tagged placeholder per grid cell rather than
     * one tagged wrapper — which is what lets the grid lay the placeholders out at the very column
     * width the cards replacing them will get.
     */
    @Test
    fun the_first_page_shows_a_skeleton_in_the_shape_of_the_list() {
        val releaseFirstPage = CompletableDeferred<Unit>()
        val holding = pagedRepository(holdAnimeList = { releaseFirstPage.await() })
        val holdingList = animeListViewModel(holding)
        try {
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, holdingList) }
                waitUntil("the first page is in flight", WAIT_MS) { holdingList.state.value.loadingFirstPage }
                waitForIdle()

                onAllNodesWithTag(ANIME_LIST_SKELETON_TAG)[0].assertIsDisplayed()

                releaseFirstPage.complete(Unit)
                waitUntil("the first page lands", WAIT_MS) { holdingList.state.value.entries.size == 50 }
                waitForIdle()

                onAllNodesWithTag(ANIME_LIST_SKELETON_TAG).assertCountEquals(0)
                onNodeWithText("Anime 1").assertIsDisplayed()
            }
        } finally {
            holding.close()
        }
    }

    /**
     * Ticket 06's second state: an empty account is not a broken app, and the fix for it is not in
     * this app at all — which is why it comes with a way out to myanimelist.net.
     */
    @Test
    fun an_empty_anime_list_says_so_and_points_at_myanimelist() {
        val opened = mutableListOf<String>()
        val nothing = pagedRepository(animeListTitles = { emptyList() })
        val nothingList = animeListViewModel(nothing)
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalUriHandler provides RecordingUriHandler(opened)) {
                        SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, nothingList)
                    }
                }
                waitUntil("the empty first page lands", WAIT_MS) { nothingList.state.value.loaded }
                waitForIdle()

                onNodeWithTag(ANIME_LIST_EMPTY_TAG).assertIsDisplayed()
                assertTrue(
                    onNodeWithTag(ANIME_LIST_EMPTY_TAG).textContent().contains("nothing on your MyAnimeList"),
                    "an empty account must be told it is empty, not left with a blank screen",
                )

                onNodeWithText("Open myanimelist.net").performClick()

                assertEquals(listOf(MY_ANIME_LIST_URL), opened)
            }
        } finally {
            nothing.close()
        }
    }

    /**
     * Ticket 06's third state, and the pair this spec is easiest to collapse by accident: an empty
     * *slice* is a filter to undo, and saying "your list is empty" to a user with four hundred
     * completed shows is a false statement about their account.
     *
     * So the message names the filter and the way out is one tap, rather than the user having to
     * work out that the chip they tapped is what emptied the screen.
     */
    @Test
    fun a_filter_that_matches_nothing_names_it_and_offers_a_way_back() {
        val sliced = pagedRepository(
            animeListTitles = { url ->
                if (url.parameters["status"] == "on_hold") emptyList() else (1..120).map { "Anime $it" }
            },
        )
        val slicedList = animeListViewModel(sliced)
        try {
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, slicedList) }
                waitUntil("the whole list lands", WAIT_MS) { slicedList.state.value.entries.size == 50 }

                onNodeWithText("On hold").performClick()
                waitUntil("the empty slice lands", WAIT_MS) {
                    slicedList.state.value.let { it.loaded && it.entries.isEmpty() }
                }
                waitForIdle()

                assertEquals(
                    "Nothing on hold.",
                    onNodeWithTag(ANIME_LIST_EMPTY_TAG).textContent(),
                    "the message must name the filter rather than report an empty account",
                )

                onNodeWithText("Show all").performClick()

                waitUntil("the whole list comes back", WAIT_MS) { slicedList.state.value.entries.size == 50 }
                waitForIdle()
                onNodeWithText("Anime 1").assertIsDisplayed()
                onNodeWithText("All").assertIsSelected()
                onNodeWithTag(ANIME_LIST_EMPTY_TAG).assertDoesNotExist()
            }
        } finally {
            sliced.close()
        }
    }

    /**
     * Ticket 06's fifth state. Nothing loaded, so there is nothing to keep on screen and the error
     * is the screen — which is the half of the pair that must *not* look like a failed later page.
     */
    @Test
    fun a_failed_first_page_is_an_error_with_a_retry_that_works() {
        var failing = true
        val flaky = pagedRepository(failAnimeListAt = { offset -> offset == 0 && failing })
        val flakyList = animeListViewModel(flaky)
        try {
            runComposeUiTest {
                setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, flakyList) }
                waitUntil("the first page fails", WAIT_MS) { flakyList.state.value.firstPageError != null }
                waitForIdle()

                onNodeWithTag(ANIME_LIST_ERROR_TAG).assertIsDisplayed()
                onAllNodesWithTag(ANIME_LIST_SKELETON_TAG).assertCountEquals(0)
                onNodeWithTag(ANIME_LIST_MORE_TAG).assertDoesNotExist()
                onNodeWithTag(ANIME_LIST_EMPTY_TAG).assertDoesNotExist()

                failing = false
                onNodeWithText("Retry").performClick()

                waitUntil("the retry lands the page that failed", WAIT_MS) {
                    flakyList.state.value.entries.size == 50
                }
                waitForIdle()
                onNodeWithTag(ANIME_LIST_ERROR_TAG).assertDoesNotExist()
                onNodeWithText("Anime 1").assertIsDisplayed()
            }
        } finally {
            flaky.close()
        }
    }

    /**
     * Ends a hand-built ViewModel's `viewModelScope`, which is otherwise never ended.
     *
     * A ViewModel built by a test rather than by a `ViewModelStore` has nothing that will ever clear
     * it, so its scope outlives the test with a page request still in it — to land after `tearDown`
     * has closed the repository underneath it and reset the Main dispatcher. Through a
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

    /**
     * An [AnimeListViewModel] over this test's own store, so a Layout written by one survives into
     * the next — which is what "remembered across a launch" means when there is no process to
     * restart. [store] is a parameter so a test can hand it a store that has never been written to.
     */
    private fun animeListViewModel(
        repository: MalSessionRepository,
        store: JsonTokenStore = this.store,
    ) = AnimeListViewModel(
        repository,
        // A preference per "launch", over the store handed in: the record is the store's and the
        // in-memory value is not, which is exactly the split a relaunch has.
        //
        // `Unconfined` rather than the app's `Dispatchers.Main.immediate`: the startup read then
        // lands during construction, so a rendering test never has to wait on a preference to find
        // out what Layout the screen opens on — and nothing of this scope outlives `resetMain`.
        LayoutPreference(store, CoroutineScope(Dispatchers.Unconfined)),
    )

    /**
     * A repository over a MAL that holds 120 entries — more than two pages of 50, so paging has a
     * middle as well as an end. Its own repository rather than [setUp]'s, because the default fake
     * holds one short page: every other test on this screen wants that, and this one cannot use it.
     */
    private fun pagedRepository(
        animeListTitles: (Url) -> List<String> = { (1..120).map { "Anime $it" } },
        failAnimeListAt: (Int) -> Boolean = { false },
        holdAnimeList: suspend (HttpRequestData) -> Unit = {},
        onAnimeListRequest: (Url) -> Unit = {},
    ) = MalSessionRepository(
        store,
        initialConfig = MalAuthConfig(clientId = "a-client-id"),
        clientFactory = fakeMal(
            animeListTitles = animeListTitles,
            failAnimeListAt = failAnimeListAt,
            holdAnimeList = holdAnimeList,
            onRequest = { request ->
                if (request.url.encodedPath.endsWith("/users/@me/animelist")) {
                    onAnimeListRequest(request.url)
                }
            },
        ),
    )

    /**
     * Puts the last loaded entry on screen, which is what the proximity trigger reads.
     *
     * By index rather than by text, because the trigger is about *position in the layout* and a
     * `performScrollToNode` would stop as soon as the node was composed rather than at the end.
     * The lazy list holds one chrome item above the entries and one below — the rest of the chrome
     * is in the top app bar now — so the entry count is always a valid index inside it and always
     * within a screenful of the bottom.
     */
    private fun ComposeUiTest.scrollToLastLoadedEntry(animeList: AnimeListViewModel) {
        onNodeWithTag(ANIME_LIST_TAG).performScrollToIndex(animeList.state.value.entries.size)
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

    private fun pendingAuthorization() = PendingAuthorization(
        codeVerifier = "a-verifier",
        state = "a-state",
        redirectUri = DESKTOP_REDIRECT_URI,
        clientId = "a-client-id",
        startedAtEpochMs = 0L,
    )
}

/**
 * Generous, because these waits are for real sockets and real dispatchers rather than for a frame.
 * A wait that is too short reads as a flaky test; one that is too long only costs time when
 * something is already broken.
 */
private const val WAIT_MS: Long = 5_000

/** The concatenated text a node draws, for assertions about copy rather than about structure. */
private fun SemanticsNodeInteraction.textContent(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("") { it.text }
        .orEmpty()
