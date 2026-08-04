package io.challenge_workshop.mal_ui.mal

/**
 * Endpoints and client credentials for the MyAnimeList API.
 *
 * MAL supports only the OAuth2 *authorization code* grant with PKCE — there is no
 * password/resource-owner grant, so the user's MAL password is never seen by this app.
 * See https://myanimelist.net/apiconfig/references/authorization
 *
 * Register an app at https://myanimelist.net/apiconfig to obtain a [clientId]. Apps
 * registered with App Type `other`/`android`/`ios` are *public* clients and get no
 * secret; leave [clientSecret] null for those. Only App Type `web` issues a secret,
 * and embedding one in a client binary does not keep it secret.
 */
data class MalAuthConfig(
    val clientId: String,
    val clientSecret: String? = null,
    /**
     * Must exactly match one of the redirect URLs registered with the app, and must be
     * sent to the token endpoint if it was sent to the authorize endpoint. When null,
     * MAL falls back to the single registered URL.
     */
    val redirectUri: String? = null,
    /** Always MAL itself: this is a browser navigation, so CORS never applies. */
    val authorizeEndpoint: String = DEFAULT_AUTHORIZE_ENDPOINT,
    /** MAL directly on native targets, the `:server` relay on web. See [platformMalEndpoints]. */
    val tokenEndpoint: String = platformMalEndpoints().tokenEndpoint,
    val apiBaseUrl: String = platformMalEndpoints().apiBaseUrl,
) {
    companion object {
        const val DEFAULT_AUTHORIZE_ENDPOINT: String = "https://myanimelist.net/v1/oauth2/authorize"
        const val DEFAULT_TOKEN_ENDPOINT: String = "https://myanimelist.net/v1/oauth2/token"
        const val DEFAULT_API_BASE_URL: String = "https://api.myanimelist.net/v2"

        /**
         * Registering this as the app's redirect URL lets the paste-the-code flow work on
         * every platform: the browser fails to load it, but the address bar still shows
         * `?code=...`, and a loopback listener can later claim the same URL unchanged.
         */
        const val LOOPBACK_REDIRECT_URI: String = "http://localhost:8080/oauth/callback"
    }
}
