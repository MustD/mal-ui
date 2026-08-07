package io.challenge_workshop.mal_ui.mal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A successful response from MAL's token endpoint.
 *
 * Per MAL's docs the access token expires in one hour and the refresh token in one month,
 * even though [expiresIn] is reported as a much larger value. Treat [expiresIn] as advisory
 * and be ready to refresh on a 401.
 */
@Serializable
data class MalTokens(
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
) {
    /**
     * Redacted, so no log line, exception message or `SessionState` dump can leak a credential
     * by interpolating a token pair. The generated `equals`/`hashCode` are untouched, so tests
     * still compare token values exactly.
     */
    override fun toString(): String = "MalTokens(tokenType=$tokenType, expiresIn=$expiresIn, tokens=REDACTED)"
}

/** Subset of `GET /users/@me`. MAL omits fields it has no value for, so all but id/name are optional. */
@Serializable
data class MalUser(
    val id: Long,
    val name: String,
    val gender: String? = null,
    val location: String? = null,
    val picture: String? = null,
    val birthday: String? = null,
    @SerialName("joined_at") val joinedAt: String? = null,
)

/** Error envelope returned by both the OAuth and the v2 API endpoints. */
@Serializable
internal data class MalErrorBody(
    val error: String? = null,
    val message: String? = null,
    val hint: String? = null,
)

/** Any failure talking to MAL: transport, non-2xx response, or a malformed callback. */
class MalAuthException(
    message: String,
    /** HTTP status, when the failure came from a response rather than the transport. */
    val status: Int? = null,
    /** Machine-readable `error` code from MAL, e.g. `invalid_client`, `unsupported_grant_type`. */
    val errorCode: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
