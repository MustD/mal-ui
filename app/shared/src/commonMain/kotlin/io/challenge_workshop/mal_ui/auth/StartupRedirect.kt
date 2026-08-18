package io.challenge_workshop.mal_ui.auth

/**
 * A redirect this process was *launched* with, rather than one an armed [AuthRedirectChannel]
 * captured while it was running.
 *
 * Only web has one. Its popup Redirect Capture falls back to a full-page redirect when the browser
 * blocks the popup, and that path destroys the document by design — the app that comes back is a
 * fresh process whose only evidence of a sign-in is a `?code=…` in its own address bar and the
 * Pending Authorization in `sessionStorage`. There is no armed channel left to deliver it to, so it
 * arrives here instead, at startup.
 *
 * Separate from [AuthRedirectChannel] because it is not a phase of a capture: nothing armed it,
 * nothing opened a browser for it, and it is answered once per process rather than once per
 * sign-in. Injected rather than probed for, so the three targets that can never have one are wired
 * to [None] and say so.
 */
fun interface StartupRedirect {

    /**
     * Takes the redirect this process was launched with, if there is one.
     *
     * **Consuming, not peeking**: the second call answers null. An authorization code is single-use
     * and, under `plain` PKCE, travels next to the verifier, so nothing may be left where a reload
     * would find it.
     *
     * @return the redirect exactly as the platform saw it — the same unparsed string a paste or a
     * capture produces, so all three go through one parser and fail identically.
     */
    suspend fun consume(): String?

    companion object {
        /** Desktop and Android: a redirect always arrives at a running process, never at a launch. */
        val None: StartupRedirect = StartupRedirect { null }
    }
}
