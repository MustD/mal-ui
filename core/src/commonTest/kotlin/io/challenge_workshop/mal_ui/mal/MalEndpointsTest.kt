package io.challenge_workshop.mal_ui.mal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun buildsTheWebRedirectUriBehindTheReverseProxy() {
        assertEquals("https://mal-ui.localhost/oauth/callback", redirectUriFor("https://mal-ui.localhost"))
    }

    @Test
    fun buildsTheWebRedirectUriOnADirectDevServerPort() {
        assertEquals("http://localhost:18020/oauth/callback", redirectUriFor("http://localhost:18020"))
    }

    @Test
    fun toleratesATrailingSlashOnTheOriginOfARedirectUri() {
        // A doubled slash would be a different byte string, and MAL matches byte-exactly.
        assertEquals(
            redirectUriFor("https://mal-ui.localhost"),
            redirectUriFor("https://mal-ui.localhost/"),
        )
    }

    @Test
    fun everyTargetsRedirectUriUsesTheRegisteredCallbackPath() {
        // Every URI registered on the MAL app ends in this; a target that invented its own suffix
        // would fail as a 401 invalid_client pointing at the Client ID.
        assertEquals("/oauth/callback", OAUTH_CALLBACK_PATH)
        assertTrue(platformRedirectUri().endsWith(OAUTH_CALLBACK_PATH), platformRedirectUri())
    }

    @Test
    fun theDesktopLoopbackPortIsInThisProjectsBlock() {
        // Fixed, not ephemeral: MAL does no RFC 8252 §7.3 port-lenient matching, so the port is part
        // of what has to be registered. 18040 is this project's slot; 8080 was neither.
        assertEquals(18040, DESKTOP_LOOPBACK_PORT)
        assertEquals("http://127.0.0.1:18040/oauth/callback", DESKTOP_REDIRECT_URI)
    }
}
