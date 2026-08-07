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
import io.challenge_workshop.mal_ui.mal.MalAuthException
import io.challenge_workshop.mal_ui.mal.platformMalEndpoints
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    val redirectUri: String get() = repository.config.value.redirectUri ?: "(none)"

    /** True on web, where token and API calls go via `:server` instead of straight to MAL. */
    val usesRelay: Boolean = !endpoints.tokenEndpoint.startsWith("https://myanimelist.net")

    val canStart: Boolean get() = clientId.isNotBlank() && !busy
    val canComplete: Boolean get() = pastedRedirect.isNotBlank() && !busy

    private var authJob: Job? = null

    init {
        // Exactly once per process: the repository is a singleton, so a recreated ViewModel finds the
        // state already settled and leaves it alone.
        if (repository.state.value is SessionState.Restoring) {
            viewModelScope.launch { repository.restore() }
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
     * Starts a sign-in and hands the authorization URL to [openUri].
     *
     * The URL is passed out rather than stored, and never logged: under `plain` PKCE the code verifier
     * travels inside it.
     */
    fun signIn(openUri: (String) -> Unit) {
        if (!canStart) return
        repository.useClientId(clientId)
        launchGuarded { openUri(repository.beginAuthorization()) }
    }

    /** Paste-the-code, and also the path every platform Redirect Capture funnels into. */
    fun completeSignIn(rawRedirect: String = pastedRedirect) {
        if (rawRedirect.isBlank() || busy) return
        launchGuarded {
            repository.completeAuthorization(rawRedirect)
            pastedRedirect = ""
        }
    }

    /** Backing out. Keeps the Pending Authorization — a redirect that lands later is still good. */
    fun cancelSignIn() {
        authJob?.cancel()
        busy = false
        launchGuarded { repository.cancelAuthorization() }
    }

    fun signOut() {
        pastedRedirect = ""
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

    private fun launchGuarded(block: suspend () -> Unit) {
        busy = true
        error = null
        authJob = viewModelScope.launch {
            try {
                block()
            } catch (e: MalAuthException) {
                error = withRelayHint(e.message ?: e.toString())
            } catch (e: Exception) {
                error = withRelayHint(e.message ?: e.toString())
            } finally {
                busy = false
            }
        }
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
    }
}
