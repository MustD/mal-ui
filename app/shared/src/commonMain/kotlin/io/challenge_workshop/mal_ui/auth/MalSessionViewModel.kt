package io.challenge_workshop.mal_ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.PendingAuthorization
import io.challenge_workshop.mal_ui.session.SessionDiagnostics
import io.challenge_workshop.mal_ui.session.SessionState
import io.challenge_workshop.mal_ui.mal.platformMalEndpoints
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Compose's adapter onto [MalSessionRepository].
 *
 * Named for the Session and not for a login: it owns `Restoring`, `Authorizing`, `SignedIn` and
 * refresh, and only one of those four is a login.
 *
 * It holds almost nothing. Everything durable — the Session, the Pending Authorization, the token
 * pair, which state we are in — belongs to the repository, which is a process-scoped singleton and so
 * survives this class being recreated. What is left here is genuinely ephemeral form text plus a
 * `busy` flag, and losing all of it to a configuration change or process death is correct.
 */
class MalSessionViewModel(
    private val repository: MalSessionRepository,
    private val startupRedirect: StartupRedirect,
) : ViewModel() {

    val state: StateFlow<SessionState> = repository.state

    /**
     * Prefilled from the build-time default so it reads as an override rather than a mandatory step.
     * A public client's ID is not a secret — it is visible in the user's own address bar — so the only
     * goal is convenience and not-in-git.
     */
    var clientId by mutableStateOf(repository.config.value.clientId)
        private set

    var pastedRedirect by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /**
     * Token metadata for the debug panel, refreshed on demand rather than observed — it changes only
     * when something the panel itself triggered has finished.
     *
     * Never token values: [SessionDiagnostics] has no field that could hold one.
     */
    var diagnostics by mutableStateOf<SessionDiagnostics?>(null)
        private set

    /**
     * There is deliberately no Client Secret field. `MalAuthConfig.clientSecret` stays, because it is
     * correct for a `web`-type app, but offering it in the UI only creates a way to mis-register.
     */

    /** Effective endpoints, surfaced in the UI because a misrouted web build is otherwise silent. */
    val endpoints = platformMalEndpoints()

    /**
     * The Redirect URI this target sends to MAL. Surfaced because a mismatch reports as a 401
     * `invalid_client`, which points at the Client ID and not at the URI.
     */
    val redirectUri: String get() = repository.config.value.redirectUri

    /** True on web, where token and API calls go via `:server` instead of straight to MAL. */
    val usesRelay: Boolean = !endpoints.tokenEndpoint.startsWith("https://myanimelist.net")

    val canStart: Boolean get() = clientId.isNotBlank() && !busy
    val canComplete: Boolean get() = pastedRedirect.isNotBlank() && !busy

    private var authJob: Job? = null

    /**
     * The whole sign-in attempt, held apart from [authJob] — which every short operation overwrites —
     * because this one lives for as long as the user is away on myanimelist.net. Cancelling it is how
     * an armed [AuthRedirectChannel] releases whatever it reserved.
     */
    private var signInJob: Job? = null

    init {
        // Exactly once per process: the repository is a singleton, so a recreated ViewModel finds the
        // state already settled and leaves it alone.
        if (repository.state.value is SessionState.Restoring) {
            launchGuarded {
                repository.restore()
                // Strictly after the store has been read. `restore` settles the state from what it
                // finds there, so completing a redirect first would have its `SignedIn` overwritten
                // a moment later by whatever the store said before the sign-in.
                //
                // Guarded like every other path, because the failures here are ones a user has to be
                // told about: a code with no Pending Authorization to complete it, or a denial. The
                // symptom of swallowing either is a sign-in button that appears to do nothing.
                startupRedirect.consume()?.let { completeAuthorization(it) }
            }
        }
    }

    fun onClientIdChange(value: String) {
        clientId = value
        error = null
        repository.useClientId(value)
    }

    fun onPastedRedirectChange(value: String) {
        pastedRedirect = value
        error = null
    }

    /**
     * Starts a sign-in: arms this target's Redirect Capture, mints the authorization URL, and sends the
     * user to MAL.
     *
     * [channel] is passed in rather than held, because it comes from a `@Composable` — see
     * [AuthRedirectChannel] for why it has to. [openUri] is the browser-opening fallback for when the
     * channel reports [ArmResult.Unsupported] and Paste-the-code takes over; an armed channel opens the
     * browser itself, since on web that call *is* the popup.
     *
     * Nothing here logs the URL: under `plain` PKCE the code verifier travels inside it.
     *
     * The stretch from the click to [AuthRedirectChannel.open] must not really suspend on web — a
     * popup loses its user activation if it does, and WebKit's window is 1 second. It currently does
     * not: `viewModelScope` is `Dispatchers.Main.immediate`, and neither the web `arm` nor
     * [MalSessionRepository.beginAuthorization] reaches a suspension point, so the popup opens before
     * `signIn` returns. That is not obvious from reading this and is not something to assume —
     * `PopupUserActivationTest` in `webTest` pins it against the production dispatcher on both
     * browser targets. Desktop is the counter-example that shows how easily it goes: its `arm` binds
     * a socket on `Dispatchers.IO` and genuinely dispatches.
     */
    fun signIn(channel: AuthRedirectChannel, openUri: (String) -> Unit) {
        if (!canStart) return
        repository.useClientId(clientId)
        // One coroutine for the whole attempt, including the wait. An armed channel that is never
        // awaited can never be released, so nothing may come between arming it and awaiting it.
        signInJob = launchGuarded {
            when (val armed = channel.arm(redirectUri)) {
                // Reported before anything is minted and before the user has approved anything on MAL
                // — which is the entire reason `arm` is a phase of its own.
                is ArmResult.Failed -> error = armed.message

                // No capture here, so the screen opens the browser itself and Paste-the-code takes
                // over. Calling `open` on a channel that declined to arm would capture nothing.
                ArmResult.Unsupported -> openUri(repository.beginAuthorization())

                ArmResult.Armed -> {
                    channel.open(repository.beginAuthorization())
                    // The user is away on myanimelist.net from here, and nothing is in flight. `busy`
                    // disables the paste field, the Complete button and Cancel, so leaving it set for
                    // the length of the wait would take Paste-the-code away exactly when it is needed.
                    busy = false
                    awaitCapture(channel)
                }
            }
        }
    }

    /** Every outcome lands on a path the paste field already uses, rather than a parallel one. */
    private suspend fun awaitCapture(channel: AuthRedirectChannel) {
        when (val captured = channel.await()) {
            is AuthRedirectResult.Received -> {
                busy = true
                // Verbatim into the same call the paste field makes, so one parser and one set of
                // errors — `error=access_denied` reads identically however the redirect arrived.
                completeAuthorization(captured.rawRedirect)
            }

            AuthRedirectResult.Cancelled -> repository.cancelAuthorization()

            // The capture broke, not the authorization: stay in `Authorizing`, where the URL and the
            // paste field are both still on screen.
            is AuthRedirectResult.Failed -> error = captured.message

            AuthRedirectResult.Unsupported -> Unit
        }
    }

    /** Paste-the-code, and also the path every platform Redirect Capture funnels into. */
    fun completeSignIn(rawRedirect: String = pastedRedirect) {
        if (rawRedirect.isBlank() || busy) return
        launchGuarded {
            completeAuthorization(rawRedirect)
            // A paste can beat the capture to it. Nothing is left to capture, so let the channel go:
            // cancelling the await is what releases a bound port or an open popup.
            signInJob?.cancel()
        }
    }

    private suspend fun completeAuthorization(rawRedirect: String) {
        repository.completeAuthorization(rawRedirect)
        pastedRedirect = ""
    }

    /** Backing out. Keeps the Pending Authorization — a redirect that lands later is still good. */
    fun cancelSignIn() {
        authJob?.cancel()
        // Also releases whatever the channel reserved: cancellation is its only teardown path.
        signInJob?.cancel()
        busy = false
        launchGuarded { repository.cancelAuthorization() }
    }

    fun signOut() {
        pastedRedirect = ""
        signInJob?.cancel()
        launchGuarded { repository.signOut() }
    }

    fun refreshUser() = launchGuarded {
        repository.fetchUser()
        diagnostics = repository.diagnostics()
    }

    fun reloadDiagnostics() = launchGuarded { diagnostics = repository.diagnostics() }

    /** Debug panel: invalidate the access token so the next call refreshes against real MAL. */
    fun forceExpireAccessToken() = launchGuarded {
        repository.forceExpireAccessToken()
        diagnostics = repository.diagnostics()
    }

    /** The URL for a restored Pending Authorization, so the UI can offer it after a restart. */
    fun authorizationUrlFor(pending: PendingAuthorization): String =
        repository.authorizationUrlFor(pending)

    private fun launchGuarded(block: suspend () -> Unit): Job {
        busy = true
        error = null
        return viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                // Not a failure to report. Cancelling a sign-in is routine — `cancelSignIn`, a paste
                // that beat the capture, `onCleared` — and reporting it would put "job was cancelled"
                // in an error card.
                throw e
            } catch (e: Exception) {
                error = withRelayHint(e.message ?: e.toString())
            } finally {
                busy = false
            }
        }.also { authJob = it }
    }

    /**
     * On web a dead relay surfaces as a bare "Failed to fetch" with no status, because the browser
     * blocks the request before it is sent. Name the likely cause instead.
     */
    private fun withRelayHint(message: String): String =
        if (usesRelay && ("fetch" in message.lowercase() || "could not reach" in message.lowercase())) {
            "$message\n\nThe web target routes MAL calls through ${endpoints.tokenEndpoint} " +
                "because MAL sends no CORS headers. Start the relay with `./gradlew :server:run`."
        } else {
            message
        }

    override fun onCleared() {
        // The HttpClients belong to the repository, which is process-scoped and outlives this object,
        // so there is nothing here to close — only work in flight to stop.
        authJob?.cancel()
        signInJob?.cancel()
    }
}
