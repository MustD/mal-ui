package io.challenge_workshop.mal_ui.auth

/**
 * The browser's [StartupRedirect]: a `?code=…` or `?error=…` sitting in this document's own address
 * bar at launch.
 *
 * That is the full-page-redirect fallback landing — the popup was blocked, MyAnimeList sent the
 * whole tab to the callback route, and the document that started the sign-in no longer exists. The
 * code verifier comes back from `sessionStorage`, which is the reason the Pending Authorization is
 * persisted before the browser is ever opened.
 *
 * A sign-in *popup* never reaches this class: the entry point relays and closes it before Koin is
 * started — see [relaySignInRedirectToOpener], and the `sessionStorage`-is-copied trap it exists
 * for.
 */
internal class WebStartupRedirect : StartupRedirect {

    override suspend fun consume(): String? {
        if (!carriesAuthRedirect(currentSearch())) return null
        val redirect = currentHref()
        // Cleared here rather than after the exchange, so nothing that happens next can leave a
        // single-use code — and, under `plain` PKCE, the verifier beside it — where a reload would
        // pick it up. What survives a failed exchange is the Pending Authorization, which is what
        // retrying actually needs.
        clearAuthQuery()
        return redirect
    }
}
