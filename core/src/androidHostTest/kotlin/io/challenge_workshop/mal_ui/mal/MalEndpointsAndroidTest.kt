package io.challenge_workshop.mal_ui.mal

import kotlin.test.Test
import kotlin.test.assertEquals

class MalEndpointsAndroidTest {

    @Test
    fun androidCallsMalDirectlyRatherThanViaTheRelay() {
        // Only the browser targets are subject to CORS; routing Android through the relay would make
        // the phone depend on a dev machine's `:server`.
        val endpoints = platformMalEndpoints()
        assertEquals(MalAuthConfig.DEFAULT_TOKEN_ENDPOINT, endpoints.tokenEndpoint)
        assertEquals(MalAuthConfig.DEFAULT_API_BASE_URL, endpoints.apiBaseUrl)
    }

    @Test
    fun androidRedirectsToTheReverseDnsCustomScheme() {
        // RFC 8252 §7.1: a private-use scheme must be reverse-DNS named after a domain the app
        // controls. The manifest's intent filter has to claim exactly this, and so does the MAL
        // registration — nothing at runtime can reconcile a difference.
        assertEquals("io.challenge-workshop.malui://oauth/callback", platformRedirectUri())
    }
}
