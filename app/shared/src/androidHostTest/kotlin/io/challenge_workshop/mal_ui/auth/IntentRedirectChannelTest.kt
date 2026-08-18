@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui.auth

import android.content.ActivityNotFoundException
import androidx.lifecycle.Lifecycle
import io.challenge_workshop.mal_ui.mal.ANDROID_REDIRECT_URI
import io.challenge_workshop.mal_ui.mal.DESKTOP_REDIRECT_URI
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android Redirect Capture, minus the framework.
 *
 * Everything platform-shaped is a constructor parameter — the inbox, the lifecycle, the browser
 * launch — so what is exercised here is the part that is ours to get wrong: which redirect is
 * accepted, and when a return to the foreground counts as the user backing out.
 *
 * Robolectric only for `Uri`, which is how the channel reads a `state`.
 */
@RunWith(RobolectricTestRunner::class)
class IntentRedirectChannelTest {

    private val inbox = AuthRedirectInbox()
    private val lifecycle = MutableStateFlow(Lifecycle.State.RESUMED)
    private val opened = mutableListOf<String>()
    private var browser: (String) -> Unit = { opened += it }

    private fun channel() = IntentRedirectChannel(
        inbox = inbox,
        lifecycleStates = lifecycle,
        launchBrowser = { browser(it) },
    )

    /** The browser covering this Activity, and the user coming back to it. */
    private fun TestScope.leaveForTheBrowser() {
        lifecycle.value = Lifecycle.State.CREATED
        runCurrent()
    }

    private fun comeBack() {
        lifecycle.value = Lifecycle.State.RESUMED
    }

    /**
     * An armed, opened channel with a capture already collecting — the state every test below
     * starts from, and the one the ViewModel puts it in.
     */
    private suspend fun TestScope.capturing(state: String): Deferred<AuthRedirectResult> {
        val channel = channel()
        assertEquals(ArmResult.Armed, channel.arm(ANDROID_REDIRECT_URI))
        channel.open(authorizationUrl(state))
        return async { channel.await() }.also {
            // Both flows are subscribed from here on, so nothing emitted next is dropped.
            runCurrent()
        }
    }

    @Test
    fun a_redirect_uri_the_manifest_does_not_claim_is_unsupported() = runTest {
        // The manifest claims exactly ANDROID_REDIRECT_URI. Anything else — another target's, a
        // mis-set config — would be captured by nobody, and a hang is the worst answer there is.
        assertEquals(ArmResult.Unsupported, channel().arm(DESKTOP_REDIRECT_URI))
        // Not a failure: nothing is broken, there is simply no capture here, and Paste-the-code is
        // the modelled path for that. Reporting `Failed` would put an error card on a working login.
        assertTrue(opened.isEmpty(), "an unarmed channel must not open a browser of its own")
    }

    @Test
    fun a_redirect_carrying_this_attempts_state_completes_the_capture() = runTest {
        val capture = capturing("our-state")

        inbox.deliver(redirectIntent("our-state"))

        assertEquals(AuthRedirectResult.Received(androidRedirect("our-state")), capture.await())
    }

    @Test
    fun a_redirect_from_another_sign_in_is_refused_and_the_capture_keeps_waiting() = runTest {
        val capture = capturing("our-state")

        // The filter is exported and a private-use scheme is not owned, so any app on the device
        // can fire this. Treating it as ours would let one end a sign-in at will.
        inbox.deliver(redirectIntent("someone-elses-state"))
        runCurrent()
        assertTrue(capture.isActive)

        inbox.deliver(redirectIntent("our-state"))

        assertEquals(AuthRedirectResult.Received(androidRedirect("our-state")), capture.await())
    }

    @Test
    fun coming_back_to_the_app_with_no_redirect_reports_a_cancellation() = runTest {
        val capture = capturing("our-state")

        leaveForTheBrowser()
        comeBack()

        // Best-effort, and never fatal: the caller keeps the Pending Authorization, so a redirect
        // that turns up later is still good.
        assertEquals(AuthRedirectResult.Cancelled, capture.await())
    }

    @Test
    fun a_resume_before_the_browser_is_on_top_is_not_a_cancellation() = runTest {
        val capture = capturing("our-state")

        // The first `onResume` after launching a browser fires before the browser covers us. Acting
        // on it would cancel every sign-in the instant it started.
        comeBack()
        runCurrent()

        assertTrue(capture.isActive)
        capture.cancel()
    }

    /**
     * The redirect and the resume are collected by two independent coroutines, and which of them is
     * scheduled first is not this code's to decide. Here the resume is seen first — the redirect is
     * in the inbox, `onNewIntent` having run before `onResume` as it is documented to, but nothing
     * has picked it up yet. Settling that on a race would report the most ordinary success in the
     * whole flow as a cancellation.
     */
    @Test
    fun a_redirect_already_delivered_beats_the_cancellation_heuristic_even_unseen() = runTest {
        val capture = capturing("our-state")
        leaveForTheBrowser()

        comeBack()
        inbox.deliver(redirectIntent("our-state"))

        assertEquals(AuthRedirectResult.Received(androidRedirect("our-state")), capture.await())
    }

    /**
     * The throw must not escape `open`, and not only because nothing would be listening afterwards:
     * Compose's `AndroidUriHandler` wraps it with "Can't open <the whole authorization URL>", and
     * under `plain` PKCE the code verifier is inside that URL. Letting it out puts the verifier in
     * an error card.
     */
    @Test
    fun a_device_with_no_browser_gives_up_at_once_instead_of_waiting() = runTest {
        browser = { throw ActivityNotFoundException("no activity handles ACTION_VIEW") }
        val channel = channel()
        channel.arm(ANDROID_REDIRECT_URI)

        channel.open(authorizationUrl("our-state"))

        // `Unsupported`, the mapping ticket 16 settles on: the `Authorizing` screen, its copyable
        // authorization URL and its paste field, with no error card.
        assertEquals(AuthRedirectResult.Unsupported, channel.await())
    }

    /**
     * A stop that happens before `await` subscribes is not observable in a `Lifecycle.Event` stream
     * — and `open` launches the browser one statement earlier, so it is a real ordering. A
     * `StateFlow` hands a late subscriber the state the Activity is in.
     */
    @Test
    fun a_stop_that_beat_the_subscription_is_still_a_cancellation() = runTest {
        val channel = channel()
        channel.arm(ANDROID_REDIRECT_URI)
        channel.open(authorizationUrl("our-state"))
        lifecycle.value = Lifecycle.State.CREATED

        val capture = async { channel.await() }
        runCurrent()
        comeBack()

        assertEquals(AuthRedirectResult.Cancelled, capture.await())
    }

    @Test
    fun a_spent_redirect_cannot_answer_the_next_sign_in() = runTest {
        val first = capturing("our-state")
        inbox.deliver(redirectIntent("our-state"))
        first.await()

        // Claiming took it out of the inbox, and the second attempt's `state` is different anyway —
        // two guards, because a re-delivered Intent is ordinary here (recents, a recreation).
        assertFalse(inbox.holds { true })
        val second = capturing("another-state")
        inbox.deliver(redirectIntent("our-state"))
        runCurrent()

        assertTrue(second.isActive, "a second sign-in must not be completed by the first one's code")
        second.cancel()
    }
}
