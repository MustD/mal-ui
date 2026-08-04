package io.challenge_workshop.mal_ui.mal

import io.ktor.http.parseQueryString

/**
 * An authorization request in flight. [codeVerifier] and [state] must be held until the
 * user returns with a code — losing them means restarting the flow.
 */
data class MalAuthRequest(
    /** Send the user here; they authenticate on MAL, not in this app. */
    val authorizationUrl: String,
    val codeVerifier: String,
    val state: String,
)

/** The authorization code recovered from the redirect the user was sent to. */
data class MalAuthCode(
    val code: String,
    /** Null when the user pasted a bare code rather than the whole redirect URL. */
    val state: String?,
)

/**
 * Recovers the authorization code from whatever the user pasted back into the app.
 *
 * Accepts a full redirect URL, a bare query string, or the bare code on its own, since all
 * three are things people plausibly copy out of an address bar.
 *
 * @throws MalAuthException if MAL reported an error, or no code is present.
 */
fun parseRedirect(input: String): MalAuthCode {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) throw MalAuthException("Nothing pasted — copy the URL you were redirected to.")

    val queryStart = trimmed.indexOfFirst { it == '?' || it == '#' }
    if (queryStart < 0) {
        // No query delimiter: treat the whole thing as a bare code, unless it is plainly a URL.
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            throw MalAuthException(
                "That URL has no query string, so it carries no authorization code. " +
                        "Make sure you copied the address MAL redirected you to after approving access."
            )
        }
        if (trimmed.any { it.isWhitespace() }) {
            throw MalAuthException("That does not look like a redirect URL or an authorization code.")
        }
        return MalAuthCode(code = trimmed, state = null)
    }

    val params = parseQueryString(trimmed.substring(queryStart + 1))
    params["error"]?.let { error ->
        val detail = params["error_description"] ?: params["message"] ?: params["hint"]
        throw MalAuthException(
            message = "MAL denied the authorization request: $error" + (detail?.let { " — $it" } ?: ""),
            errorCode = error,
        )
    }
    val code = params["code"]
        ?: throw MalAuthException(
            "No `code` parameter in that URL. Approve access on the MAL page first, " +
                    "then copy the address you land on."
        )
    return MalAuthCode(code = code, state = params["state"])
}
