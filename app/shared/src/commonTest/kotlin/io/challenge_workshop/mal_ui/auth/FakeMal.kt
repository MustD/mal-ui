package io.challenge_workshop.mal_ui.auth

import io.challenge_workshop.mal_ui.mal.HttpClientFactory
import io.challenge_workshop.mal_ui.mal.MalUser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
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

/**
 * The Anime List this fake serves, as two List Entries.
 *
 * Deliberately short. The signed-in screen renders every entry it is given, and a fixture with fifty
 * would push the debug panel below it off screen — which the tests that assert the panel is visible
 * would then report as the panel being lost.
 *
 * A second hand-written copy of `:core`'s `FakeAnimeList`, because a `commonTest` source set is not
 * published to another module. If MAL's shape changes, both move — `:core:allTests` is the one that
 * will say so first.
 */
internal val FAKE_MAL_ANIME_TITLES: List<String> = listOf("Cowboy Bebop", "Mushishi")

private val ANIME_LIST_JSON: String = FAKE_MAL_ANIME_TITLES.mapIndexed { index, title ->
    """{"node":{"id":${index + 1},"title":"$title","num_episodes":26,"media_type":"tv",""" +
        """"status":"finished_airing"},"list_status":{"status":"watching","score":8,""" +
        """"num_episodes_watched":${index + 3},"updated_at":"2026-08-01T12:00:00+00:00"}}"""
}.joinToString(",", prefix = """{"data":[""", postfix = """],"paging":{}}""")

/**
 * @param onRequest every request, before it is answered. For counting: "did that redirect produce a
 * second token exchange" is otherwise only inferable from a downstream error.
 */
internal fun fakeMal(onRequest: (HttpRequestData) -> Unit = {}): HttpClientFactory {
    val engine = MockEngine { request ->
        onRequest(request)
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            request.url.toString().startsWith(FAKE_MAL_TOKEN_ENDPOINT) -> respond(
                content = """{"token_type":"Bearer","expires_in":2415600,""" +
                    """"access_token":"an-access-token","refresh_token":"a-refresh-token"}""",
                status = HttpStatusCode.OK,
                headers = json,
            )

            request.url.encodedPath.endsWith("/users/@me/animelist") -> respond(
                content = ANIME_LIST_JSON,
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
