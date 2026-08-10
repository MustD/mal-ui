package io.challenge_workshop.mal_ui.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The default channel on every target until the platform ones land.
 *
 * Whether its caller honours [ArmResult.Unsupported] by *not* opening it is
 * `MalSessionViewModelRedirectTest.an_unsupported_channel_never_gets_opened_and_falls_back_to_paste_the_code`.
 */
class PasteOnlyRedirectChannelTest {

    @Test
    fun arming_reports_unsupported_rather_than_failing() = runTest {
        assertEquals(
            ArmResult.Unsupported,
            PasteOnlyRedirectChannel.arm("http://127.0.0.1:18040/oauth/callback"),
            "Unsupported is a modelled path — reporting it as Failed would put an error card in " +
                "front of a login that works.",
        )
    }

    @Test
    fun driving_all_three_phases_anyway_is_harmless() = runTest {
        // A caller that ignores `arm` must not be left with a thrown `open` or a coroutine parked
        // forever on a redirect that nothing is listening for.
        PasteOnlyRedirectChannel.arm("http://127.0.0.1:18040/oauth/callback")
        PasteOnlyRedirectChannel.open("https://myanimelist.net/v1/oauth2/authorize?response_type=code")

        assertEquals(AuthRedirectResult.Unsupported, PasteOnlyRedirectChannel.await())
    }
}
