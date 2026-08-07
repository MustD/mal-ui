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

/**
 * The suffix every registered Redirect URI ends in, on every target.
 *
 * It is a *path* on the two `http` targets and — because a private-use scheme has no host to speak
 * of — an authority plus a path on Android. What all three share is the trailing byte string, which
 * is the only thing MAL compares.
 *
 * Shared so the desktop listener's route and the web callback route cannot drift from each other, or
 * from what is registered on the MAL app.
 */
const val OAUTH_CALLBACK_PATH: String = "/oauth/callback"

/**
 * The port the desktop loopback listener binds, and the only port the desktop Redirect URI may name.
 *
 * Fixed rather than ephemeral: MAL does **no** RFC 8252 §7.3 port-lenient matching for loopback
 * redirects, so the port is part of the byte-exact string that has to be registered in advance.
 * Inside this project's 18010–18090 block, unlike the 8080 it replaced.
 */
const val DESKTOP_LOOPBACK_PORT: Int = 18040

/**
 * Desktop's Redirect URI.
 *
 * The bare IP literal, which RFC 8252 §8.3 prefers and which MAL accepts (verified). It also spares
 * the listener the dual-bind problem `localhost` would bring: that name resolves to both `127.0.0.1`
 * and `::1` here, and a browser is free to pick either.
 */
const val DESKTOP_REDIRECT_URI: String = "http://127.0.0.1:$DESKTOP_LOOPBACK_PORT$OAUTH_CALLBACK_PATH"

/**
 * Android's Redirect URI.
 *
 * A private-use URI scheme, reverse-DNS named after a domain this app controls as RFC 8252 §7.1
 * requires. Written out in full rather than composed from [OAUTH_CALLBACK_PATH], because the pieces
 * do not line up: under a custom scheme `oauth` is the *authority* and `/callback` the path, so the
 * manifest's intent filter is `scheme="io.challenge-workshop.malui" host="oauth" path="/callback"`
 * even though MAL only ever sees the one string.
 *
 * Android compares the scheme case-insensitively; MAL compares the whole URI byte-exactly, so this
 * is the stricter of the two constraints.
 */
const val ANDROID_REDIRECT_URI: String = "io.challenge-workshop.malui://oauth/callback"

/**
 * The browser Redirect URI for a page served from [origin], such as `https://mal-ui.localhost` or
 * `http://localhost:18020`.
 *
 * Derived from the live origin for the same reason [relayEndpointsFor] is: one build has to work
 * behind the reverse proxy and on either direct dev-server port. All four of those origins are
 * registered on the MAL app, because there is no wildcard and no normalization to lean on.
 */
fun redirectUriFor(origin: String): String = origin.trimEnd('/') + OAUTH_CALLBACK_PATH

/**
 * Where MAL should send the user back to after they approve, on this target.
 *
 * Pure data with no I/O, which is what keeps an `expect` in `:core` harmless for `:server`. Kept a
 * function of its own rather than a field on [MalEndpoints] because it is not an endpoint this app
 * calls — it is an address MAL calls back.
 *
 * Every value it can return is registered on the MAL app. MAL matches the string it receives
 * byte-exactly against that list — a trailing slash, a changed port, an upper-cased host or an extra
 * query parameter are each enough to fail — and it reports the mismatch as HTTP 401 `invalid_client`,
 * which names the Client ID and not the URI.
 */
expect fun platformRedirectUri(): String
