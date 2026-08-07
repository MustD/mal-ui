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
     * Where MAL sends the user back to. Must match one of the redirect URLs registered
     * with the app **byte-exactly**, and must be sent to the token endpoint as well as to
     * the authorize endpoint.
     *
     * Not nullable, and there is no "omit it" branch: with more than one URL registered on
     * the app, leaving `redirect_uri` out of the authorize request is rejected outright
     * (verified — the identical request passed while one URL was registered and failed once
     * several were). A missing value here could therefore only ever be a bug, and it would
     * report as a misleading HTTP 401 `invalid_client`.
     *
     * Defaults to this target's registered URI. See [platformRedirectUri].
     */
    val redirectUri: String = platformRedirectUri(),
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
    }
}
