package io.challenge_workshop.mal_ui.mal

/** Desktop is not subject to CORS, so it calls MAL directly. */
actual fun platformMalEndpoints(): MalEndpoints = MalEndpoints(
    tokenEndpoint = MalAuthConfig.DEFAULT_TOKEN_ENDPOINT,
    apiBaseUrl = MalAuthConfig.DEFAULT_API_BASE_URL,
)

/** A loopback listener on a fixed port — see [DESKTOP_REDIRECT_URI]. */
actual fun platformRedirectUri(): String = DESKTOP_REDIRECT_URI
