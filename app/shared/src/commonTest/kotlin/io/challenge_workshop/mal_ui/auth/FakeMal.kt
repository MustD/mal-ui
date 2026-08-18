package io.challenge_workshop.mal_ui.auth

import io.challenge_workshop.mal_ui.mal.HttpClientFactory
import io.challenge_workshop.mal_ui.mal.MalUser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * A MyAnimeList that answers the token exchange and `/users/@me`, and nothing else.
 *
 * Shared by every test that drives a whole sign-in, in `commonTest` so `webTest` gets it too. The
 * Redirect URI is deliberately *not* here: it is the one value that legitimately differs per
 * target, and a shared default would hide a test asserting against the wrong one.
 */
internal const val FAKE_MAL_TOKEN_ENDPOINT: String = "https://mal.test/v1/oauth2/token"

internal const val FAKE_MAL_API_BASE_URL: String = "https://mal.test/v2"

internal val FAKE_MAL_USER: MalUser = MalUser(id = 42, name = "someone")

internal fun fakeMal(): HttpClientFactory {
    val engine = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            request.url.toString().startsWith(FAKE_MAL_TOKEN_ENDPOINT) -> respond(
                content = """{"token_type":"Bearer","expires_in":2415600,""" +
                    """"access_token":"an-access-token","refresh_token":"a-refresh-token"}""",
                status = HttpStatusCode.OK,
                headers = json,
            )

            request.url.encodedPath.endsWith("/users/@me") -> respond(
                content = """{"id":${FAKE_MAL_USER.id},"name":"${FAKE_MAL_USER.name}"}""",
                status = HttpStatusCode.OK,
                headers = json,
            )

            else -> respondError(HttpStatusCode.NotFound, "unexpected ${request.url}")
        }
    }
    return HttpClientFactory { configure -> HttpClient(engine) { configure() } }
}
