package io.challenge_workshop.mal_ui

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MalRelayTest {

    @Test
    fun relayAnswersCorsPreflightSoBrowsersCanPost() = testApplication {
        application { module() }

        val response: HttpResponse = client.request("/mal/oauth2/token") {
            method = HttpMethod.Options
            header(HttpHeaders.Origin, "http://localhost:18020")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "content-type")
        }

        // This is exactly what MAL itself refuses to do (it answers 405), and why the relay exists.
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "http://localhost:18020",
            response.headers[HttpHeaders.AccessControlAllowOrigin],
        )
    }

    @Test
    fun relayAllowsTheProxiedHostnameOverHttps() = testApplication {
        application { module() }

        val response: HttpResponse = client.request("/mal/oauth2/token") {
            method = HttpMethod.Options
            header(HttpHeaders.Origin, "https://mal-ui.localhost")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }

        assertEquals(
            "https://mal-ui.localhost",
            response.headers[HttpHeaders.AccessControlAllowOrigin],
        )
    }

    @Test
    fun relayRejectsPlaintextOnTheProxiedHostname() = testApplication {
        application { module() }

        // The proxy sets HSTS, so http:// is never a legitimate origin for this host.
        val response: HttpResponse = client.request("/mal/oauth2/token") {
            method = HttpMethod.Options
            header(HttpHeaders.Origin, "http://mal-ui.localhost")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }

        assertTrue(response.headers[HttpHeaders.AccessControlAllowOrigin] == null)
    }

    @Test
    fun relayRejectsUnknownOrigin() = testApplication {
        application { module() }

        val response: HttpResponse = client.request("/mal/oauth2/token") {
            method = HttpMethod.Options
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }

        assertTrue(
            response.headers[HttpHeaders.AccessControlAllowOrigin] == null,
            "must not hand out a wildcard to arbitrary origins",
        )
    }

    @Test
    fun apiRelayRequiresAPath() = testApplication {
        application { module() }

        val response = client.get("/mal/v2/")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun rootRouteStillWorks() = testApplication {
        application { module() }

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertNotNull(response.bodyAsText().ifBlank { null })
    }
}
