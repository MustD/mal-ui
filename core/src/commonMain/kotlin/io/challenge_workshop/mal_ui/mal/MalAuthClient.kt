package io.challenge_workshop.mal_ui.mal

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
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
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
        require(config.clientId.isNotBlank()) { "clientId must not be blank" }
        val url = URLBuilder(config.authorizeEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.clientId)
            parameters.append("code_challenge", Pkce.codeChallengeOf(codeVerifier))
            parameters.append("code_challenge_method", Pkce.CHALLENGE_METHOD)
            parameters.append("state", state)
            config.redirectUri?.let { parameters.append("redirect_uri", it) }
        }.buildString()
        return MalAuthRequest(authorizationUrl = url, codeVerifier = codeVerifier, state = state)
    }

    /** Step 3: exchange an authorization code for tokens. */
    suspend fun exchangeCode(code: String, codeVerifier: String): MalTokens =
        postToken {
            append("grant_type", "authorization_code")
            append("code", code)
            append("code_verifier", codeVerifier)
            // Required here if and only if it was sent to the authorize endpoint.
            config.redirectUri?.let { append("redirect_uri", it) }
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
        return decodeOrThrow(response)
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
        return decodeOrThrow(response)
    }

    private suspend inline fun <reified T> decodeOrThrow(response: HttpResponse): T {
        if (!response.status.isSuccess()) throw response.toMalException()
        return try {
            response.body()
        } catch (e: Exception) {
            throw MalAuthException(
                "MAL returned a ${response.status.value} but the body did not parse: ${e.message}",
                status = response.status.value,
                cause = e,
            )
        }
    }

    private suspend fun HttpResponse.toMalException(): MalAuthException {
        val raw = runCatching { bodyAsText() }.getOrDefault("")
        val parsed = runCatching { json.decodeFromString<MalErrorBody>(raw) }.getOrNull()
        val detail = listOfNotNull(parsed?.message, parsed?.hint)
            .distinct()
            .joinToString(" — ")
            .ifBlank { raw.take(300).ifBlank { "no response body" } }
        return MalAuthException(
            message = buildString {
                append("MAL rejected the request (HTTP ${status.value}")
                parsed?.error?.let { append(", $it") }
                append("): ")
                append(detail)
                append(hintFor(parsed?.error))
            },
            status = status.value,
            errorCode = parsed?.error,
        )
    }

    /** MAL's error codes are terse; these are the ones that actually bite during setup. */
    private fun hintFor(errorCode: String?): String = when (errorCode) {
        // MAL reports a *Redirect URI* mismatch as 401 invalid_client / "Client authentication
        // failed", which implicates the Client ID and costs an hour of debugging. Naming both
        // possibilities here is the whole point of the hint.
        "invalid_client" ->
            "\n\nTwo possible causes. Either the Client ID is wrong — and if the app was registered " +
                    "with App Type `web`, MAL issued a Client Secret and requires it here too. Or the " +
                    "redirect_uri does not byte-exactly match one registered on the app: MAL reports " +
                    "a Redirect URI mismatch as this same 401 invalid_client, which points at the " +
                    "Client ID and not at the URI. A trailing slash, a changed port or a case " +
                    "difference is enough."

        "invalid_request" ->
            "\n\nUsually a redirect_uri mismatch: it must match a URL registered on the app exactly, " +
                    "and be sent to both the authorize and token endpoints or neither."

        "invalid_grant" ->
            "\n\nThe code was already used, expired, or the code_verifier does not match. " +
                    "Authorization codes are single-use — start the login again."

        else -> ""
    }

    fun close() {
        if (ownsHttpClient) http.close()
    }
}
