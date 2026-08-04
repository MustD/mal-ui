package io.challenge_workshop.mal_ui.mal

import kotlinx.browser.window

/**
 * Browsers must go through the same-origin `:server` relay — see [platformMalEndpoints].
 * Start it with `./gradlew :server:run` before attempting to log in on web.
 */
actual fun platformMalEndpoints(): MalEndpoints = relayEndpointsFor(window.location.origin)
