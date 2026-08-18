package io.challenge_workshop.mal_ui.auth

import android.net.Uri
import androidx.lifecycle.Lifecycle
import io.challenge_workshop.mal_ui.mal.ANDROID_REDIRECT_URI
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicReference

/**
 * Android's Redirect Capture: the custom-scheme intent filter, arriving through
 * [AuthRedirectInbox].
 *
 * It works on every browser there is, which is why it is still here now that
 * [AuthTabRedirectChannel] sits in front of it: Auth Tab needs Chrome 137+ and degrades to a plain
 * Custom Tab *silently* on everything else, and this is where the redirect then lands.
 *
 * Nothing is reserved, so no [ArmResult.Failed] is possible: the intent filter is declared in the
 * manifest, it is always live, and `AndroidManifestTest` is what holds it to
 * [ANDROID_REDIRECT_URI].
 *
 * One instance serves many sign-ins — the composable remembers it for the life of the composition —
 * so everything belonging to a single attempt lives in [Attempt] and [arm] starts a fresh one.
 *
 * @param lifecycleStates the hosting Activity's. The only signal there is that the user backed
 * out, unless an Auth Tab is in play and can say so outright; see [awaitReturnToForeground].
 * @param launchBrowser opens a URL. A plain Custom Tab where one is available, and Compose's
 * `LocalUriHandler` — a bare `ACTION_VIEW` — where none is; see [BrowserPlan]. Never called at all
 * when an Auth Tab took the user instead, which is what [expect] is for.
 */
internal class IntentRedirectChannel(
    private val inbox: AuthRedirectInbox,
    private val lifecycleStates: Flow<Lifecycle.State>,
    private val launchBrowser: (String) -> Unit,
) : AuthRedirectChannel {

    private val current = AtomicReference<Attempt?>(null)

    /**
     * Declines for a Redirect URI this app is not the one registered for, and otherwise starts an
     * attempt.
     *
     * The manifest claims exactly [ANDROID_REDIRECT_URI] and nothing else, so anything else would
     * be captured by nobody and the login would *hang* — the one failure worth converting into
     * Paste-the-code. A declining `arm` deliberately does not release a previous attempt: declining
     * is not a reason to tear down a capture that is still live and still being awaited.
     */
    override suspend fun arm(redirectUri: String): ArmResult {
        if (redirectUri != ANDROID_REDIRECT_URI) return ArmResult.Unsupported
        current.set(Attempt())
        return ArmResult.Armed
    }

    /**
     * Records which redirect this attempt will accept, without sending anyone anywhere.
     *
     * Split out of [open] for [AuthTabRedirectChannel], which has already put the user in a browser
     * and needs this side watching anyway: an Auth Tab degrades silently to a plain Custom Tab, and
     * the redirect then arrives here instead of through a result code. Launching a second browser
     * would be the alternative, and there is no version of that which is not a bug.
     */
    fun expect(authorizationUrl: String) {
        val attempt = current.get() ?: return
        attempt.expectedState = stateIn(authorizationUrl)
    }

    /**
     * Records the `state` to expect, then sends the user to the browser.
     *
     * Synchronous, unlike the desktop listener's: there is no `Desktop.browse()` hang to keep off
     * the caller's thread, and `startActivity` from the main thread is the ordinary way to do this.
     */
    override fun open(authorizationUrl: String) {
        val attempt = current.get() ?: return
        expect(authorizationUrl)
        try {
            // Nothing here logs the URL: under `plain` PKCE the code verifier travels inside it.
            launchBrowser(authorizationUrl)
        } catch (_: Exception) {
            // A device with no browser at all — rare, but real on kiosk and stripped builds. The
            // throw must not escape: it would end the sign-in coroutine with Compose's own message,
            // which is "Can't open <the whole authorization URL>" and therefore the code verifier.
            attempt.browserNeverOpened = true
        }
    }

    /**
     * Races the redirect against the user coming back without one.
     *
     * A race and not a sequence, because either can be the thing that never happens: a browser that
     * somehow never covers this Activity would leave the foreground signal silent forever, and a
     * user who backs out sends no redirect. Whichever arrives first settles the attempt.
     */
    override suspend fun await(): AuthRedirectResult {
        // A caller that ignored a declined `arm` gets an answer rather than parking forever.
        val attempt = current.get() ?: return AuthRedirectResult.Unsupported
        // Nothing will arrive, so say so at once rather than parking on it. `Unsupported` and not
        // `Failed`, which is the mapping ticket 16 settles on for "no browser could be opened": it
        // lands on the `Authorizing` screen, where the authorization URL is copyable and the paste
        // field is waiting — the same place a desktop timeout lands, and with no error card.
        if (attempt.browserNeverOpened) return AuthRedirectResult.Unsupported

        return coroutineScope {
            val redirect = async { inbox.claim(attempt::isOurs) }
            val backFromTheBrowser = async { awaitReturnToForeground() }
            val result = select {
                redirect.onAwait { AuthRedirectResult.Received(it) }
                backFromTheBrowser.onAwait { suspectedCancellation(attempt, redirect) }
            }
            // The loser of the race. Cancelling the collection is all there is to release — nothing
            // here holds a port or a window — but leaving it running would keep a spent attempt
            // collecting redirects meant for the next one.
            coroutineContext.cancelChildren()
            result
        }
    }

    /**
     * What a return to the foreground means, once a redirect is not what caused it.
     *
     * `onNewIntent` is documented to run before `onResume` and [AuthRedirectInbox.deliver] is
     * synchronous, so a redirect of ours has *arrived* by the time this runs. Where it is by then
     * depends on scheduling, and both places are checked because either coroutine can have run
     * first: the collector above may already have claimed it, or it may still be sitting in the
     * inbox. No grace window, and no race to lose — which matters because the race is between a
     * cancellation and the most ordinary success in the whole flow.
     */
    private suspend fun suspectedCancellation(
        attempt: Attempt,
        redirect: Deferred<String>,
    ): AuthRedirectResult =
        if (redirect.isCompleted || inbox.holds(attempt::isOurs)) {
            AuthRedirectResult.Received(redirect.await())
        } else {
            AuthRedirectResult.Cancelled
        }

    /**
     * Suspends until this Activity is resumed **after** having been off screen.
     *
     * The heuristic, and the whole reason [AuthTabRedirectChannel] exists to sit in front of this:
     * a result code says what happened, and this can only guess. The having-been-stopped requirement
     * is what makes the guess survivable: the first `onResume` after
     * launching a browser fires before the browser is on top, and acting on that alone would cancel
     * every sign-in at the moment it started.
     *
     * States and not events, because this subscribes from inside [await] — after [open] has already
     * launched the browser — and a `Lifecycle.Event` stream delivers nothing about what happened
     * before. A `StateFlow` hands a late subscriber the state the Activity is *in*, so a stop that
     * beat the subscription is still seen. The conflation that comes with it only ever loses a
     * round trip too fast to be a user backing out, which is the direction to be wrong in.
     *
     * The remaining false positives are real — a call, a notification, a biometric prompt or a
     * configuration change can each resume us mid-login — which is why the caller treats
     * [AuthRedirectResult.Cancelled] as "re-enable the button", never as "destroy the Pending
     * Authorization". See `MalSessionRepository.cancelAuthorization`.
     */
    private suspend fun awaitReturnToForeground() {
        var wasOffScreen = false
        lifecycleStates.first { state ->
            if (!state.isAtLeast(Lifecycle.State.STARTED)) wasOffScreen = true
            wasOffScreen && state == Lifecycle.State.RESUMED
        }
    }

    /**
     * One sign-in's worth of capture. The channel outlives many of these, so everything single-use
     * lives here rather than being reset in place.
     */
    private class Attempt {

        /**
         * The `state` this flow expects, read out of the authorization URL because that is the only
         * place a channel ever sees it. Null until [open] runs — and nothing arriving before then
         * can be ours, since nobody has been sent to MyAnimeList yet.
         */
        @Volatile
        var expectedState: String? = null

        /** No browser opened, so no redirect is coming. See [open]. */
        @Volatile
        var browserNeverOpened: Boolean = false

        fun isOurs(rawRedirect: String): Boolean {
            val expected = expectedState ?: return false
            return stateIn(rawRedirect) == expected
        }
    }
}

/**
 * The `state` parameter, decoded the same way on both sides — the authorization URL this app minted
 * and the redirect a browser handed back — so a value that needed escaping compares equal.
 */
private fun stateIn(url: String): String? =
    runCatching { Uri.parse(url).getQueryParameter("state") }.getOrNull()
