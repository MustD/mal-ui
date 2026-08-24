package io.challenge_workshop.mal_ui.animelist

import io.ktor.http.HttpStatusCode

/**
 * How the fake MAL answers one `/users/@me/animelist` request.
 *
 * Built as raw JSON rather than by serializing the production DTOs: those DTOs are half of what is
 * under test, so a fixture that round-tripped through them could not catch a wrong `@SerialName`.
 */
sealed interface AnimeListResponse {
    /** 200 with [entries], and a `paging.next` iff [hasMore]. */
    data class Page(val entries: List<FakeEntry>, val hasMore: Boolean) : AnimeListResponse {
        fun json(): String = buildString {
            append("""{"data":[""")
            append(entries.joinToString(",") { it.json() })
            append("]")
            // The absolute `api.myanimelist.net` URL MAL really sends, so a pager that tried to
            // follow it would be visibly reaching off the Relay's origin.
            if (hasMore) append(""","paging":{"next":"https://api.myanimelist.net/v2/users/@me/animelist?offset=50"}""")
            else append(""","paging":{}""")
            append("}")
        }
    }

    /** MAL answered, and said no. */
    data class Failure(val status: HttpStatusCode = HttpStatusCode.ServiceUnavailable) : AnimeListResponse

    /** The request never got there — offline, DNS, TLS. */
    data object TransportFailure : AnimeListResponse
}

/**
 * One row of MAL's wire shape.
 *
 * The two statuses are strings and not the production enums, so a test can serve a value MAL has
 * not invented yet — which is the whole of the "unknown values parse rather than throw" case.
 */
data class FakeEntry(
    val id: Long,
    val title: String,
    val numEpisodes: Int = 26,
    val mediaType: String = "tv",
    val airingStatus: String = "finished_airing",
    val watchStatus: String = "watching",
    val score: Int = 8,
    val watched: Int = 3,
    val updatedAt: String = "2026-08-01T12:00:00+00:00",
) {
    fun json(): String =
        """{"node":{"id":$id,"title":"$title",""" +
            """"main_picture":{"medium":"https://cdn.myanimelist.net/images/anime/4/$id.jpg",""" +
            """"large":"https://cdn.myanimelist.net/images/anime/4/${id}l.jpg"},""" +
            """"num_episodes":$numEpisodes,"media_type":"$mediaType","status":"$airingStatus"},""" +
            """"list_status":{"status":"$watchStatus","score":$score,""" +
            """"num_episodes_watched":$watched,"updated_at":"$updatedAt"}}"""
}

/** A page of [count] entries, numbered from [firstId], so a paging test can name what it expects. */
fun fakeEntries(count: Int, firstId: Long = 1): List<FakeEntry> =
    (0 until count).map { FakeEntry(id = firstId + it, title = "Anime ${firstId + it}") }
