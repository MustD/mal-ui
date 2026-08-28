package io.challenge_workshop.mal_ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.mal.platformMalEndpoints
import io.challenge_workshop.mal_ui.screen.MalRouting
import io.challenge_workshop.mal_ui.screen.SignInForm
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.SessionDiagnostics
import io.challenge_workshop.mal_ui.session.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 *
 * Its two `StateFlow`s are two of the Screen State's six inputs. They are `StateFlow` rather than
 * `mutableStateOf` for exactly that reason: `ScreenStateSource` is in `:core` and has no Compose
 * dependency to read a snapshot state with. Nothing else about them changed — they are still
 * ephemeral, and still lost with this object.
 */
class MalSessionViewModel(
    private val repository: MalSessionRepository,
    private val startupRedirect: StartupRedirect,
) : ViewModel() {

    val state: StateFlow<SessionState> = repository.state

    /**
     * The live config, which the Screen State needs in order to rebuild the authorization URL and to
     * say which Redirect URI this build sends.
     *
     * Passed through rather than copied: the remembered Client ID only reaches it after `restore()`,
     * and a snapshot taken here would be the build-time default forever.
     */
    val config: StateFlow<MalAuthConfig> = repository.config

    /**
     * The sign-in form, prefilled so the Client ID reads as an override rather than a mandatory step:
     * the Client ID the device remembers if there is one, and the build-time `mal.clientId` default
     * otherwise. A public client's ID is not a secret — it is visible in the user's own address bar —
     * so the only goal is convenience and not-in-git.
     *
     * Seeded twice, because the remembered value comes out of the store and so cannot be in the config
     * yet when this object is constructed: once here, and again from `init` once `restore()` has
     * settled it.
     *
     * There is deliberately no Client Secret field. `MalAuthConfig.clientSecret` stays, because it is
     * correct for a `web`-type app, but offering it in the UI only creates a way to mis-register.
     */
    private val _form = MutableStateFlow(SignInForm(clientId = repository.config.value.clientId))
    val form: StateFlow<SignInForm> = _form.asStateFlow()

    /**
     * Token metadata for the debug panel, refreshed on demand rather than observed — it changes only
     * when something the panel itself triggered has finished.
     *
     * A separate flow from [form], and not a field of it: `busy` and `error` are read by three screens
     * and this by one dialog that only opens deliberately, so folding them together would make every
     * keystroke in the Client ID field emit a record carrying diagnostics nothing is reading.
     *
     * Never token values: [SessionDiagnostics] has no field that could hold one.
     */
    private val _diagnostics = MutableStateFlow<SessionDiagnostics?>(null)
    val diagnostics: StateFlow<SessionDiagnostics?> = _diagnostics.asStateFlow()

    /**
     * Where this build sends token and API traffic, which only [withRelayHint] reads.
     *
     * The same [MalRouting] the Screen State carries, rather than a second `startsWith` over the same
     * origin: two answers to "is this build relayed" is exactly how the hint and the debug panel come
     * to disagree. `redirectUri` is not read here — the screens get it off the Screen State.
     */
    private val routing = MalRouting(
        endpoints = platformMalEndpoints(),
        redirectUri = repository.config.value.redirectUri,
    )

    private val busy: Boolean get() = _form.value.busy

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
                // A remembered Client ID only exists in the config after this point. Safe to
                // overwrite the field: `busy` is set for the length of this block, and the Client ID
                // input is disabled while it is, so there is nothing typed to lose.
                _form.update { it.copy(clientId = repository.config.value.clientId) }
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
        _form.update { it.copy(clientId = value, error = null) }
        repository.useClientId(value)
    }

    fun onPastedRedirectChange(value: String) {
        _form.update { it.copy(pastedRedirect = value, error = null) }
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
        if (!_form.value.canStart) return
        repository.useClientId(_form.value.clientId)
        // One coroutine for the whole attempt, including the wait. An armed channel that is never
        // awaited can never be released, so nothing may come between arming it and awaiting it.
        signInJob = launchGuarded {
            when (val armed = channel.arm(repository.config.value.redirectUri)) {
                // Reported before anything is minted and before the user has approved anything on MAL
                // — which is the entire reason `arm` is a phase of its own.
                is ArmResult.Failed -> _form.update { it.copy(error = armed.message) }

                // No capture here, so the screen opens the browser itself and Paste-the-code takes
                // over. Calling `open` on a channel that declined to arm would capture nothing.
                ArmResult.Unsupported -> openUri(repository.beginAuthorization())

                ArmResult.Armed -> {
                    channel.open(repository.beginAuthorization())
                    // The user is away on myanimelist.net from here, and nothing is in flight. `busy`
                    // disables the paste field, the Complete button and Cancel, so leaving it set for
                    // the length of the wait would take Paste-the-code away exactly when it is needed.
                    _form.update { it.copy(busy = false) }
                    awaitCapture(channel)
                }
            }
        }
    }

    /** Every outcome lands on a path the paste field already uses, rather than a parallel one. */
    private suspend fun awaitCapture(channel: AuthRedirectChannel) {
        when (val captured = channel.await()) {
            is AuthRedirectResult.Received -> {
                _form.update { it.copy(busy = true) }
                // Verbatim into the same call the paste field makes, so one parser and one set of
                // errors — `error=access_denied` reads identically however the redirect arrived.
                completeAuthorization(captured.rawRedirect)
            }

            AuthRedirectResult.Cancelled -> repository.cancelAuthorization()

            // The capture broke, not the authorization: stay in `Authorizing`, where the URL and the
            // paste field are both still on screen.
            is AuthRedirectResult.Failed -> _form.update { it.copy(error = captured.message) }

            AuthRedirectResult.Unsupported -> Unit
        }
    }

    /** Paste-the-code, and also the path every platform Redirect Capture funnels into. */
    fun completeSignIn(rawRedirect: String = _form.value.pastedRedirect) {
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
        _form.update { it.copy(pastedRedirect = "") }
    }

    /** Backing out. Keeps the Pending Authorization — a redirect that lands later is still good. */
    fun cancelSignIn() {
        authJob?.cancel()
        // Also releases whatever the channel reserved: cancellation is its only teardown path.
        signInJob?.cancel()
        _form.update { it.copy(busy = false) }
        launchGuarded { repository.cancelAuthorization() }
    }

    fun signOut() {
        _form.update { it.copy(pastedRedirect = "") }
        signInJob?.cancel()
        launchGuarded { repository.signOut() }
    }

    fun refreshUser() = launchGuarded {
        repository.fetchUser()
        _diagnostics.value = repository.diagnostics()
    }

    fun reloadDiagnostics() = launchGuarded { _diagnostics.value = repository.diagnostics() }

    /** Debug panel: invalidate the access token so the next call refreshes against real MAL. */
    fun forceExpireAccessToken() = launchGuarded {
        repository.forceExpireAccessToken()
        _diagnostics.value = repository.diagnostics()
    }

    private fun launchGuarded(block: suspend () -> Unit): Job {
        _form.update { it.copy(busy = true, error = null) }
        return viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                // Not a failure to report. Cancelling a sign-in is routine — `cancelSignIn`, a paste
                // that beat the capture, `onCleared` — and reporting it would put "job was cancelled"
                // in an error card.
                throw e
            } catch (e: Exception) {
                _form.update { it.copy(error = withRelayHint(e.message ?: e.toString())) }
            } finally {
                _form.update { it.copy(busy = false) }
            }
        }.also { authJob = it }
    }

    /**
     * On web a dead relay surfaces as a bare "Failed to fetch" with no status, because the browser
     * blocks the request before it is sent. Name the likely cause instead.
     */
    private fun withRelayHint(message: String): String =
        if (routing.usesRelay && ("fetch" in message.lowercase() || "could not reach" in message.lowercase())) {
            "$message\n\nThe web target routes MAL calls through ${routing.endpoints.tokenEndpoint} " +
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
