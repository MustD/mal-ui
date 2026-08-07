package io.challenge_workshop.mal_ui.session

import io.challenge_workshop.mal_ui.mal.HttpClientFactory
import io.challenge_workshop.mal_ui.mal.MalAuthException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The authorization half of the repository: minting a Pending Authorization, and completing one from
 * a raw redirect.
 *
 * Lives here rather than in a ViewModel test because this is where the correctness is. The old
 * `completeLogin()` did `val request = authRequest ?: return` over a `mutableStateOf`, so a redirect
 * arriving after Android process death or a web full-page redirect failed **silently** — and both of
 * those are on the happy path of a redirect flow, not edge cases.
 */
class MalSessionAuthorizationTest {

    private class Fake(
        private val exchange: Exchange = Exchange.Succeeds,
    ) {
        var tokenEndpointHits = 0
            private set

        sealed interface Exchange {
            data object Succeeds : Exchange
            data class MalRejects(val status: HttpStatusCode, val errorCode: String) : Exchange
            data object Unreachable : Exchange
            data object ServerError : Exchange
        }

        val engine = MockEngine { request ->
            when {
                request.url.toString().startsWith(TEST_TOKEN_ENDPOINT) -> {
                    tokenEndpointHits++
                    when (val e = exchange) {
                        Exchange.Succeeds -> respond(
                            content = """{"token_type":"Bearer","expires_in":2415600,""" +
                                """"access_token":"granted-access","refresh_token":"granted-refresh"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )

                        is Exchange.MalRejects -> respond(
                            content = """{"error":"${e.errorCode}","message":"nope"}""",
                            status = e.status,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )

                        Exchange.Unreachable -> throw IOException("network is down")
                        Exchange.ServerError -> respondError(HttpStatusCode.BadGateway)
                    }
                }

                request.url.encodedPath.endsWith("/users/@me") -> respond(
                    content = """{"id":42,"name":"someone"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )

                else -> respondError(HttpStatusCode.NotFound, "unexpected ${request.url}")
            }
        }

        val factory: HttpClientFactory = HttpClientFactory { configure -> HttpClient(engine) { configure() } }
    }

    private class Fixture(fake: Fake = Fake()) {
        val kv = FakeKeyValueStore()
        val clock = FakeClock()
        val store = JsonTokenStore(kv, clock = clock)
        val repository = MalSessionRepository(store, clock, TEST_CONFIG, fake.factory)
    }

    /** The URL MAL would have redirected to, as the user would paste it. */
    private fun redirect(code: String, state: String) =
        "http://127.0.0.1:18040/oauth/callback?code=$code&state=$state"

    @Test
    fun beginning_an_authorization_persists_the_pending_record_before_returning_the_url() = runTest {
        val f = Fixture()

        val url = f.repository.beginAuthorization()

        // The fix for the plan's biggest correctness gap: the verifier is on disk before the user is
        // anywhere near a browser.
        val pending = assertNotNull(f.store.readPending(), "nothing was persisted")
        assertTrue(pending.codeVerifier.isNotBlank())
        assertTrue(pending.state.isNotBlank())
        assertEquals(TEST_CONFIG.clientId, pending.clientId)
        assertEquals(TEST_CONFIG.redirectUri, pending.redirectUri)
        assertTrue(url.startsWith(TEST_CONFIG.authorizeEndpoint), url)
        f.repository.close()
    }

    @Test
    fun beginning_an_authorization_moves_to_authorizing_with_the_persisted_record() = runTest {
        val f = Fixture()

        f.repository.beginAuthorization()

        assertEquals(
            SessionState.Authorizing(assertNotNull(f.store.readPending())),
            f.repository.state.value,
        )
        f.repository.close()
    }

    @Test
    fun the_authorization_url_carries_the_verifier_as_a_plain_challenge_and_the_redirect_uri() = runTest {
        val f = Fixture()

        val url = f.repository.beginAuthorization()

        val pending = assertNotNull(f.store.readPending())
        assertTrue("code_challenge=${pending.codeVerifier}" in url, url)
        assertTrue("code_challenge_method=plain" in url, url)
        assertTrue("state=${pending.state}" in url, url)
        assertTrue("redirect_uri=" in url, url)
        f.repository.close()
    }

    @Test
    fun the_authorization_url_can_be_rebuilt_from_a_restored_pending_record() = runTest {
        // What lets the UI show a copyable authorization URL after a restart, without storing the URL.
        val f = Fixture()
        val url = f.repository.beginAuthorization()
        val pending = assertNotNull(f.store.readPending())

        val fresh = Fixture(Fake()).let { other ->
            other.kv.entries.putAll(f.kv.entries)
            other.repository.restore()
            other
        }

        assertEquals(url, fresh.repository.authorizationUrlFor(pending))
        f.repository.close()
        fresh.repository.close()
    }

    @Test
    fun a_pasted_redirect_completes_the_login_and_persists_the_session() = runTest {
        val f = Fixture()
        f.repository.beginAuthorization()
        val pending = assertNotNull(f.store.readPending())

        f.repository.completeAuthorization(redirect("the-code", pending.state))

        assertEquals(SessionState.SignedIn(TEST_USER), f.repository.state.value)
        assertEquals("granted-refresh", f.store.readSession()?.tokens?.refreshToken)
        assertNull(f.store.readPending(), "a used Pending Authorization must be cleared")
        f.repository.close()
    }

    @Test
    fun a_bare_pasted_code_with_no_state_still_completes() = runTest {
        // People do copy just the code. There is nothing to compare against, which is not a mismatch.
        val f = Fixture()
        f.repository.beginAuthorization()

        f.repository.completeAuthorization("  the-code  ")

        assertIs<SessionState.SignedIn>(f.repository.state.value)
        f.repository.close()
    }

    @Test
    fun a_session_signed_in_this_way_survives_a_process_restart_with_no_token_call() = runTest {
        val fake = Fake()
        val f = Fixture(fake)
        f.repository.beginAuthorization()
        val pending = assertNotNull(f.store.readPending())
        f.repository.completeAuthorization(redirect("the-code", pending.state))
        val hitsAtSignIn = fake.tokenEndpointHits
        f.repository.close()

        // A brand-new repository over the same store, i.e. the next launch.
        val relaunch = Fixture(fake).also { it.kv.entries.putAll(f.kv.entries) }
        relaunch.repository.restore()

        assertEquals(SessionState.SignedIn(TEST_USER), relaunch.repository.state.value)
        assertEquals(hitsAtSignIn, fake.tokenEndpointHits, "restore must not touch the token endpoint")
        relaunch.repository.close()
    }

    @Test
    fun a_state_mismatch_is_rejected_the_pending_record_cleared_and_the_reason_recorded() = runTest {
        val f = Fixture()
        f.repository.beginAuthorization()

        val failure = assertFailsWith<MalAuthException> {
            f.repository.completeAuthorization(redirect("the-code", "not-the-state"))
        }

        assertTrue("state" in failure.message.orEmpty().lowercase(), failure.message.orEmpty())
        assertNull(f.store.readPending())
        assertEquals(
            SignedOutReason.AuthorizationFailed,
            assertIs<SessionState.SignedOut>(f.repository.state.value).reason,
        )
        f.repository.close()
    }

    @Test
    fun a_state_mismatch_never_reaches_the_token_endpoint() = runTest {
        val fake = Fake()
        val f = Fixture(fake)
        f.repository.beginAuthorization()

        runCatching { f.repository.completeAuthorization(redirect("the-code", "not-the-state")) }

        assertEquals(0, fake.tokenEndpointHits)
        f.repository.close()
    }

    @Test
    fun a_redirect_arriving_with_no_pending_authorization_states_the_problem() = runTest {
        // The old code did `authRequest ?: return` here and failed silently.
        val f = Fixture()
        f.repository.restore()

        val failure = assertFailsWith<MalAuthException> {
            f.repository.completeAuthorization(redirect("the-code", "whatever"))
        }

        assertTrue(failure.message.orEmpty().isNotBlank())
        f.repository.close()
    }

    @Test
    fun a_pending_record_with_no_redirect_uri_is_discarded_rather_than_resumed() = runTest {
        // What a build from before `redirectUri` was mandatory left behind: it wrote
        // `config.redirectUri.orEmpty()`, and the config's was null. Resuming one would send
        // `redirect_uri=` to the token endpoint, which MAL validates whenever it is present — so the
        // user would get a 401 `invalid_client` naming the Client ID for a record that was never
        // completable. Nothing can repair it, so it must not pin the app in `Authorizing`.
        val f = Fixture()
        f.store.writePending(
            codeVerifier = "verifier-from-the-old-build",
            state = "state-from-the-old-build",
            redirectUri = "",
            clientId = "the-client",
        )

        f.repository.restore()

        assertNull(f.store.readPending(), "an uncompletable record must be cleared, not kept")
        assertEquals(
            SignedOutReason.NeverSignedIn,
            assertIs<SessionState.SignedOut>(f.repository.state.value).reason,
        )
        f.repository.close()
    }

    @Test
    fun a_denied_authorization_clears_the_pending_record() = runTest {
        val f = Fixture()
        f.repository.beginAuthorization()

        val failure = assertFailsWith<MalAuthException> {
            f.repository.completeAuthorization(
                "http://127.0.0.1:18040/oauth/callback?error=access_denied&error_description=denied",
            )
        }

        assertEquals("access_denied", failure.errorCode)
        assertNull(f.store.readPending(), "MAL said no — there is nothing left to resume")
        assertEquals(
            SignedOutReason.AuthorizationFailed,
            assertIs<SessionState.SignedOut>(f.repository.state.value).reason,
        )
        f.repository.close()
    }

    @Test
    fun an_unparseable_paste_keeps_the_pending_record_so_the_user_can_try_again() = runTest {
        val f = Fixture()
        f.repository.beginAuthorization()
        val pending = assertNotNull(f.store.readPending())

        assertFailsWith<MalAuthException> {
            f.repository.completeAuthorization("https://myanimelist.net/no-query-string-here")
        }

        assertEquals(pending, f.store.readPending(), "a typo must not force restarting the whole flow")
        assertIs<SessionState.Authorizing>(f.repository.state.value)
        f.repository.close()
    }

    @Test
    fun a_code_mal_rejects_clears_the_pending_record_because_codes_are_single_use() = runTest {
        val f = Fixture(Fake(Fake.Exchange.MalRejects(HttpStatusCode.BadRequest, "invalid_grant")))
        f.repository.beginAuthorization()
        val pending = assertNotNull(f.store.readPending())

        assertFailsWith<MalAuthException> {
            f.repository.completeAuthorization(redirect("already-used", pending.state))
        }

        assertNull(f.store.readPending())
        assertEquals(
            SignedOutReason.AuthorizationFailed,
            assertIs<SessionState.SignedOut>(f.repository.state.value).reason,
        )
        f.repository.close()
    }

    @Test
    fun an_unreachable_token_endpoint_keeps_the_pending_record() = runTest {
        // Same argument as a failed refresh: we do not know that the code is spent, and MAL's codes
        // last minutes. Destroying the verifier here would turn a dropped connection into a restart.
        val f = Fixture(Fake(Fake.Exchange.Unreachable))
        f.repository.beginAuthorization()
        val pending = assertNotNull(f.store.readPending())

        assertFailsWith<MalAuthException> {
            f.repository.completeAuthorization(redirect("the-code", pending.state))
        }

        assertEquals(pending, f.store.readPending())
        assertIs<SessionState.Authorizing>(f.repository.state.value)
        f.repository.close()
    }

    @Test
    fun a_5xx_from_the_token_endpoint_keeps_the_pending_record() = runTest {
        val f = Fixture(Fake(Fake.Exchange.ServerError))
        f.repository.beginAuthorization()
        val pending = assertNotNull(f.store.readPending())

        assertFailsWith<MalAuthException> {
            f.repository.completeAuthorization(redirect("the-code", pending.state))
        }

        assertEquals(pending, f.store.readPending())
        f.repository.close()
    }

    @Test
    fun the_exchange_reuses_the_client_id_and_redirect_uri_from_the_pending_record() = runTest {
        // Not from the live config: matching is byte-exact, and the live config may have been edited
        // while the user was away in the browser.
        val fake = Fake()
        val f = Fixture(fake)
        f.repository.beginAuthorization()
        val pending = assertNotNull(f.store.readPending())
        f.repository.useClientId("edited-since")

        f.repository.completeAuthorization(redirect("the-code", pending.state))

        val exchange = fake.engine.requestHistory.last { it.url.toString().startsWith(TEST_TOKEN_ENDPOINT) }
        val form = parseQueryString(exchange.body.toByteArray().decodeToString())
        assertEquals(TEST_CONFIG.clientId, form["client_id"])
        assertEquals(TEST_CONFIG.redirectUri, form["redirect_uri"])
        assertEquals(pending.codeVerifier, form["code_verifier"])
        f.repository.close()
    }

    @Test
    fun cancelling_keeps_the_pending_record_so_a_late_redirect_still_works() = runTest {
        val f = Fixture()
        f.repository.beginAuthorization()
        val pending = assertNotNull(f.store.readPending())

        f.repository.cancelAuthorization()

        assertEquals(pending, f.store.readPending())
        assertIs<SessionState.SignedOut>(f.repository.state.value)
        f.repository.close()
    }
}
