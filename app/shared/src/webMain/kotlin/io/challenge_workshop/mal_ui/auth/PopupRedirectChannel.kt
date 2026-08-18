@file:OptIn(ExperimentalWasmJsInterop::class)

package io.challenge_workshop.mal_ui.auth

import kotlinx.coroutines.CompletableDeferred
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Web's Redirect Capture: a popup that `postMessage`s the redirect back, falling back to a
 * full-page redirect and then to Paste-the-code.
 *
 * A popup rather than a navigation, because a full-page redirect tears down and re-initialises a
 * multi-MB Wasm bundle. It is viable because MAL sends no `Cross-Origin-Opener-Policy` on `/`,
 * `/login.php` or `/v1/oauth2/authorize`, so the browsing context group never switches and
 * `window.opener` survives the round trip. (MAL *does* send `X-Frame-Options: SAMEORIGIN`, so an
 * iframe was never an option, and nothing serving this app may set `COOP: same-origin` — use
 * `same-origin-allow-popups` if cross-origin isolation is ever needed.)
 *
 * @param origin the origin this document is served from, and therefore the only `event.origin` a
 * message of ours can carry. Injected so a test can arm a channel for an origin it is not being
 * served from.
 * @param openPopup `window.open`, returning null when the browser blocked it.
 * @param navigate the full-page-redirect fallback.
 */
internal class PopupRedirectChannel(
    private val origin: String = currentOrigin(),
    private val openPopup: (String) -> JsAny? = ::openSignInPopup,
    private val navigate: (String) -> Unit = ::navigateTo,
) : AuthRedirectChannel {

    private var current: Attempt? = null

    /**
     * Installs the message listener.
     *
     * Nothing is reserved and nothing can fail, so the only answer other than [ArmResult.Armed] is
     * for a Redirect URI this document could not possibly capture: `postMessage` is origin-scoped,
     * and the popup's final document has to be *this* app served from *this* origin for there to be
     * an opener to message at all.
     *
     * **This must not really suspend.** The stretch from the click to [open] runs synchronously only
     * while nothing in it reaches a suspension point, and that is what keeps the user activation
     * `window.open` consumes.
     */
    override suspend fun arm(redirectUri: String): ArmResult {
        // Checked before anything is torn down. Declining is not a reason to release an attempt
        // that is still live and still being awaited by an earlier `signIn`.
        if (!redirectUri.startsWith("$origin/")) return ArmResult.Unsupported
        // The bind-equivalent, for the case that *is* replacing a previous attempt: whatever it
        // left listening goes now, so a stale message cannot answer this sign-in.
        release()
        current = Attempt(addRedirectMessageListener(::onMessage))
        return ArmResult.Armed
    }

    /**
     * Opens the popup, synchronously, inside the click's activation window — and falls back to a
     * full-page redirect when the browser refuses.
     *
     * A null handle is the only signal a blocked popup gives, and it is also what a *lost* user
     * activation looks like from here, which is what makes the fallback load-bearing rather than
     * defensive: the stretch from the click to this call runs synchronously only while nothing in
     * it reaches a suspension point, and this is what happens if that ever stops being true.
     *
     * Nothing here logs the URL: under `plain` PKCE the code verifier travels inside it.
     */
    override fun open(authorizationUrl: String) {
        val attempt = current ?: return
        val popup = openPopup(authorizationUrl)
        if (popup != null && !isWindowClosed(popup)) {
            attempt.popup = popup
            return
        }
        // Settled before navigating, so a caller is never left parked on a capture that this
        // document will not be around to make. The flow continues after the reload, off the
        // Pending Authorization that `beginAuthorization` has already persisted.
        attempt.captured.complete(AuthRedirectResult.Unsupported)
        navigate(authorizationUrl)
    }

    override suspend fun await(): AuthRedirectResult {
        // A caller that ignored a declined `arm` gets an answer rather than parking forever.
        val attempt = current ?: return AuthRedirectResult.Unsupported
        try {
            return attempt.captured.await()
        } finally {
            release()
        }
    }

    /**
     * The credential check.
     *
     * Both halves, every time: `event.origin` proves the message came from a document this app
     * serves, and `event.source` proves it came from the window we opened rather than from any
     * other frame on the page. Neither is sufficient alone, and an authorization code is a
     * credential.
     *
     * A message arriving before [open] has run is refused by the source check on its own —
     * [Attempt.popup] is still null, so nothing can match it, and nobody has been sent to
     * MyAnimeList yet.
     *
     * The redirect is passed on **verbatim**, unparsed: `state` verification and the token exchange
     * belong to the opener's repository, which is the only thing holding the real Pending
     * Authorization. See [PopupRedirectChannel] callers, and the `sessionStorage` trap in
     * [io.challenge_workshop.mal_ui.session.SessionStorageKeyValueStore].
     */
    private fun onMessage(rawRedirect: String, source: JsAny?, messageOrigin: String) {
        val attempt = current ?: return
        if (messageOrigin != origin) return
        if (!isSameJsObject(source, attempt.popup)) return
        attempt.captured.complete(AuthRedirectResult.Received(rawRedirect))
    }

    /** Cancelling [await] is a channel's only teardown path, so everything single-use goes here. */
    private fun release() {
        val attempt = current ?: return
        current = null
        removeRedirectMessageListener(attempt.listener)
        closeWindow(attempt.popup)
    }

    /**
     * One sign-in's worth of capture. The channel outlives many of these — the composable remembers
     * it for the life of the composition — so everything single-use lives here rather than being
     * reset in place.
     */
    private class Attempt(val listener: JsAny) {
        val captured = CompletableDeferred<AuthRedirectResult>()

        /** Null until `open` runs, which is what makes an early message unmatchable. */
        var popup: JsAny? = null
    }
}
