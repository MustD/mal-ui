package io.challenge_workshop.mal_ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.challenge_workshop.mal_ui.mal.MalAuthClient
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.mal.MalAuthException
import io.challenge_workshop.mal_ui.mal.MalAuthRequest
import io.challenge_workshop.mal_ui.mal.MalTokens
import io.challenge_workshop.mal_ui.mal.MalUser
import io.challenge_workshop.mal_ui.mal.createMalHttpClient
import io.challenge_workshop.mal_ui.mal.platformMalEndpoints
import kotlinx.coroutines.launch

/**
 * Drives the paste-the-code login flow.
 *
 * Survives configuration changes so an in-flight [authRequest] — and with it the PKCE code
 * verifier — is not lost while the user is away in the browser. Losing it would force a
 * restart of the whole flow.
 */
class MalLoginViewModel : ViewModel() {

    /** Credentials are entered at runtime so no real Client ID ends up committed. */
    var clientId by mutableStateOf("")
        private set
    var clientSecret by mutableStateOf("")
        private set
    var redirectUri by mutableStateOf(MalAuthConfig.LOOPBACK_REDIRECT_URI)
        private set
    var pastedRedirect by mutableStateOf("")
        private set

    /** Non-null once step 1 has run; holds the code verifier the token exchange needs. */
    var authRequest by mutableStateOf<MalAuthRequest?>(null)
        private set
    var tokens by mutableStateOf<MalTokens?>(null)
        private set
    var user by mutableStateOf<MalUser?>(null)
        private set

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    val canStart: Boolean get() = clientId.isNotBlank() && !busy
    val canComplete: Boolean get() = authRequest != null && pastedRedirect.isNotBlank() && !busy

    /** Effective endpoints, shown in the UI because a misrouted web build is otherwise silent. */
    val endpoints = platformMalEndpoints()

    /** True on web, where requests go via `:server` instead of straight to MAL. */
    val usesRelay: Boolean = !endpoints.tokenEndpoint.startsWith("https://myanimelist.net")

    private val http = createMalHttpClient()

    private fun client() = MalAuthClient(
        config = MalAuthConfig(
            clientId = clientId.trim(),
            clientSecret = clientSecret.trim().takeIf { it.isNotEmpty() },
            redirectUri = redirectUri.trim().takeIf { it.isNotEmpty() },
        ),
        http = http,
        ownsHttpClient = false,
    )

    fun onClientIdChange(value: String) {
        clientId = value; error = null
    }

    fun onClientSecretChange(value: String) {
        clientSecret = value; error = null
    }

    fun onRedirectUriChange(value: String) {
        redirectUri = value; error = null
    }

    fun onPastedRedirectChange(value: String) {
        pastedRedirect = value; error = null
    }

    /** Step 1: build the authorization URL. Returns it so the caller can open a browser. */
    fun startAuthorization(): String? {
        if (!canStart) return null
        error = null
        notice = null
        return try {
            client().beginAuthorization().also { authRequest = it }.authorizationUrl
        } catch (e: Exception) {
            error = e.message ?: "Could not build the authorization URL."
            null
        }
    }

    /** Step 3: exchange the pasted code for tokens, then confirm them against `/users/@me`. */
    fun completeLogin() {
        val request = authRequest ?: return
        if (!canComplete) return
        launchGuarded {
            val c = client()
            val received = c.completeAuthorization(request, pastedRedirect)
            tokens = received
            user = c.me(received.accessToken)
            pastedRedirect = ""
            notice = "Signed in as ${user?.name}."
        }
    }

    /** Verifies the stored access token still works. */
    fun fetchMe() {
        val accessToken = tokens?.accessToken ?: return
        launchGuarded {
            user = client().me(accessToken)
            notice = "Token is valid — fetched ${user?.name}."
        }
    }

    fun refreshTokens() {
        val refreshToken = tokens?.refreshToken ?: return
        launchGuarded {
            tokens = client().refresh(refreshToken)
            notice = "Tokens refreshed."
        }
    }

    /** Clears session state but keeps the entered credentials, so retrying is quick. */
    fun signOut() {
        authRequest = null
        tokens = null
        user = null
        pastedRedirect = ""
        error = null
        notice = null
    }

    private fun launchGuarded(block: suspend () -> Unit) {
        busy = true
        error = null
        notice = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: MalAuthException) {
                error = e.message?.let(::withRelayHint)
            } catch (e: Exception) {
                error = withRelayHint(e.message ?: e.toString())
            } finally {
                busy = false
            }
        }
    }

    /**
     * On web a dead relay surfaces as a bare "Fail to fetch" with no status, because the
     * browser blocks the request before it is sent. Name the likely cause instead.
     */
    private fun withRelayHint(message: String): String =
        if (usesRelay && ("fetch" in message.lowercase() || "could not reach" in message.lowercase())) {
            "$message\n\nThe web target routes MAL calls through ${endpoints.tokenEndpoint} " +
                    "because MAL sends no CORS headers. Start the relay with `./gradlew :server:run`."
        } else {
            message
        }

    override fun onCleared() {
        http.close()
    }
}
