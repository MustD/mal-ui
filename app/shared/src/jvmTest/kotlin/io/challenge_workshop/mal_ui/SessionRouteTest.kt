@file:OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import io.challenge_workshop.mal_ui.animelist.AnimeListViewModel
import io.challenge_workshop.mal_ui.auth.LoopbackRedirectListener
import io.challenge_workshop.mal_ui.auth.LoopbackRedirectListenerTest
import io.challenge_workshop.mal_ui.auth.MalSessionViewModel
import io.challenge_workshop.mal_ui.auth.StartupRedirect
import io.challenge_workshop.mal_ui.auth.awaitLoopbackPortFree
import io.challenge_workshop.mal_ui.auth.SIGNED_OUT_REASON_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_MORE_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_FILTERS_TAG
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_TAG
import io.challenge_workshop.mal_ui.auth.FAKE_MAL_ANIME_TITLES
import io.challenge_workshop.mal_ui.auth.SessionScreenTag
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        animeList = AnimeListViewModel(repository)
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
     * from a MAL response to a row, which is the ticket's tracer bullet.
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
            // Watched-of-total, which is the other half of what a row is for.
            onNodeWithText("3 / 26").assertIsDisplayed()
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
        val pagedList = AnimeListViewModel(paged)
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
        val flakyList = AnimeListViewModel(flaky)
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
            animeListTitles = { status ->
                if (status == "watching") (1..60).map { "Episode $it" } else (1..120).map { "Anime $it" }
            },
            onAnimeListRequest = { requests += it },
        )
        val filteredList = AnimeListViewModel(filtered)
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
            animeListTitles = { status ->
                if (status == null) (1..120).map { "Anime $it" } else listOf("Episode 1")
            },
            holdAnimeList = { request ->
                if (request.url.parameters["status"] != null) releaseFilteredPage.await()
            },
        )
        val holdingList = AnimeListViewModel(holding)
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
     * The Anime List taking over the signed-in screen must not cost the two things that were on it.
     * Ticket 09 rehouses both into a top app bar; until then they are simply still here, and this is
     * what says so.
     */
    @Test
    fun sign_out_and_the_debug_panel_survive_the_anime_list_arriving() {
        runComposeUiTest {
            setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, animeList) }

            waitUntil("the first page lands", WAIT_MS) { animeList.state.value.loaded }

            onNodeWithText("Sign out").assertIsDisplayed()
            onNodeWithText("Session diagnostics").assertIsDisplayed()
        }
    }

    @Test
    fun the_debug_panel_starts_collapsed() {
        runComposeUiTest {
            setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel, animeList) }
            // The Anime List loads asynchronously above the panel. Clicking before it lands aims at
            // where the panel *was*, and the list then pushes it out from under the click.
            waitUntil("the Anime List settles", WAIT_MS) { animeList.state.value.loaded }

            onNodeWithText("Session diagnostics").assertIsDisplayed()
            // "Force 401" writes an invalid token into the store, so it must not be a stray tap away.
            onNodeWithText("Force 401").assertDoesNotExist()

            onNodeWithText("Session diagnostics").performClick()

            onNodeWithText("Force 401").assertIsDisplayed()
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
            onNodeWithText("Session diagnostics").performClick()

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
     * A repository over a MAL that holds 120 entries — more than two pages of 50, so paging has a
     * middle as well as an end. Its own repository rather than [setUp]'s, because the default fake
     * holds one short page: every other test on this screen wants that, and this one cannot use it.
     */
    private fun pagedRepository(
        animeListTitles: (String?) -> List<String> = { (1..120).map { "Anime $it" } },
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
     * The lazy list holds two chrome items above the entries and one below, so the entry count is
     * always a valid index inside it and always within a screenful of the bottom.
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
