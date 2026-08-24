package io.challenge_workshop.mal_ui.animelist

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What the user did with an anime.
 *
 * Named for the glossary rather than for MAL, which calls this `status` and calls [AiringStatus]
 * `status` too — the two live in different objects of the same response, which is exactly how they
 * get confused. See `CONTEXT.md`.
 *
 * [Unknown] is not a MAL value and is never sent: it is where a value MAL adds later lands, so a
 * single new status cannot crash a list the user is looking at.
 */
@Serializable(with = WatchStatusSerializer::class)
enum class WatchStatus(
    /** MAL's own spelling, or null for [Unknown], which has none and is never sent as a filter. */
    val wireValue: String?,
) {
    Watching("watching"),
    Completed("completed"),
    OnHold("on_hold"),
    Dropped("dropped"),
    PlanToWatch("plan_to_watch"),
    Unknown(null),
    ;

    companion object {
        fun fromWire(value: String?): WatchStatus = entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

/**
 * What the anime itself is doing — true for everyone, and unrelated to any user's list.
 *
 * [Unknown] exists for the same reason as [WatchStatus.Unknown].
 */
@Serializable(with = AiringStatusSerializer::class)
enum class AiringStatus(val wireValue: String?) {
    CurrentlyAiring("currently_airing"),
    FinishedAiring("finished_airing"),
    NotYetAired("not_yet_aired"),
    Unknown(null),
    ;

    companion object {
        fun fromWire(value: String?): AiringStatus = entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

/**
 * The orderings MAL offers, each with a **fixed direction and no direction parameter**.
 *
 * There is deliberately no reverse toggle: the Anime List is paged, so reversing what happens to be
 * loaded is a bug that looks like a feature.
 *
 * `anime_id` is a fifth value MAL's documentation marks "under development", and is not offered.
 *
 * Wire values only. The user-facing labels have to name the direction each one sorts in — "Score"
 * alone reads as ascending to about half of everyone — but that is display copy, and `:core` is the
 * tier `:server` also depends on. Ticket 05 puts them in `:app:shared` with the control.
 */
enum class AnimeListSortOrder(val wireValue: String) {
    LastUpdated("list_updated_at"),
    Score("list_score"),
    Title("anime_title"),
    StartDate("anime_start_date"),
}

/** The two cover-art sizes MAL serves for an anime. Both come from `cdn.myanimelist.net`. */
@Serializable
data class AnimePicture(
    val medium: String? = null,
    val large: String? = null,
)

/**
 * One anime together with *this user's* relationship to it — the unit the Anime List is made of.
 *
 * Flattened from MAL's two-object shape (`node` plus `list_status`) on purpose: nothing in this app
 * ever has one without the other, and keeping the split would mean every call site reaching through
 * a wrapper to answer "how far through is this".
 */
data class AnimeListEntry(
    val animeId: Long,
    val title: String,
    val picture: AnimePicture?,
    /** MAL reports `0` for an anime whose total is not yet known, not null. */
    val totalEpisodes: Int,
    /** MAL's `media_type`, verbatim: `tv`, `movie`, `ova`, … Left a string because MAL adds them. */
    val mediaType: String?,
    val airingStatus: AiringStatus,
    val watchStatus: WatchStatus,
    /** 0–10, where 0 means the user has not scored it. MAL does not distinguish those two. */
    val score: Int,
    val episodesWatched: Int,
    /** MAL's ISO-8601 stamp, verbatim. Nothing here parses it — the sort happens server-side. */
    val updatedAt: String?,
)

/**
 * One page of an Anime List.
 *
 * [hasMore] is the *presence* of `paging.next` and nothing else. The URL itself is deliberately
 * discarded: it is absolute and points at `api.myanimelist.net`, which the web target must never
 * request — it has to stay on the Relay's origin — so `offset` is driven from this side instead.
 */
data class AnimeListPage(
    val entries: List<AnimeListEntry>,
    val hasMore: Boolean,
)

/** Maps an enum to and from MAL's spelling, treating anything unrecognised as the fallback value. */
internal abstract class WireEnumSerializer<T>(
    serialName: String,
    private val toWire: (T) -> String?,
    private val fromWire: (String) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(toWire(value).orEmpty())
    override fun deserialize(decoder: Decoder): T = fromWire(decoder.decodeString())
}

internal object WatchStatusSerializer : WireEnumSerializer<WatchStatus>(
    serialName = "WatchStatus",
    toWire = { it.wireValue },
    fromWire = WatchStatus::fromWire,
)

internal object AiringStatusSerializer : WireEnumSerializer<AiringStatus>(
    serialName = "AiringStatus",
    toWire = { it.wireValue },
    fromWire = AiringStatus::fromWire,
)
