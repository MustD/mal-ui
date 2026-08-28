@file:OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import io.challenge_workshop.mal_ui.animelist.AnimeListViewModel
import io.challenge_workshop.mal_ui.animelist.LayoutPreference
import io.challenge_workshop.mal_ui.auth.LoopbackRedirectListener
import io.challenge_workshop.mal_ui.auth.LoopbackRedirectListenerTest
import io.challenge_workshop.mal_ui.auth.MalSessionViewModel
import io.challenge_workshop.mal_ui.auth.SIGNED_OUT_REASON_TAG
import io.challenge_workshop.mal_ui.auth.SessionScreenTag
import io.challenge_workshop.mal_ui.auth.StartupRedirect
import io.challenge_workshop.mal_ui.auth.awaitLoopbackPortFree
import io.challenge_workshop.mal_ui.auth.fakeMal
import io.challenge_workshop.mal_ui.mal.DESKTOP_LOOPBACK_PORT
import io.challenge_workshop.mal_ui.mal.DESKTOP_REDIRECT_URI
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.session.FakeKeyValueStore
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.SessionState
import io.challenge_workshop.mal_ui.session.authorizationUrlFor
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
 * The sign-in screen: what it says, what it reports, and the one seam in this whole test tree that
 * still needs the real stack behind it.
 *
 * MAL supports only the authorization code grant, so there is no password field here and never will
 * be — the password is typed on myanimelist.net and this app only ever sees a code.
 */
class SignInScreenTest {

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
                val authorizationUrl = authorizationUrlFor(repository.config.value, state.pending)
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
}
