package io.challenge_workshop.mal_ui.mal

import io.ktor.http.URLBuilder
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

/**
 * The URL that sends the user to MyAnimeList to approve access.
 *
 * **The one construction site for this URL.** Two would drift, and MAL matches `redirect_uri`
 * byte-exactly. [MalAuthClient.authorizationFor] mints a verifier and calls this; the Screen State's
 * mapping rebuilds the URL for a Pending Authorization and calls this — which is possible at all only
 * because MAL supports `plain` PKCE, so the challenge *is* the verifier and there is nothing in the
 * URL that is not in the record.
 *
 * A plain function rather than a method, so rebuilding the URL needs neither an `HttpClient` nor a
 * suspension point: the Screen State's combine is pure, and a `(PendingAuthorization) -> String`
 * adapter passed into it would be a seam with one implementation.
 *
 * **Never log the result.** Under `plain` PKCE the code verifier travels inside it.
 */
fun authorizationUrl(config: MalAuthConfig, codeVerifier: String, state: String): String =
    URLBuilder(config.authorizeEndpoint).apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", config.clientId)
        parameters.append("code_challenge", Pkce.codeChallengeOf(codeVerifier))
        parameters.append("code_challenge_method", Pkce.CHALLENGE_METHOD)
        parameters.append("state", state)
        parameters.append("redirect_uri", config.redirectUri)
    }.buildString()

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
