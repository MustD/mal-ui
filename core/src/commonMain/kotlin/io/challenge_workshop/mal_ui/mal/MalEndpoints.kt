package io.challenge_workshop.mal_ui.mal

/** The two endpoints that a browser cannot call directly, and so may need redirecting to a relay. */
data class MalEndpoints(
    val tokenEndpoint: String,
    val apiBaseUrl: String,
)

/**
 * Path prefix under which `:server` exposes its MAL relay.
 *
 * Both routes that reach it use this prefix, so the browser always sees the relay on its
 * own origin:
 *  - Caddy routes this prefix on `mal-ui.localhost` to the Ktor server.
 *  - The webpack dev server proxies this prefix to the Ktor server for direct-port access.
 *
 * Changing it means changing both of those too.
 */
const val MAL_RELAY_PATH_PREFIX: String = "/mal"

/**
 * Relay endpoints for a browser [origin] such as `https://mal-ui.localhost` or
 * `http://localhost:18020`.
 *
 * Derived from the live origin rather than hardcoded, so the same build works behind the
 * reverse proxy and on a direct dev-server port without a rebuild.
 */
fun relayEndpointsFor(origin: String): MalEndpoints {
    val base = origin.trimEnd('/') + MAL_RELAY_PATH_PREFIX
    return MalEndpoints(
        tokenEndpoint = "$base/oauth2/token",
        apiBaseUrl = "$base/v2",
    )
}

/**
 * Where this platform should send token and API requests.
 *
 * Native targets talk to MAL directly. Browsers cannot: MAL serves no
 * `Access-Control-Allow-Origin` header on either endpoint and answers preflight `OPTIONS`
 * with 405, so every request fails as an opaque `TypeError: Failed to fetch` before it
 * leaves the page. The browser actuals therefore point at the same-origin `:server` relay.
 *
 * The authorize endpoint is exempt — that is a top-level browser navigation, not a fetch.
 */
expect fun platformMalEndpoints(): MalEndpoints
