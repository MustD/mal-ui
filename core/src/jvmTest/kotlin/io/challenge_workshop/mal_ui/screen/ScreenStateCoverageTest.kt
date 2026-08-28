package io.challenge_workshop.mal_ui.screen

import io.challenge_workshop.mal_ui.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Holds [SESSION_STATE_CASES] to the sealed interface, so a fifth `SessionState` cannot be added
 * without `ScreenStateSourceTest` growing a case for it.
 *
 * **jvm-only, and not by choice.** `KClass.sealedSubclasses` exists on jvm and nowhere else, so the
 * enumeration cannot be common code. That is the right half to lose: the *mapping* is what has to run
 * on four Targets, and it does — this only asks whether the list of cases is complete, which is a
 * question about the source tree rather than about any Target.
 */
class ScreenStateCoverageTest {

    @Test
    fun every_session_state_has_a_case_in_the_mapping_test() {
        assertEquals(
            SessionState::class.sealedSubclasses.map { it.simpleName }.toSet(),
            SESSION_STATE_CASES.map { it::class.simpleName }.toSet(),
            "A SessionState subtype has no case in ScreenStateSourceTest, so nothing proves it maps " +
                "to a screen at all.",
        )
    }

    /**
     * One [ScreenState] variant per `SessionState` subtype, named for it.
     *
     * That correspondence is the design — see [ScreenState] — and it is also the thing that makes the
     * two easy to confuse, so it is worth failing loudly when somebody adds a variant on one side
     * only. `ScreenStateSourceTest` is what proves each variant is actually *reached*.
     */
    @Test
    fun there_is_one_screen_state_variant_per_session_state_subtype() {
        assertEquals(
            SessionState::class.sealedSubclasses.map { it.simpleName }.toSet(),
            ScreenState::class.sealedSubclasses.map { it.simpleName }.toSet(),
            "ScreenState and SessionState have drifted apart. They are not synonyms, but there is " +
                "one variant per subtype and they share their names.",
        )
    }
}
