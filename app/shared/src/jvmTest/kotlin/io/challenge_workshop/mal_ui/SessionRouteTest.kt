@file:OptIn(ExperimentalTestApi::class)

package io.challenge_workshop.mal_ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import io.challenge_workshop.mal_ui.auth.SessionScreenTag
import io.challenge_workshop.mal_ui.screen.ScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The routing `when` and nothing else: every [ScreenState] reaches one tagged screen, and that screen
 * draws something.
 *
 * Exhaustiveness over the sealed interface is already a compiler guarantee, but "compiles" is not
 * "renders something" — a branch could route to a composable that draws nothing, and the tagged
 * screen roots all `fillMaxSize()`, so their mere presence proves nothing. That gap is the whole
 * reason this file exists, and it is now the smallest of the five: what each screen then *draws* is
 * its own file's, and what a Session *produces* is `ScreenStateSourceTest`'s, in `:core`, on four
 * Targets.
 *
 * JVM-only, deliberately, like every rendering test here. [SessionRoute] is common code with no
 * `expect`/`actual` in it, so running it on a second Target would re-test Compose rather than this
 * app: the web and Android Targets would each need their own harness — karma and Robolectric — to
 * prove nothing this module owns.
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
}
