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

    @Test
    fun desktopRedirectsToTheFixedLoopbackPort() {
        // The bare IP literal rather than `localhost`, per RFC 8252 §8.3 and because an IP literal
        // cannot resolve to both address families the way `localhost` does — so the loopback
        // listener binds one socket and not two.
        assertEquals("http://127.0.0.1:18040/oauth/callback", platformRedirectUri())
    }
}
