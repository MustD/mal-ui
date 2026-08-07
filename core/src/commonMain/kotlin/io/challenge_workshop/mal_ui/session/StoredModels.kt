package io.challenge_workshop.mal_ui.session

import io.challenge_workshop.mal_ui.mal.MalTokens
import io.challenge_workshop.mal_ui.mal.MalUser
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
