package io.challenge_workshop.mal_ui.session

import io.challenge_workshop.mal_ui.mal.HttpClientFactory
import io.challenge_workshop.mal_ui.mal.MalAuthClient
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.mal.MalAuthException
import io.challenge_workshop.mal_ui.mal.MalTokens
import io.challenge_workshop.mal_ui.mal.MalUser
import io.challenge_workshop.mal_ui.mal.malClientDefaults
import io.challenge_workshop.mal_ui.mal.parseRedirect
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.clearAuthTokens
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The single source of truth for whether there is a Session, the only thing that writes to the token
 * store, and the owner of the two HTTP clients that talk to MAL.
 *
 * Lives in `:core` rather than `:app:shared` so `./gradlew :core:allTests` covers it on all four
 * targets, and so it stays free of Compose and of Koin.
 *
 * **There must be exactly one of these per process.** Two would mean two Ktor `AuthTokenHolder`
 * caches over one store, and therefore a refresh race that the plugin's own mutex cannot see.
 */
class MalSessionRepository(
    private val store: JsonTokenStore,
    private val clock: Clock = Clock.System,
    initialConfig: MalAuthConfig = MalAuthConfig(clientId = ""),
    clientFactory: HttpClientFactory = HttpClientFactory.Default,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _config = MutableStateFlow(initialConfig)

    /** The effective MAL app configuration. The Client ID is entered at runtime, so this moves. */
    val config: StateFlow<MalAuthConfig> = _config.asStateFlow()

    /**
     * The token endpoint gets its **own, `Auth`-free** client. Reusing the authenticated one here
     * deadlocks — Ktor's own docs warn about it — because a refresh triggered by a 401 would re-enter
     * the provider that is already holding its mutex.
     */
    private val tokenHttp: HttpClient = clientFactory.create { malClientDefaults() }

    /**
     * Everything authenticated goes through here, and the `Auth` plugin owns refresh.
     *
     * Ktor 3.5.1's `AuthTokenHolder` is a `Mutex` plus a generation check, so N concurrent 401s on
     * the same cached token produce **one** `refreshTokens` call and the losers reuse the winner's
     * result. Do not wrap this in a hand-rolled mutex: the advice to do so predates the fixes in
     * 3.4.0 and serialises all parallel requests.
     *
     * With exactly one provider installed, a 401 carrying **no `WWW-Authenticate` header at all**
     * still triggers refresh — which matters because MAL's challenge header is not guaranteed.
     */
    private val authenticatedHttp: HttpClient = clientFactory.create {
        malClientDefaults()
        install(Auth) {
            bearer {
                // KTOR-8285: without this, a cancelled coroutine can drop a refresh that already
                // succeeded, losing the rotated pair.
                nonCancellableRefresh = true
                loadTokens { store.readSession()?.tokens?.asBearerTokens() }
                refreshTokens { performRefresh() }
            }
        }
    }

    private fun tokenApi() = MalAuthClient(_config.value, tokenHttp, ownsHttpClient = false)

    private fun authenticatedApi() = MalAuthClient(_config.value, authenticatedHttp, ownsHttpClient = false)

    /** Replaces the Client ID, which the user types at runtime. */
    fun useClientId(clientId: String) {
        _config.update { it.copy(clientId = clientId.trim()) }
    }

    /**
     * Reads the store and settles on a state. Optimistic by design — **never an eager refresh**.
     *
     * An eager refresh on every launch costs a round trip, fails offline, and (because MAL rotates
     * refresh tokens) can lose the Session if the launch is killed mid-refresh. The cached
     * [StoredSession.user] is enough to render the app bar, and the first ordinary authenticated
     * request is what discovers expiry and drives the refresh.
     *
     * ```
     * Restoring
     *  ├─ Pending Authorization present, usable → Authorizing(...)   // resume an interrupted flow
     *  ├─ Pending Authorization present, not    → clear it, then as below
     *  ├─ tokens present                        → SignedIn(cachedUser)
     *  └─ store empty                           → SignedOut(NeverSignedIn)
     * ```
     */
    suspend fun restore() {
        val pending = store.readPending()
        if (pending != null) {
            if (isResumable(pending)) {
                _state.value = SessionState.Authorizing(pending)
                return
            }
            store.clearPending()
        }
        _state.value = store.readSession()
            ?.let { SessionState.SignedIn(it.user) }
            ?: SessionState.SignedOut(SignedOutReason.NeverSignedIn)
    }

    /**
     * Starts an authorization: mints a PKCE verifier and `state`, **persists the Pending
     * Authorization before returning**, and moves to [SessionState.Authorizing].
     *
     * Persisting first is the whole point. The two ordinary happy paths of a redirect flow both
     * destroy memory — Android process death while parked behind a browser, and the web full-page
     * redirect — and the previous in-memory version failed silently when either happened.
     *
     * @return the authorization URL to send the user to. Never logged: under `plain` PKCE the code
     * verifier travels inside it.
     */
    suspend fun beginAuthorization(): String {
        val request = tokenApi().beginAuthorization()
        val pending = store.writePending(
            codeVerifier = request.codeVerifier,
            state = request.state,
            redirectUri = _config.value.redirectUri,
            clientId = _config.value.clientId,
        )
        _state.value = SessionState.Authorizing(pending)
        return request.authorizationUrl
    }

    /**
     * Rebuilds the authorization URL for a restored [pending], so the UI can offer it even after a
     * restart without ever having stored the URL itself.
     */
    fun authorizationUrlFor(pending: PendingAuthorization): String =
        MalAuthClient(
            config = _config.value.copy(clientId = pending.clientId, redirectUri = pending.redirectUri),
            http = tokenHttp,
            ownsHttpClient = false,
        ).authorizationFor(pending.codeVerifier, pending.state).authorizationUrl

    /**
     * Finishes an authorization from whatever came back — a full redirect URL, a bare query string, or
     * a bare code.
     *
     * The Pending Authorization is read from the **store**, not from memory, so a redirect that
     * arrives after process death still completes. The Client ID and Redirect URI used for the
     * exchange come from that record rather than from the live config, because MAL matches
     * `redirect_uri` byte-exactly and the config may have been edited while the user was away.
     *
     * When the Pending Authorization survives a failure is deliberate, and mirrors the refresh
     * classification: anything MAL *told* us (a denial, a rejected code) ends the attempt, while a
     * typo or a dropped connection leaves the record alone so retrying is possible.
     *
     * @throws MalAuthException always, on any failure — never a silent return.
     */
    suspend fun completeAuthorization(rawRedirect: String) {
        val pending = store.readPending() ?: throw MalAuthException(
            "There is no sign-in in progress on this device, so there is no code verifier to " +
                "complete one with. Start the sign-in again — an authorization code on its own is " +
                "not enough.",
        )

        val parsed = try {
            parseRedirect(rawRedirect)
        } catch (e: MalAuthException) {
            // MAL saying no (`error=access_denied`) ends the attempt; an unparseable paste does not.
            if (e.errorCode != null) failAuthorization(e)
            throw e
        }

        // A bare pasted code carries no state, so there is nothing to compare against.
        if (parsed.state != null && parsed.state != pending.state) {
            failAuthorization(
                MalAuthException(
                    "The `state` in that redirect does not match this sign-in attempt. Start the " +
                        "sign-in again rather than trusting it.",
                ),
            )
        }

        val exchangeConfig = _config.value.copy(
            clientId = pending.clientId,
            redirectUri = pending.redirectUri,
        )
        val tokens = try {
            MalAuthClient(exchangeConfig, tokenHttp, ownsHttpClient = false)
                .exchangeCode(parsed.code, pending.codeVerifier)
        } catch (e: MalAuthException) {
            // MAL rejected the code — and codes are single-use, so the record is spent. A transport
            // error or a 5xx tells us nothing, and MAL's codes last minutes, so keep it.
            if (isMalRejection(e)) failAuthorization(e)
            throw e
        }

        store.clearPending()
        store.writeSession(tokens, user = null)
        // Otherwise Ktor keeps serving whatever token it had cached from a previous Session.
        authenticatedHttp.clearAuthTokens()
        _state.value = SessionState.SignedIn(user = null)
        fetchUser()
    }

    /**
     * The user backed out, or a platform channel reported a cancellation.
     *
     * **Keeps the Pending Authorization.** Cancellation detection is best-effort on Android — the
     * redirect itself resumes you, and so does any call, notification or configuration change — so
     * destroying the verifier here would break logins that were about to succeed. A stale redirect
     * arriving later is caught by the `state` check instead.
     */
    suspend fun cancelAuthorization() {
        _state.value = store.readSession()
            ?.let { SessionState.SignedIn(it.user) }
            ?: SessionState.SignedOut(SignedOutReason.UserSignedOut)
    }

    private suspend fun failAuthorization(e: MalAuthException): Nothing {
        store.clearPending()
        _state.value = SessionState.SignedOut(SignedOutReason.AuthorizationFailed, e.message)
        throw e
    }

    /** MAL answered, and said no. A missing status means the request never got there. */
    private fun isMalRejection(e: MalAuthException): Boolean = e.status?.let { it in 400..499 } == true

    /**
     * Fetches the signed-in user over the authenticated client and caches it.
     *
     * This is also the only thing in a shell-only app that ever discovers an expired access token,
     * which is why the refresh path hangs off it rather than off a timer.
     */
    suspend fun fetchUser(): MalUser {
        val user = authenticatedApi().me()
        store.updateUser(user)
        _state.update { if (it is SessionState.SignedIn) it.copy(user = user) else it }
        return user
    }

    /** Deliberate sign-out. Drops both records, so nothing is left to resume. */
    suspend fun signOut() {
        store.clear()
        // Without this the next request would still carry the token Ktor has cached.
        authenticatedHttp.clearAuthTokens()
        _state.value = SessionState.SignedOut(SignedOutReason.UserSignedOut)
    }

    /**
     * Invalidates the access token while keeping the refresh token, so the next authenticated call
     * gets a genuine 401 from MAL and the `Auth` plugin refreshes for real. Drives the debug panel's
     * force-401 button, which is the only way a human ever sees the refresh path execute.
     *
     * Clearing Ktor's cached copy is **not optional**: `AuthTokenHolder` would keep serving the old
     * token and nothing observable would happen.
     */
    suspend fun forceExpireAccessToken() {
        val session = store.readSession() ?: return
        // The obtained-at stamp is preserved on purpose — a debug button must not lie about token age.
        store.writeSession(session.copy(tokens = session.tokens.copy(accessToken = INVALIDATED_ACCESS_TOKEN)))
        authenticatedHttp.clearAuthTokens()
    }

    /**
     * What the debug panel is allowed to know about the stored Session. **Never the token values** —
     * only their shape and age, which is enough to tell "the refresh worked" from "nothing happened".
     */
    suspend fun diagnostics(): SessionDiagnostics? = store.readSession()?.let {
        SessionDiagnostics(
            obtainedAtEpochMs = it.obtainedAtEpochMs,
            ageMillis = (clock.now().toEpochMilliseconds() - it.obtainedAtEpochMs).coerceAtLeast(0),
            accessTokenLength = it.tokens.accessToken.length,
            hasRefreshToken = it.tokens.refreshToken.isNotBlank(),
            accessTokenIsDeliberatelyInvalid = it.tokens.accessToken == INVALIDATED_ACCESS_TOKEN,
        )
    }

    fun close() {
        tokenHttp.close()
        authenticatedHttp.close()
    }

    /**
     * Called by the `Auth` plugin when a request came back 401.
     *
     * The refresh token is read from the **store** rather than from the plugin's `oldTokens`, so a
     * refresh that another code path already persisted is not undone.
     *
     * Returning null tells Ktor not to retry; the original 401 then surfaces to the caller. Which
     * failures also end the Session is the one thing in here that must not be wrong — see
     * [isRefreshRejection].
     */
    private suspend fun performRefresh(): BearerTokens? {
        val current = store.readSession() ?: return null
        markRefreshing(true)
        return try {
            val fresh = tokenApi().refresh(current.tokens.refreshToken)
            // Persisted *before* returning, so the retried request cannot outrun the write.
            store.updateTokens(fresh)
            fresh.asBearerTokens()
        } catch (e: MalAuthException) {
            if (isRefreshRejection(e)) {
                store.clear()
                _state.value = SessionState.SignedOut(SignedOutReason.RefreshRejected, e.message)
            }
            null
        } finally {
            markRefreshing(false)
        }
    }

    /**
     * `invalid_grant`, 400 or 401 mean MAL will not honour this refresh token again, so the Session
     * is over and the store has to be emptied.
     *
     * Everything else — a transport error (no status at all) or a 5xx — means we do not know, and the
     * refresh token is probably still good. **Do not clear on those.** "My app signed me out because
     * my wifi dropped" is the failure this distinction exists to prevent.
     */
    private fun isRefreshRejection(e: MalAuthException): Boolean =
        e.errorCode == "invalid_grant" || e.status == 400 || e.status == 401

    /** A flag rather than a state, so a refresh does not unmount the signed-in screen. */
    private fun markRefreshing(refreshing: Boolean) {
        _state.update { if (it is SessionState.SignedIn) it.copy(refreshing = refreshing) else it }
    }

    /**
     * Whether an interrupted authorization is worth putting the user back into.
     *
     * A record with no Redirect URI cannot be one: [completeAuthorization] would send an empty
     * `redirect_uri`, which MAL validates whenever it is *present*, and the exchange would fail as a
     * 401 `invalid_client` naming the Client ID. Only a build from before the URI became mandatory
     * could have written one, and nothing here can repair it — so it is discarded rather than
     * resumed into a state whose only exit is a confusing error.
     */
    private fun isResumable(pending: PendingAuthorization): Boolean =
        pending.redirectUri.isNotBlank() && isFresh(pending)

    /**
     * MAL's authorization codes are single-use and short-lived, so a Pending Authorization that old
     * is worthless. A stamp in the *future* is treated as stale too: a clock that went backwards —
     * a suspended laptop, an NTP correction — must not pin the app in `Authorizing` forever.
     */
    private fun isFresh(pending: PendingAuthorization): Boolean {
        val age = clock.now().toEpochMilliseconds() - pending.startedAtEpochMs
        return age >= 0 && age < PENDING_AUTHORIZATION_TTL.inWholeMilliseconds
    }

    companion object {
        val PENDING_AUTHORIZATION_TTL: Duration = 10.minutes

        /** Deliberately not a plausible token, so it is obvious in a diagnostic where it came from. */
        internal const val INVALIDATED_ACCESS_TOKEN: String = "invalidated-by-session-debug-panel"
    }
}

private fun MalTokens.asBearerTokens() = BearerTokens(accessToken, refreshToken)
