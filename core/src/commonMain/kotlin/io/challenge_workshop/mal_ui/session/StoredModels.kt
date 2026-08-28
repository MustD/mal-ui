package io.challenge_workshop.mal_ui.session

import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.mal.MalTokens
import io.challenge_workshop.mal_ui.mal.MalUser
import io.challenge_workshop.mal_ui.mal.authorizationUrl
import kotlinx.serialization.Serializable

/**
 * A persisted Session: the token pair plus the cached identity it belongs to.
 *
 * [user] is cached so a relaunch can render the app bar without a round trip — an eager
 * refresh on every launch costs a request, fails offline, and (because MAL rotates refresh
 * tokens) can lose the Session if the launch is killed mid-refresh.
 *
 * [obtainedAtEpochMs] is when the *access* token in [tokens] was issued. MAL's `expires_in`
 * describes the refresh token, not the access token, so it cannot be used for this.
 */
@Serializable
data class StoredSession(
    val tokens: MalTokens,
    val user: MalUser?,
    val obtainedAtEpochMs: Long,
)

/**
 * An authorization that has been started but not finished — the user is away on myanimelist.net.
 *
 * Persisted rather than held in memory because the two ordinary happy paths of a redirect flow
 * both destroy memory: Android process death while parked behind a browser, and the web
 * full-page-redirect fallback. Losing [codeVerifier] or [state] makes the eventual token
 * exchange impossible.
 *
 * [clientId] is carried because it is entered at runtime today, and without it the token call
 * cannot be rebuilt after a restart.
 */
@Serializable
data class PendingAuthorization(
    val codeVerifier: String,
    val state: String,
    val redirectUri: String,
    val clientId: String,
    val startedAtEpochMs: Long,
)

/**
 * The authorization URL for a restored [pending], so the UI can offer it even after a restart
 * without the URL itself ever having been stored.
 *
 * Takes the Pending Authorization's own Client ID and Redirect URI over whatever [config] holds now:
 * either may have been changed since the sign-in started, and MAL matches `redirect_uri`
 * byte-exactly against the one the authorization began with.
 *
 * A plain function over a `MalAuthConfig` rather than a method on `MalSessionRepository`, because the
 * one caller that matters is `ScreenStateSource`'s combine, which is pure and has a
 * `StateFlow<MalAuthConfig>` rather than a repository. Nothing here suspends, allocates an
 * `HttpClient`, or can throw — a total mapping cannot.
 *
 * **Never log the result.** Under `plain` PKCE the code verifier travels inside it.
 */
fun authorizationUrlFor(config: MalAuthConfig, pending: PendingAuthorization): String =
    authorizationUrl(
        config = config.copy(clientId = pending.clientId, redirectUri = pending.redirectUri),
        codeVerifier = pending.codeVerifier,
        state = pending.state,
    )
