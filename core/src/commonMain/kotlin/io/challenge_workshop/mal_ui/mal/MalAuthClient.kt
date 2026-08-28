package io.challenge_workshop.mal_ui.mal

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The client configuration every MAL-facing [HttpClient] needs, whether or not it also carries the
 * `Auth` plugin.
 *
 * `expectSuccess` stays off deliberately: MAL puts the useful diagnostics in the body of a
 * 4xx, and we want to read it rather than have Ktor throw first.
 */
fun HttpClientConfig<*>.malClientDefaults() {
    expectSuccess = false
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
    }
}

/**
 * Builds the [HttpClient] used to talk to MAL. Each target contributes an engine, so the
 * engine-less factory resolves one automatically.
 */
fun createMalHttpClient(): HttpClient = HttpClient { malClientDefaults() }

/**
 * How to make an [HttpClient]. The one seam that lets a test drive the whole refresh path against a
 * `MockEngine` while production picks up whichever engine its target contributes.
 */
fun interface HttpClientFactory {
    fun create(configure: HttpClientConfig<*>.() -> Unit): HttpClient

    companion object {
        val Default: HttpClientFactory = HttpClientFactory { configure -> HttpClient(configure) }
    }
}

/**
 * The MyAnimeList OAuth2 + API v2 client.
 *
 * The flow is three steps, and step 2 happens outside this app entirely:
 *  1. [beginAuthorization] — build a URL and hold on to the returned request.
 *  2. The user approves access in a browser and is redirected to the registered URL.
 *  3. [exchangeCode] (or [completeAuthorization]) — trade the code for tokens.
 */
class MalAuthClient(
    private val config: MalAuthConfig,
    private val http: HttpClient = createMalHttpClient(),
    /** True when this instance owns [http] and should close it in [close]. */
    private val ownsHttpClient: Boolean = true,
) {
    /** Step 1: mint a PKCE verifier plus the URL to send the user to. */
    fun beginAuthorization(): MalAuthRequest =
        authorizationFor(Pkce.generateCodeVerifier(), Pkce.generateState())

    /**
     * The authorization URL for a *specific* verifier and state, rather than a freshly minted pair.
     *
     * Exists so a Pending Authorization that was persisted before the app restarted can have its URL
     * rebuilt — which is possible at all only because MAL supports `plain` PKCE, so the challenge *is*
     * the verifier and there is nothing in the URL that is not in the record.
     *
     * The one construction site for this URL. Two would drift, and MAL matches `redirect_uri`
     * byte-exactly.
     */
    fun authorizationFor(codeVerifier: String, state: String): MalAuthRequest {
        // Minting is the one place a blank Client ID is a misconfiguration rather than a state to
        // render, which is why the check is here and not in [authorizationUrl] — that one is
        // called from a total mapping, and a mapping that can throw is not total.
        require(config.clientId.isNotBlank()) { "clientId must not be blank" }
        return MalAuthRequest(
            authorizationUrl = authorizationUrl(config, codeVerifier, state),
            codeVerifier = codeVerifier,
            state = state,
        )
    }

    /** Step 3: exchange an authorization code for tokens. */
    suspend fun exchangeCode(code: String, codeVerifier: String): MalTokens =
        postToken {
            append("grant_type", "authorization_code")
            append("code", code)
            append("code_verifier", codeVerifier)
            // Always sent to the authorize endpoint, so it is always required here too — and
            // byte-identically, since MAL compares the two strings rather than the two URIs.
            append("redirect_uri", config.redirectUri)
        }

    /** Trades a refresh token for a fresh pair. The old refresh token stays valid until it expires. */
    suspend fun refresh(refreshToken: String): MalTokens =
        postToken {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
        }

    /**
     * Fetches the signed-in user, which is the cheapest way to prove a token works.
     *
     * @param accessToken pass it explicitly when [http] has no `Auth` plugin; leave it null when it
     * does, so the plugin attaches the header and owns the refresh-on-401 behaviour. Setting it here
     * as well would send two `Authorization` headers.
     */
    suspend fun me(accessToken: String? = null): MalUser {
        val response = try {
            http.get("${config.apiBaseUrl.trimEnd('/')}/users/@me") {
                if (accessToken != null) header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
        } catch (e: MalAuthException) {
            throw e
        } catch (e: Exception) {
            throw MalAuthException("Could not reach the MAL API: ${e.message}", cause = e)
        }
        return response.decodeOrThrow()
    }

    private suspend fun postToken(
        extraParams: io.ktor.http.ParametersBuilder.() -> Unit,
    ): MalTokens {
        val response = try {
            http.submitForm(
                url = config.tokenEndpoint,
                formParameters = parameters {
                    // Scheme 2 from MAL's docs: client credentials in the body. Public clients
                    // (App Type `other`) have no secret and authenticate with client_id + PKCE.
                    append("client_id", config.clientId)
                    config.clientSecret?.takeIf { it.isNotBlank() }?.let { append("client_secret", it) }
                    extraParams()
                },
            )
        } catch (e: Exception) {
            throw MalAuthException("Could not reach the MAL token endpoint: ${e.message}", cause = e)
        }
        return response.decodeOrThrow()
    }

    fun close() {
        if (ownsHttpClient) http.close()
    }
}
