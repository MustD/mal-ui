package io.challenge_workshop.mal_ui.auth

import androidx.browser.auth.AuthTabIntent
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Android's Redirect Capture on a device that has an Auth Tab: the Auth Tab in front, the manifest's
 * intent filter behind it, and the two **raced**.
 *
 * `AuthTabIntent` is the right tool for this and the biggest change since this problem was last
 * "solved": it hands the redirect back through an `ActivityResultLauncher`, so no intent filter is
 * involved, and it reports the user backing out as a real result code instead of the lifecycle
 * heuristic [IntentRedirectChannel] has to make do with.
 *
 * It is not enough on its own, and the reason is the whole design of this class. Auth Tab requires
 * **Chrome 137+**. On any other browser the same Intent is read as a plain Custom Tab — androidx puts
 * a null `EXTRA_SESSION` in it precisely so that it is — and the launcher then reports
 * `RESULT_CANCELED` **even when the login succeeded**, with the redirect arriving through the intent
 * filter instead. Believing the result code on its own would break sign-in for everyone not on
 * current Chrome.
 *
 * So neither side is authoritative alone:
 *
 * - **A redirect from either side wins outright**, immediately. It is the one answer that cannot be a
 *   misinterpretation, and there is nothing to gain by making a successful login wait.
 * - **Anything else waits out [CANCELLATION_GRACE] for the other side.** That covers the degraded browser in
 *   both orderings — a `RESULT_CANCELED` that is really a success, and a lifecycle heuristic that
 *   fires before the result code arrives.
 *
 * Two redirects can therefore be produced for one sign-in, and only one of them may be exchanged.
 * That is guaranteed twice over: this class returns exactly one [AuthRedirectResult], and
 * `MalSessionRepository.completeAuthorization` reads the Pending Authorization from the store and
 * clears it, so a second attempt at the same code finds nothing to complete it with.
 *
 * @param intentFilter the fallback capture, which also owns the `state` filtering, the plain-browser
 * fall-through and the no-browser-at-all case. Its own `launchBrowser` is only reached when the Auth
 * Tab could not be launched.
 * @param launchAuthTab shows [AuthRedirectChannel.open]'s URL in an Auth Tab. Throwing is a modelled
 * outcome, not a bug: the browser can be uninstalled between the capability check and the click.
 * @param results where the `ActivityResultLauncher` callback writes.
 */
internal class AuthTabRedirectChannel(
    private val intentFilter: IntentRedirectChannel,
    private val launchAuthTab: (String) -> Unit,
    private val results: AuthTabResultInbox,
) : AuthRedirectChannel {

    /**
     * Whether there is a result code coming at all. False after a failed launch, and the difference
     * matters: waiting a [CANCELLATION_GRACE] for an answer that can never arrive would delay every
     * fall-through by two seconds for nothing.
     */
    @Volatile
    private var authTabLaunched: Boolean = false

    /**
     * Delegated whole, because the question `arm` answers — does the manifest claim this Redirect URI
     * — is the intent filter's to answer, and the Auth Tab is launched watching for that same scheme.
     */
    override suspend fun arm(redirectUri: String): ArmResult {
        // Nothing is touched until the delegate has accepted, for the reason its own `arm` documents:
        // a decline is not a reason to tear down a capture that is still live and still being awaited.
        val armed = intentFilter.arm(redirectUri)
        if (armed != ArmResult.Armed) return armed

        authTabLaunched = false
        // Before anything is launched, so a result code the user produced by closing a previous Auth
        // Tab cannot be read as this attempt's answer. See [AuthTabResultInbox.clear].
        results.clear()
        return armed
    }

    override fun open(authorizationUrl: String) {
        authTabLaunched = try {
            launchAuthTab(authorizationUrl)
            true
        } catch (_: Exception) {
            // `ActivityNotFoundException` if the browser went away since the capability check, or
            // `IllegalStateException` from a launcher whose composition is gone. Swallowed for the
            // same reason [IntentRedirectChannel.open] swallows its own: the user still has to reach
            // MyAnimeList, and the message that would escape embeds the authorization URL.
            false
        }

        if (authTabLaunched) {
            // The intent filter still has to know which redirect is ours — a degraded Custom Tab
            // answers there — but the user is already in the browser, so `expect` and not `open`.
            intentFilter.expect(authorizationUrl)
        } else {
            // The full fall-through, including its own catch for a device with no browser at all.
            intentFilter.open(authorizationUrl)
        }
    }

    override suspend fun await(): AuthRedirectResult {
        // No Auth Tab took the user, so there is no result code to race and no window to hold open:
        // this is exactly the intent-filter capture, lifecycle heuristic and all.
        if (!authTabLaunched) return intentFilter.await()

        return coroutineScope {
            val authTab = async { results.claim().asRedirectResult() }
            val filter = async { intentFilter.await() }
            val first = select {
                authTab.onAwait { Answer(it, other = filter) }
                filter.onAwait { Answer(it, other = authTab) }
            }
            val settled = arbitrate(first)
            // Releases whichever side is still waiting — including the intent filter's own await,
            // whose only teardown path this is.
            coroutineContext.cancelChildren()
            settled
        }
    }

    /**
     * Decides what the two sides add up to.
     *
     * The asymmetry is the point: a redirect settles it on the spot, and everything else is only
     * believed once the other side has had [CANCELLATION_GRACE] to contradict it. Both directions need that
     * window, because both orderings happen — `RESULT_CANCELED` before the intent filter has seen the
     * redirect it is about to get, and the lifecycle heuristic firing before the result code lands.
     */
    private suspend fun arbitrate(first: Answer): AuthRedirectResult {
        if (first.result is AuthRedirectResult.Received) return first.result

        val other = withTimeoutOrNull(CANCELLATION_GRACE) { first.other.await() }
        if (other is AuthRedirectResult.Received) return other
        // Neither side saw a redirect. Two answers that agree cost nothing — the window closes as
        // soon as the second one lands — and where they disagree the more specific one wins, since a
        // `Failed` carries a reason and a heuristic `Cancelled` carries a guess.
        return if (other == null || first.result.specificity <= other.specificity) {
            first.result
        } else {
            other
        }
    }

    /** Which side answered first, and the one still outstanding. */
    private class Answer(
        val result: AuthRedirectResult,
        val other: Deferred<AuthRedirectResult>,
    )

    /**
     * The result codes, in this app's vocabulary.
     *
     * `RESULT_CANCELED` becomes [AuthRedirectResult.Cancelled] here and is *not* trusted by itself —
     * see [arbitrate]. The two verification codes cannot happen under a private-use scheme, since
     * Digital Asset Links only enters into it for an `https` redirect, but they are named separately
     * anyway: an impossible outcome that arrives with no explanation is worse than a long message.
     */
    private fun AuthTabResult.asRedirectResult(): AuthRedirectResult = when (resultCode) {
        AuthTabIntent.RESULT_OK -> redirect?.let(AuthRedirectResult::Received)
            ?: AuthRedirectResult.Failed(
                "The browser reported a successful sign-in but sent no redirect back. Paste the " +
                    "URL it ended on instead.",
            )

        AuthTabIntent.RESULT_CANCELED -> AuthRedirectResult.Cancelled

        AuthTabIntent.RESULT_VERIFICATION_FAILED -> AuthRedirectResult.Failed(
            "The browser could not verify that this app owns the redirect address, so it refused " +
                "to hand the sign-in back. Paste the URL it ended on instead.",
        )

        AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT -> AuthRedirectResult.Failed(
            "The browser timed out while verifying that this app owns the redirect address. Paste " +
                "the URL it ended on instead.",
        )

        // RESULT_UNKNOWN_CODE, which is also what androidx normalises anything it does not recognise
        // to. Reported rather than swallowed: as a cancellation it would look like the user backing
        // out, and as a hang it would look like nothing at all.
        else -> AuthRedirectResult.Failed(
            "The browser ended the sign-in with an outcome this app does not recognise " +
                "($resultCode). Paste the URL it ended on instead.",
        )
    }

    companion object {
        /**
         * How long a non-redirect answer is held before it is believed.
         *
         * Long enough to cover a hand-off between two independently scheduled coroutines, which is
         * all it is really waiting for — the redirect and the result code both arrive within a frame
         * or two of the user returning. It is invisible when it is spent, too: the full window is
         * only ever paid when the other side has not settled, and the intent filter does not settle
         * until this Activity is back on screen — so nobody is looking at a stalled screen while it
         * runs down.
         */
        val CANCELLATION_GRACE: Duration = 2.seconds
    }
}

/**
 * How much a non-redirect answer actually tells us. Lower is more specific.
 *
 * A [AuthRedirectResult.Failed] names a cause; a [AuthRedirectResult.Cancelled] from the lifecycle
 * heuristic is a guess that a call or a configuration change would produce identically; and
 * [AuthRedirectResult.Unsupported] says only that a side has nothing to offer.
 */
private val AuthRedirectResult.specificity: Int
    get() = when (this) {
        is AuthRedirectResult.Received -> 0
        is AuthRedirectResult.Failed -> 1
        AuthRedirectResult.Cancelled -> 2
        AuthRedirectResult.Unsupported -> 3
    }
