package io.challenge_workshop.mal_ui.animelist

import io.challenge_workshop.mal_ui.mal.MalAuthException
import io.challenge_workshop.mal_ui.mal.decodeOrThrow
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /users/@me/animelist`, and nothing else. HTTP and parsing only — every decision about *when*
 * to ask belongs to [AnimeListPager] above it. Mirrors `MalAuthClient`.
 *
 * [http] is the caller's **authenticated** client: this class installs no `Authorization` header of
 * its own, because in production the `Auth` plugin on `MalSessionRepository`'s client owns that and
 * the refresh that follows a 401. Two clients over one token store is the refresh race that
 * repository's own doc comment warns about, so this one is handed a client rather than building one.
 */
class MalAnimeListClient(
    private val apiBaseUrl: String,
    private val http: HttpClient,
) {
    /**
     * One page, at [offset], of the signed-in user's own Anime List.
     *
     * @param watchStatus the single Watch Status to filter to, or null for the whole list. MAL takes
     * **one** value or none — there is no multi-select — and [WatchStatus.Unknown] has no wire value,
     * so it filters nothing.
     */
    suspend fun page(
        offset: Int,
        limit: Int = DEFAULT_PAGE_SIZE,
        watchStatus: WatchStatus? = null,
        sortOrder: AnimeListSortOrder = AnimeListSortOrder.LastUpdated,
    ): AnimeListPage {
        val response = try {
            http.get("${apiBaseUrl.trimEnd('/')}/users/@me/animelist") {
                parameter("limit", limit)
                parameter("offset", offset)
                // So the list matches myanimelist.net rather than silently omitting the user's own
                // entries. There is no setting for it: this is a client for your own list.
                parameter("nsfw", true)
                parameter("sort", sortOrder.wireValue)
                parameter("fields", ANIME_LIST_FIELDS)
                watchStatus?.wireValue?.let { parameter("status", it) }
            }
        } catch (e: MalAuthException) {
            throw e
        } catch (e: Exception) {
            throw MalAuthException("Could not reach the MAL API: ${e.message}", cause = e)
        }
        return response.decodeOrThrow<AnimeListPageBody>().toPage()
    }

    companion object {
        /**
         * One page size for both Layouts. A Layout toggle that changed it would re-fetch on what is
         * only a presentation change.
         */
        const val DEFAULT_PAGE_SIZE: Int = 50

        /**
         * Exactly what this screen draws, and nothing more.
         *
         * A wrong `fields` string is the failure mode worth knowing about: MAL still answers 200 and
         * simply omits what was not asked for, so the cost lands in the UI as missing data rather
         * than as an error. `AnimeListPagerTest.the_first_page_asks_for_the_agreed_query` asserts the
         * outgoing string for that reason, rather than trusting the parsed result.
         */
        const val ANIME_LIST_FIELDS: String =
            "id,title,main_picture,num_episodes,media_type,status," +
                "list_status{status,score,num_episodes_watched,updated_at}"
    }
}

/**
 * MAL's wire shape, kept out of the rest of the app.
 *
 * Every field is optional with a default, and both status enums fall back to `Unknown`, so a MAL-side
 * addition or a field this request did not ask for cannot throw. A list the user is looking at must
 * not become a crash because MAL shipped a sixth Watch Status.
 */
@Serializable
internal data class AnimeListPageBody(
    val data: List<AnimeListRowBody> = emptyList(),
    val paging: PagingBody = PagingBody(),
) {
    fun toPage(): AnimeListPage = AnimeListPage(
        entries = data.map { it.toEntry() },
        // The presence of the link, never the link itself — see [AnimeListPage.hasMore].
        hasMore = !paging.next.isNullOrBlank(),
    )
}

@Serializable
internal data class PagingBody(
    val next: String? = null,
    val previous: String? = null,
)

@Serializable
internal data class AnimeListRowBody(
    val node: AnimeNodeBody = AnimeNodeBody(),
    @SerialName("list_status") val listStatus: ListStatusBody = ListStatusBody(),
) {
    fun toEntry(): AnimeListEntry = AnimeListEntry(
        animeId = node.id,
        title = node.title,
        picture = node.mainPicture,
        totalEpisodes = node.numEpisodes,
        mediaType = node.mediaType,
        airingStatus = node.status,
        watchStatus = listStatus.status,
        score = listStatus.score,
        episodesWatched = listStatus.numEpisodesWatched,
        updatedAt = listStatus.updatedAt,
    )
}

@Serializable
internal data class AnimeNodeBody(
    val id: Long = 0,
    val title: String = "",
    @SerialName("main_picture") val mainPicture: AnimePicture? = null,
    @SerialName("num_episodes") val numEpisodes: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    /** MAL's `status` on the anime is the **Airing** Status. The one in `list_status` is not. */
    val status: AiringStatus = AiringStatus.Unknown,
)

@Serializable
internal data class ListStatusBody(
    /** MAL's `status` inside `list_status` is the **Watch** Status. The one on the node is not. */
    val status: WatchStatus = WatchStatus.Unknown,
    val score: Int = 0,
    @SerialName("num_episodes_watched") val numEpisodesWatched: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)
