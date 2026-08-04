package io.challenge_workshop.mal_ui.mal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN_JSON = """
    {"token_type":"Bearer","expires_in":2415600,"access_token":"at-123","refresh_token":"rt-456"}
"""

private const val USER_JSON = """
    {"id":42,"name":"testuser","location":"","joined_at":"2020-01-01T00:00:00+00:00"}
"""

class MalAuthClientTest {

    private val config = MalAuthConfig(
        clientId = "client-id",
        redirectUri = "http://localhost:8080/oauth/callback",
    )

    /** Captures outgoing requests so assertions can inspect what was actually sent. */
    private class Recorder {
        val requests = mutableListOf<HttpRequestData>()
        suspend fun formParams(index: Int = 0) =
            parseQueryString(requests[index].body.toByteArray().decodeToString())
    }

    private fun clientOf(
        recorder: Recorder,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = TOKEN_JSON,
    ): MalAuthClient {
        val engine = MockEngine { request ->
            recorder.requests += request
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return MalAuthClient(config, http, ownsHttpClient = true)
    }

    @Test
    fun authorizationUrlCarriesEveryParameterMalRequires() {
        val request = MalAuthClient(config, HttpClient(MockEngine { respond("") })).beginAuthorization()
        val params = Url(request.authorizationUrl).parameters

        assertEquals("code", params["response_type"])
        assertEquals("client-id", params["client_id"])
        assertEquals("plain", params["code_challenge_method"])
        assertEquals(request.codeVerifier, params["code_challenge"], "plain method: challenge == verifier")
        assertEquals(request.state, params["state"])
        assertEquals("http://localhost:8080/oauth/callback", params["redirect_uri"])
        assertTrue(request.authorizationUrl.startsWith(MalAuthConfig.DEFAULT_AUTHORIZE_ENDPOINT))
    }

    @Test
    fun authorizationUrlOmitsRedirectUriWhenNotConfigured() {
        val client = MalAuthClient(config.copy(redirectUri = null), HttpClient(MockEngine { respond("") }))
        assertNull(Url(client.beginAuthorization().authorizationUrl).parameters["redirect_uri"])
    }

    @Test
    fun beginAuthorizationRejectsBlankClientId() {
        val client = MalAuthClient(config.copy(clientId = "  "), HttpClient(MockEngine { respond("") }))
        assertFailsWith<IllegalArgumentException> { client.beginAuthorization() }
    }

    @Test
    fun exchangeCodeSendsAuthorizationCodeGrantWithVerifier() = runTest {
        val recorder = Recorder()
        val tokens = clientOf(recorder).exchangeCode(code = "the-code", codeVerifier = "the-verifier")

        assertEquals("Bearer", tokens.tokenType)
        assertEquals("at-123", tokens.accessToken)
        assertEquals("rt-456", tokens.refreshToken)

        val sent = recorder.formParams()
        assertEquals("authorization_code", sent["grant_type"])
        assertEquals("the-code", sent["code"])
        assertEquals("the-verifier", sent["code_verifier"])
        assertEquals("client-id", sent["client_id"])
        // redirect_uri went to the authorize endpoint, so it must be repeated here.
        assertEquals("http://localhost:8080/oauth/callback", sent["redirect_uri"])
    }

    @Test
    fun publicClientSendsNoClientSecret() = runTest {
        val recorder = Recorder()
        clientOf(recorder).exchangeCode("c", "v")
        assertNull(recorder.formParams()["client_secret"], "App Type `other` has no secret to send")
    }

    @Test
    fun confidentialClientSendsClientSecret() = runTest {
        val recorder = Recorder()
        val engine = MockEngine { request ->
            recorder.requests += request
            respond(TOKEN_JSON, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        MalAuthClient(config.copy(clientSecret = "shh"), http).exchangeCode("c", "v")

        assertEquals("shh", recorder.formParams()["client_secret"])
    }

    @Test
    fun refreshSendsRefreshTokenGrant() = runTest {
        val recorder = Recorder()
        clientOf(recorder).refresh("rt-old")

        val sent = recorder.formParams()
        assertEquals("refresh_token", sent["grant_type"])
        assertEquals("rt-old", sent["refresh_token"])
        assertNull(sent["code_verifier"], "refresh is not a PKCE exchange")
    }

    @Test
    fun completeAuthorizationRejectsMismatchedState() = runTest {
        val request = MalAuthRequest("https://example.test", codeVerifier = "v", state = "expected")
        val error = assertFailsWith<MalAuthException> {
            clientOf(Recorder()).completeAuthorization(request, "http://localhost/cb?code=c&state=forged")
        }
        assertTrue(error.message!!.contains("state"), error.message!!)
    }

    @Test
    fun completeAuthorizationAcceptsMatchingState() = runTest {
        val recorder = Recorder()
        val request = MalAuthRequest("https://example.test", codeVerifier = "v1", state = "s1")
        val tokens = clientOf(recorder)
            .completeAuthorization(request, "http://localhost/cb?code=c1&state=s1")

        assertEquals("at-123", tokens.accessToken)
        assertEquals("c1", recorder.formParams()["code"])
        assertEquals("v1", recorder.formParams()["code_verifier"])
    }

    @Test
    fun meSendsBearerTokenAndParsesUser() = runTest {
        val recorder = Recorder()
        val user = clientOf(recorder, body = USER_JSON).me("at-123")

        assertEquals(42, user.id)
        assertEquals("testuser", user.name)
        assertEquals("Bearer at-123", recorder.requests[0].headers[HttpHeaders.Authorization])
        assertTrue(recorder.requests[0].url.toString().endsWith("/users/@me"))
    }

    @Test
    fun errorResponseIsTranslatedWithCodeAndSetupHint() = runTest {
        val body = """{"error":"invalid_client","message":"Client authentication failed"}"""
        val error = assertFailsWith<MalAuthException> {
            clientOf(Recorder(), HttpStatusCode.Unauthorized, body).exchangeCode("c", "v")
        }

        assertEquals(401, error.status)
        assertEquals("invalid_client", error.errorCode)
        assertTrue(error.message!!.contains("Client authentication failed"), error.message!!)
        assertTrue(error.message!!.contains("Client Secret"), "should hint at the web-App-Type trap")
    }

    @Test
    fun nonJsonErrorBodyStillProducesAReadableFailure() = runTest {
        val error = assertFailsWith<MalAuthException> {
            clientOf(Recorder(), HttpStatusCode.BadGateway, "<html>nginx</html>").exchangeCode("c", "v")
        }
        assertEquals(502, error.status)
        assertNull(error.errorCode)
        assertTrue(error.message!!.contains("nginx"), error.message!!)
    }
}
