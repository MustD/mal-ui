package io.challenge_workshop.mal_ui.session

/**
 * Everything the session debug panel may display about the stored token pair.
 *
 * Metadata only, by construction: there is no field here that could hold a credential, so no future
 * edit to the panel can put one on screen, in a screenshot or in a screen share.
 */
data class SessionDiagnostics(
    val obtainedAtEpochMs: Long,
    val ageMillis: Long,
    /** Enough to tell two tokens apart after a refresh, without being one. */
    val accessTokenLength: Int,
    val hasRefreshToken: Boolean,
    /** True after the force-401 button has run and before the next request refreshes. */
    val accessTokenIsDeliberatelyInvalid: Boolean,
)
