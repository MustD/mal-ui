package io.challenge_workshop.mal_ui.mal

import kotlin.test.Test
import kotlin.test.assertEquals

class MalEndpointsJvmTest {

    @Test
    fun desktopCallsMalDirectlyRatherThanViaTheRelay() {
        // Only the browser targets are subject to CORS; routing desktop through the relay
        // would make desktop login depend on `:server` for no reason.
        val endpoints = platformMalEndpoints()
        assertEquals(MalAuthConfig.DEFAULT_TOKEN_ENDPOINT, endpoints.tokenEndpoint)
        assertEquals(MalAuthConfig.DEFAULT_API_BASE_URL, endpoints.apiBaseUrl)
    }
}
