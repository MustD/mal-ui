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

/** One page of [titles], as MAL would send it for the `offset` and `limit` that were asked for. */
private fun animeListJson(titles: List<String>, offset: Int, limit: Int): String {
    val page = titles.drop(offset).take(limit)
    val rows = page.mapIndexed { index, title ->
        val id = offset + index + 1
        """{"node":{"id":$id,"title":"$title","num_episodes":26,"media_type":"tv",""" +
            """"status":"finished_airing"},"list_status":{"status":"watching","score":8,""" +
            """"num_episodes_watched":${(index % 3) + 3},"updated_at":"2026-08-01T12:00:00+00:00"}}"""
    }.joinToString(",")
    // The absolute `api.myanimelist.net` URL MAL really sends, and only while entries remain: its
    // presence is the whole of "there is more", and its absence is what exhausts the pager.
    val paging = if (offset + limit < titles.size) {
        """{"next":"https://api.myanimelist.net/v2/users/@me/animelist?offset=${offset + limit}"}"""
    } else {
        "{}"
    }
    return """{"data":[$rows],"paging":$paging}"""
}

/**
 * @param animeListTitles the whole Anime List this fake holds **for a given `status` parameter** —
 * null being All — paged off the request's own `offset` and `limit` rather than off a size fixed
 * here, so a test about paging is testing the offsets the app actually drives. Keyed on the status
 * because that is how a filter test tells the slices apart: identical titles under two filters
 * cannot show that the filter reached MAL at all. The default is short enough to fit one page,
 * which is what every test that is not about filtering or paging wants.
 * @param failAnimeListAt asked for each Anime List request's `offset`. Answering true makes MAL
 * fail that page — which is a different screen from a failed first page, and the only way to reach
 * the retry at the bottom of the list. Consulted per request rather than fixed, so a test can let
 * the retry succeed.
 * @param holdAnimeList suspends before an Anime List request is answered. Without it every request
 * completes before the next line of the test runs, and the states that exist only *while* a page is
 * in flight — the old entries still on screen, the filter row disabled — cannot be looked at.
 * @param onRequest every request, before it is answered. For counting: "did that redirect produce a
 * second token exchange" is otherwise only inferable from a downstream error. Last, so it stays
 * reachable as a trailing lambda.
 */
internal fun fakeMal(
    animeListTitles: (String?) -> List<String> = { FAKE_MAL_ANIME_TITLES },
    failAnimeListAt: (Int) -> Boolean = { false },
    holdAnimeList: suspend (HttpRequestData) -> Unit = {},
    onRequest: (HttpRequestData) -> Unit = {},
): HttpClientFactory {
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

            request.url.encodedPath.endsWith("/users/@me/animelist") -> {
                holdAnimeList(request)
                val offset = request.url.parameters["offset"]?.toInt() ?: 0
                val titles = animeListTitles(request.url.parameters["status"])
                if (failAnimeListAt(offset)) {
                    respondError(HttpStatusCode.ServiceUnavailable, "boom")
                } else {
                    respond(
                        content = animeListJson(
                            titles = titles,
                            offset = offset,
                            limit = request.url.parameters["limit"]?.toInt() ?: titles.size,
                        ),
                        status = HttpStatusCode.OK,
                        headers = json,
                    )
                }
            }

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
