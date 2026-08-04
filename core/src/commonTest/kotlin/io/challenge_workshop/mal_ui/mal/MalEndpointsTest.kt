package io.challenge_workshop.mal_ui.mal

import kotlin.test.Test
import kotlin.test.assertEquals

class MalEndpointsTest {

    @Test
    fun buildsRelayEndpointsBehindTheReverseProxy() {
        val endpoints = relayEndpointsFor("https://mal-ui.localhost")
        assertEquals("https://mal-ui.localhost/mal/oauth2/token", endpoints.tokenEndpoint)
        assertEquals("https://mal-ui.localhost/mal/v2", endpoints.apiBaseUrl)
    }

    @Test
    fun buildsRelayEndpointsOnADirectDevServerPort() {
        val endpoints = relayEndpointsFor("http://localhost:18020")
        assertEquals("http://localhost:18020/mal/oauth2/token", endpoints.tokenEndpoint)
        assertEquals("http://localhost:18020/mal/v2", endpoints.apiBaseUrl)
    }

    @Test
    fun toleratesATrailingSlashOnTheOrigin() {
        assertEquals(
            relayEndpointsFor("https://mal-ui.localhost").tokenEndpoint,
            relayEndpointsFor("https://mal-ui.localhost/").tokenEndpoint,
        )
    }

    @Test
    fun relayPathPrefixMatchesWhatTheProxiesRoute() {
        // Caddy's `handle /mal/*` and the dev server's proxy context must agree with this.
        assertEquals("/mal", MAL_RELAY_PATH_PREFIX)
    }
}
