package io.challenge_workshop.mal_ui.mal

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/**
 * How every MAL response is turned into either a value or a [MalAuthException].
 *
 * Shared by the OAuth client and the API clients rather than written twice: MAL uses **one** error
 * envelope for both, and the hints below are the accumulated cost of debugging it. A second copy
 * would drift, and the half that drifted would be the one a user sees.
 */
private val errorJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Decodes a 2xx body, or throws whatever the non-2xx one describes. */
internal suspend inline fun <reified T> HttpResponse.decodeOrThrow(): T {
    if (!status.isSuccess()) throw toMalException()
    return try {
        body()
    } catch (e: Exception) {
        throw MalAuthException(
            "MAL returned a ${status.value} but the body did not parse: ${e.message}",
            status = status.value,
            cause = e,
        )
    }
}

internal suspend fun HttpResponse.toMalException(): MalAuthException {
    val raw = runCatching { bodyAsText() }.getOrDefault("")
    val parsed = runCatching { errorJson.decodeFromString<MalErrorBody>(raw) }.getOrNull()
    val detail = listOfNotNull(parsed?.message, parsed?.hint)
        .distinct()
        .joinToString(" — ")
        .ifBlank { raw.take(300).ifBlank { "no response body" } }
    return MalAuthException(
        message = buildString {
            append("MAL rejected the request (HTTP ${status.value}")
            parsed?.error?.let { append(", $it") }
            append("): ")
            append(detail)
            append(hintFor(parsed?.error))
        },
        status = status.value,
        errorCode = parsed?.error,
    )
}

/** MAL's error codes are terse; these are the ones that actually bite during setup. */
internal fun hintFor(errorCode: String?): String = when (errorCode) {
    // MAL reports a *Redirect URI* mismatch as 401 invalid_client / "Client authentication
    // failed", which implicates the Client ID and costs an hour of debugging. Naming both
    // possibilities here is the whole point of the hint.
    "invalid_client" ->
        "\n\nTwo possible causes. Either the Client ID is wrong — and if the app was registered " +
            "with App Type `web`, MAL issued a Client Secret and requires it here too. Or the " +
            "redirect_uri does not byte-exactly match one registered on the app: MAL reports " +
            "a Redirect URI mismatch as this same 401 invalid_client, which points at the " +
            "Client ID and not at the URI. A trailing slash, a changed port or a case " +
            "difference is enough."

    "invalid_request" ->
        "\n\nUsually a redirect_uri mismatch: it must match a URL registered on the app exactly, " +
            "and be sent to both the authorize and token endpoints or neither."

    "invalid_grant" ->
        "\n\nThe code was already used, expired, or the code_verifier does not match. " +
            "Authorization codes are single-use — start the login again."

    else -> ""
}
