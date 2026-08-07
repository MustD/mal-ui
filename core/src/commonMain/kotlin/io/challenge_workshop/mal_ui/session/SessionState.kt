package io.challenge_workshop.mal_ui.session

import io.challenge_workshop.mal_ui.mal.MalUser

/**
 * Everything the UI needs to know about whether there is a Session, and nothing else.
 *
 * Three choices here will read as omissions to a future reader, so they are named:
 *
 *  - **There is no resting `Expired` state.** Expiry is discovered mid-request and immediately
 *    either recovers or becomes `SignedOut(RefreshRejected)`. A distinct state would add no
 *    transitions and would invite "expired but still rendering" bugs. The nuance the UI actually
 *    needs lives in [SignedOutReason].
 *  - **`refreshing` is a flag on [SignedIn], not a state of its own**, because a refresh must not
 *    unmount the screen.
 *  - **No token appears anywhere in here.** Tokens live in the [JsonTokenStore] and are read by the
 *    bearer provider, which keeps them out of Compose snapshots and out of every `toString()`, and
 *    stops the displayed state drifting from what the HTTP client actually sends.
 */
sealed interface SessionState {

    /** App start: reading the store. The UI shows a splash, not a sign-in form. */
    data object Restoring : SessionState

    data class SignedOut(val reason: SignedOutReason, val error: String? = null) : SessionState

    /** The user is away on myanimelist.net. */
    data class Authorizing(val pending: PendingAuthorization) : SessionState

    data class SignedIn(val user: MalUser?, val refreshing: Boolean = false) : SessionState
}

/**
 * Why a Session is absent. Carried so the UI can explain itself — `RefreshRejected` reads "your MAL
 * session expired" rather than a bare "signed out".
 */
enum class SignedOutReason {
    NeverSignedIn,
    UserSignedOut,
    RefreshRejected,
    AuthorizationFailed,
}
