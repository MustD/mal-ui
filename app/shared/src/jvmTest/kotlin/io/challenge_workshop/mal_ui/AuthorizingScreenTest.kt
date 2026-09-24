@file:OptIn(ExperimentalTestApi::class)

package io.challenge_workshop.mal_ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import io.challenge_workshop.mal_ui.mal.DESKTOP_REDIRECT_URI
import io.challenge_workshop.mal_ui.screen.SignInForm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The authorizing screen — the user is away on myanimelist.net — which is Paste-the-code's screen.
 *
 * Paste-the-code stays visible throughout rather than appearing only on failure. It is the one
 * mechanism that works headless, behind a blocked popup and with no Custom-Tabs browser, so it is a
 * modelled path; and the authorization URL has to be reachable by hand because no platform's
 * browser-opening call reliably reports whether it worked.
 */
class AuthorizingScreenTest {

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
                actions.calls.filterNot { it == "pastedRedirectChange" },
            )
        }
    }
}
