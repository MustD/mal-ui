package io.challenge_workshop.mal_ui.mal

/** Android is not subject to CORS, so it calls MAL directly. */
actual fun platformMalEndpoints(): MalEndpoints = MalEndpoints(
    tokenEndpoint = MalAuthConfig.DEFAULT_TOKEN_ENDPOINT,
    apiBaseUrl = MalAuthConfig.DEFAULT_API_BASE_URL,
)
