@file:OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.challenge_workshop.mal_ui.auth.MalSessionViewModel
import io.challenge_workshop.mal_ui.auth.PasteOnlyRedirectChannel
import io.challenge_workshop.mal_ui.auth.SIGNED_OUT_REASON_TAG
import io.challenge_workshop.mal_ui.auth.SessionScreenTag
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which the JVM test platform does not provide.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = JsonTokenStore(FakeKeyValueStore())
        repository = MalSessionRepository(store, initialConfig = MalAuthConfig(clientId = "a-client-id"))
        viewModel = MalSessionViewModel(repository)
    }

    @AfterTest
    fun tearDown() {
        repository.close()
        Dispatchers.resetMain()
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
                setContent { SessionRoute(state, viewModel) }

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
                setContent { SessionRoute(SessionState.SignedOut(reason), viewModel) }
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
     * resolves to this target's actual, the click reaches the ViewModel through it, and — since that
     * actual is still [PasteOnlyRedirectChannel] — the browser is opened by the screen and the login
     * lands on the paste field, exactly as it did before the channel existed.
     *
     * Routed off the live state rather than a fixed one, because the transition to `Authorizing` is
     * half of what is being checked.
     */
    @Test
    fun signing_in_goes_through_this_targets_channel_and_still_lands_on_paste_the_code() {
        val opened = mutableListOf<String>()
        runComposeUiTest {
            setContent {
                // Otherwise the desktop `UriHandler` really does launch a browser from a unit test.
                CompositionLocalProvider(LocalUriHandler provides RecordingUriHandler(opened)) {
                    SessionRoute(viewModel.state.collectAsState().value, viewModel)
                }
            }

            onNodeWithText("Sign in with MyAnimeList").performClick()

            val state = repository.state.value
            assertTrue(state is SessionState.Authorizing, "$state")
            assertEquals(DESKTOP_REDIRECT_URI, state.pending.redirectUri)
            val authorizationUrl = viewModel.authorizationUrlFor(state.pending)
            assertEquals(listOf(authorizationUrl), opened)

            // Paste-the-code stays reachable throughout: the URL is on screen to copy by hand, since
            // no platform's browser-opening call reliably reports whether it worked.
            onNodeWithTag(SessionScreenTag.Authorizing.tag).assertIsDisplayed()
            onNodeWithText(authorizationUrl).assertIsDisplayed()
            onNodeWithText("Redirect URL or authorization code").assertIsDisplayed()
        }
    }

    @Test
    fun the_debug_panel_starts_collapsed() {
        runComposeUiTest {
            setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel) }

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
            setContent { SessionRoute(SessionState.SignedIn(MalUser(1, "someone")), viewModel) }
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

/** The concatenated text a node draws, for assertions about copy rather than about structure. */
private fun SemanticsNodeInteraction.textContent(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("") { it.text }
        .orEmpty()
