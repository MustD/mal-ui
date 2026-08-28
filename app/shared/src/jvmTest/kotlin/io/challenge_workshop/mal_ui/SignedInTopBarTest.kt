@file:OptIn(ExperimentalTestApi::class)

package io.challenge_workshop.mal_ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.challenge_workshop.mal_ui.auth.ANIME_LIST_TAG
import io.challenge_workshop.mal_ui.auth.SESSION_DIAGNOSTICS_TAG
import io.challenge_workshop.mal_ui.auth.SESSION_MENU_BUTTON_TAG
import io.challenge_workshop.mal_ui.auth.SESSION_MENU_TAG
import io.challenge_workshop.mal_ui.auth.SESSION_TOP_BAR_TAG
import io.challenge_workshop.mal_ui.auth.SESSION_USER_NAME_TAG
import io.challenge_workshop.mal_ui.mal.MalUser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The top app bar over the signed-in screen, and the diagnostics dialog its overflow menu opens.
 *
 * It exists because the Anime List **is** the signed-in screen now: everything that was on that
 * screen before — the name, Reload, Sign out, the debug panel — had nowhere left to sit, since
 * anywhere below the entries is unreachable on a real account. So what these tests are really about
 * is that the Anime List taking the screen over cost none of them.
 */
class SignedInTopBarTest {

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
}
