package io.challenge_workshop.mal_ui.mal

/**
 * The page's own origin, such as `https://mal-ui.localhost` or `http://localhost:18020`.
 *
 * The one thing the two browser targets cannot share: `kotlinx.browser` is a JS-target dependency,
 * so Wasm reads the same property through an external declaration instead. Everything built on top
 * of it lives here, in `webMain`, and is compiled once for both.
 */
internal expect fun browserOrigin(): String

/**
 * Browsers must go through the same-origin `:server` relay — see [platformMalEndpoints].
 * Start it with `./gradlew :server:run` before attempting to log in on web.
 */
actual fun platformMalEndpoints(): MalEndpoints = relayEndpointsFor(browserOrigin())

/**
 * The callback route on whichever origin this build is being served from, so one build works behind
 * the reverse proxy and on either direct dev-server port. All four origins are registered on the MAL
 * app; an unregistered one fails as a 401 `invalid_client`.
 */
actual fun platformRedirectUri(): String = redirectUriFor(browserOrigin())
