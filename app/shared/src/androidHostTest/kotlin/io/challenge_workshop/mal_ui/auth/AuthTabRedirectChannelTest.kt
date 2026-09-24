@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui.auth

import android.content.ActivityNotFoundException
import androidx.browser.auth.AuthTabIntent
import androidx.lifecycle.Lifecycle
import io.challenge_workshop.mal_ui.mal.ANDROID_REDIRECT_URI
import io.challenge_workshop.mal_ui.mal.DESKTOP_REDIRECT_URI
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Android Redirect Capture with Auth Tab in front of it, and the race that makes the pair safe.
 *
 * The race exists because a `RESULT_CANCELED` means two opposite things. On Chrome 137+ it is the
 * user closing the tab. On every other browser the Auth Tab silently degrades to a plain Custom Tab
 * and the launcher reports `RESULT_CANCELED` **even on a successful login**, with the redirect
 * arriving through the manifest's intent filter instead. Believing the result code on its own would
 * report the ordinary success path as a cancellation on most of the installed base.
 *
 * So neither side is authoritative alone, and these tests are about the arbitration: a redirect from
 * either side wins outright, and anything else waits out a grace window for the other side before it
 * is believed.
 *
 * Everything framework-shaped is a parameter — the Auth Tab launch, its results, the inbox, the
 * lifecycle — so what is exercised is the arbitration itself. Robolectric only for `Uri`, which is
 * how a `state` is read.
 */
@RunWith(RobolectricTestRunner::class)
class AuthTabRedirectChannelTest {

    private val inbox = AuthRedirectInbox()
    private val lifecycle = MutableStateFlow(Lifecycle.State.RESUMED)
    private val results = AuthTabResultInbox()

    /** Every URL that went out the plain way — the Auth Tab's own fall-through. */
    private val openedPlainly = mutableListOf<String>()

    /** Every URL the Auth Tab was asked to show. */
    private val authTabbed = mutableListOf<String>()
    private var authTabLaunch: (String) -> Unit = { authTabbed += it }

    private fun channel() = AuthTabRedirectChannel(
        intentFilter = IntentRedirectChannel(
            inbox = inbox,
            lifecycleStates = lifecycle,
            launchBrowser = { openedPlainly += it },
        ),
        launchAuthTab = { authTabLaunch(it) },
        results = results,
    )

    /** An armed, opened channel with both sides collecting — where the ViewModel leaves it. */
    private suspend fun TestScope.capturing(state: String): Deferred<AuthRedirectResult> {
        val channel = channel()
        assertEquals(ArmResult.Armed, channel.arm(ANDROID_REDIRECT_URI))
        channel.open(authorizationUrl(state))
        return async { channel.await() }.also { runCurrent() }
    }

    private fun ok(redirect: String) = AuthTabResult(AuthTabIntent.RESULT_OK, redirect)
    private fun cancelled() = AuthTabResult(AuthTabIntent.RESULT_CANCELED, redirect = null)

    /** The browser covering this Activity, and the user coming back to it. */
    private fun TestScope.leaveAndReturn() {
        lifecycle.value = Lifecycle.State.CREATED
        runCurrent()
        lifecycle.value = Lifecycle.State.RESUMED
        runCurrent()
    }

    @Test
    fun the_auth_tab_shows_the_authorization_url_and_nothing_else_opens_a_browser() = runTest {
        val capture = capturing("our-state")

        assertEquals(listOf(authorizationUrl("our-state")), authTabbed)
        // The intent filter has to know which `state` to accept — the degraded Custom Tab answers
        // there — but it must not send the user out a second time.
        assertTrue(openedPlainly.isEmpty(), "the Auth Tab already has the user")
        capture.cancel()
    }

    @Test
    fun a_result_code_of_ok_carries_the_redirect_and_is_believed_at_once() = runTest {
        val capture = capturing("our-state")

        results.deliver(ok(androidRedirect("our-state")))

        assertEquals(AuthRedirectResult.Received(androidRedirect("our-state")), capture.await())
        // No grace window is paid for a redirect: it is the one answer that cannot be a
        // misinterpretation, and making a successful login wait would be the wrong trade.
        assertEquals(0L, currentTime)
    }

    @Test
    fun a_denial_carried_by_an_ok_result_is_handed_on_as_received() = runTest {
        val capture = capturing("our-state")

        // The Auth Tab reports `RESULT_OK` for any redirect to our URI — MAL saying no included.
        results.deliver(ok(androidDenial("our-state")))

        // Not `Failed`: that would be this channel judging MAL's answer, which is
        // `completeAuthorization`'s, so a denial ends the same way here as on every other target.
        assertEquals(AuthRedirectResult.Received(androidDenial("our-state")), capture.await())
    }

    /**
     * The ticket's first rule, and the case that most of the installed base is in: a browser without
     * Auth Tab support degrades to a Custom Tab, reports `RESULT_CANCELED` on success, and the
     * redirect comes through the intent filter a moment later.
     */
    @Test
    fun a_cancellation_followed_by_a_redirect_inside_the_window_is_a_success() = runTest {
        val capture = capturing("our-state")

        results.deliver(cancelled())
        // Runs the Auth Tab side without advancing virtual time, so the window is open but unspent.
        runCurrent()
        inbox.deliver(redirectIntent("our-state"))

        assertEquals(AuthRedirectResult.Received(androidRedirect("our-state")), capture.await())
    }

    @Test
    fun a_cancellation_with_no_redirect_inside_the_window_is_a_cancellation() = runTest {
        val capture = capturing("our-state")

        results.deliver(cancelled())

        assertEquals(AuthRedirectResult.Cancelled, capture.await())
        // And the window was actually waited out rather than the result code being taken at face
        // value — which is the whole difference between this test and the one above it.
        assertTrue(
            currentTime >= AuthTabRedirectChannel.CANCELLATION_GRACE.inWholeMilliseconds,
            "settled after ${currentTime}ms, so no window was held open",
        )
    }

    @Test
    fun a_redirect_after_the_window_has_closed_is_too_late_to_change_the_answer() = runTest {
        val capture = capturing("our-state")
        results.deliver(cancelled())
        assertEquals(AuthRedirectResult.Cancelled, capture.await())

        // The capture is over, so nothing claims this. It stays in the inbox for the next attempt's
        // `state` filter to refuse, which is what stops it completing a later sign-in.
        inbox.deliver(redirectIntent("our-state"))
        runCurrent()

        assertTrue(inbox.holds { true })
    }

    /**
     * Both sides agree, so there is nothing to arbitrate and no reason to make the user wait. This is
     * the ordinary back-press on Chrome 137+: the result code says cancelled and the lifecycle
     * heuristic says the same.
     */
    @Test
    fun a_cancellation_both_sides_report_settles_without_holding_the_window_open() = runTest {
        val capture = capturing("our-state")

        leaveAndReturn()
        results.deliver(cancelled())

        assertEquals(AuthRedirectResult.Cancelled, capture.await())
        assertTrue(
            currentTime < AuthTabRedirectChannel.CANCELLATION_GRACE.inWholeMilliseconds,
            "two agreeing answers should not cost a grace window",
        )
    }

    /**
     * The other ordering, and the one the grace window has to be symmetric for. With Auth Tab in
     * play the lifecycle heuristic is the weaker signal — the redirect never touches the intent
     * filter, so coming back to the app looks exactly like backing out — and it routinely fires
     * before the `ActivityResultCallback` runs.
     */
    @Test
    fun the_lifecycle_heuristic_does_not_beat_a_successful_auth_tab_result() = runTest {
        val capture = capturing("our-state")

        leaveAndReturn()
        results.deliver(ok(androidRedirect("our-state")))

        assertEquals(AuthRedirectResult.Received(androidRedirect("our-state")), capture.await())
    }

    @Test
    fun a_verification_failure_is_reported_as_a_failure_and_not_as_a_cancellation() = runTest {
        // Both are Digital Asset Links outcomes, which a private-use scheme never reaches — so if
        // one ever shows up, the message has to say which, or there is nothing to go on.
        val failed = assertIs<AuthRedirectResult.Failed>(
            resultOf(AuthTabIntent.RESULT_VERIFICATION_FAILED),
        )
        val timedOut = assertIs<AuthRedirectResult.Failed>(
            resultOf(AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT),
        )

        assertNotEquals(failed.message, timedOut.message)
    }

    @Test
    fun an_unrecognised_result_code_is_a_failure_rather_than_a_silent_hang() = runTest {
        // What androidx normalises anything it does not recognise to. A future code arriving as
        // `Cancelled` would look like the user backing out; as a hang it would look like nothing.
        assertTrue(resultOf(AuthTabIntent.RESULT_UNKNOWN_CODE) is AuthRedirectResult.Failed)
    }

    @Test
    fun an_ok_result_with_no_redirect_in_it_is_a_failure() = runTest {
        // Documented as impossible — androidx only fills `resultUri` for `RESULT_OK` — but the field
        // is nullable and there is nothing to complete an authorization with.
        assertTrue(
            resultOf(AuthTabIntent.RESULT_OK, redirect = null) is AuthRedirectResult.Failed,
        )
    }

    @Test
    fun an_auth_tab_that_cannot_be_launched_sends_the_user_out_the_plain_way() = runTest {
        // The browser was uninstalled between the capability check and the click, or the launcher
        // has left composition. Either way the user must still reach MyAnimeList.
        authTabLaunch = { throw ActivityNotFoundException("no Auth Tab after all") }
        val capture = capturing("our-state")

        assertEquals(listOf(authorizationUrl("our-state")), openedPlainly)

        // And the intent filter alone answers, with no grace window over a result code that is
        // never coming.
        inbox.deliver(redirectIntent("our-state"))
        assertEquals(AuthRedirectResult.Received(androidRedirect("our-state")), capture.await())
        assertEquals(0L, currentTime)
    }

    @Test
    fun a_device_with_no_browser_at_all_gives_up_at_once_instead_of_waiting() = runTest {
        // Nothing resolves the Auth Tab intent, and nothing resolves `ACTION_VIEW` either, so the
        // fall-through has nowhere left to go.
        val noBrowser = AuthTabRedirectChannel(
            intentFilter = IntentRedirectChannel(
                inbox = inbox,
                lifecycleStates = lifecycle,
                launchBrowser = { throw ActivityNotFoundException("nothing handles ACTION_VIEW") },
            ),
            launchAuthTab = { throw ActivityNotFoundException("no Auth Tab") },
            results = results,
        )
        noBrowser.arm(ANDROID_REDIRECT_URI)

        noBrowser.open(authorizationUrl("our-state"))

        // `Unsupported`, not `Failed`: the `Authorizing` screen with its copyable URL and its paste
        // field, and no error card. Nothing is broken — there is just no browser to capture from.
        assertEquals(AuthRedirectResult.Unsupported, noBrowser.await())
    }

    @Test
    fun a_redirect_uri_the_manifest_does_not_claim_is_unsupported() = runTest {
        // Delegated to the intent filter's check, which is the one that knows what the manifest
        // claims — and the Auth Tab is launched with that scheme, so a mismatch is unlaunchable too.
        assertEquals(ArmResult.Unsupported, channel().arm(DESKTOP_REDIRECT_URI))
        assertTrue(authTabbed.isEmpty(), "an unarmed channel must not open a browser of its own")
    }

    /**
     * A declining `arm` must cost a live capture nothing — the rule [IntentRedirectChannel.arm]
     * documents, and this side has state of its own that could break it: a result inbox that would be
     * emptied, and the flag that decides whether a result code is even listened for.
     *
     * The result is delivered *before* anything is awaiting, which is the whole reason the inbox
     * replays: a returning Auth Tab result and a fresh sign-in press are both things the user can do
     * while the sign-in button is live. Clearing the inbox for an attempt that was never started
     * would take the answer away from the one that was.
     */
    @Test
    fun a_declined_re_arm_does_not_disturb_a_capture_that_is_still_live() = runTest {
        val channel = channel()
        channel.arm(ANDROID_REDIRECT_URI)
        channel.open(authorizationUrl("our-state"))
        results.deliver(ok(androidRedirect("our-state")))

        assertEquals(ArmResult.Unsupported, channel.arm(DESKTOP_REDIRECT_URI))

        assertEquals(AuthRedirectResult.Received(androidRedirect("our-state")), channel.await())
    }

    @Test
    fun a_result_left_over_from_a_previous_attempt_cannot_answer_the_next_one() = runTest {
        val channel = channel()
        channel.arm(ANDROID_REDIRECT_URI)
        channel.open(authorizationUrl("first-state"))
        // The user closed the first Auth Tab, and nobody was awaiting when the callback fired.
        results.deliver(cancelled())

        assertEquals(ArmResult.Armed, channel.arm(ANDROID_REDIRECT_URI))
        channel.open(authorizationUrl("second-state"))
        val second = async { channel.await() }
        runCurrent()
        inbox.deliver(redirectIntent("second-state"))

        assertEquals(AuthRedirectResult.Received(androidRedirect("second-state")), second.await())
        assertEquals(0L, currentTime, "a stale cancellation must not even open a window")
    }

    /** One Auth Tab result, run through a whole capture, for the mapping assertions above. */
    private suspend fun TestScope.resultOf(
        resultCode: Int,
        redirect: String? = androidRedirect("our-state"),
    ): AuthRedirectResult {
        val capture = capturing("our-state")
        results.deliver(AuthTabResult(resultCode, redirect))
        return capture.await()
    }
}
