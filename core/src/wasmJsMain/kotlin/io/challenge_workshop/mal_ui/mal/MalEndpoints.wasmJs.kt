package io.challenge_workshop.mal_ui.mal

/**
 * Read directly rather than via `kotlinx.browser`, which the Wasm stdlib does not carry —
 * this avoids adding a dependency just to read one string.
 */
private fun currentOrigin(): String = js("window.location.origin")

/**
 * Browsers must go through the same-origin `:server` relay — see [platformMalEndpoints].
 * Start it with `./gradlew :server:run` before attempting to log in on web.
 */
actual fun platformMalEndpoints(): MalEndpoints = relayEndpointsFor(currentOrigin())
