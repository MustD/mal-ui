package io.challenge_workshop.mal_ui.session

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The correctness argument for reactive refresh.
 *
 * Nothing in a shell-only app exercises this organically — a single `/users/@me` at startup never
 * crosses the one-hour access-token boundary — so these tests are the only thing standing between
 * "it compiles" and "it works". [MalSessionRefreshTest.a_transport_failure_during_refresh_keeps_the_store]
 * is the one that matters most: "my app signed me out because my wifi dropped" is the failure the
 * whole classification table exists to prevent.
 */
class MalSessionRefreshTest {

    private suspend fun signedIn(mal: FakeMal): Pair<MalSessionRepository, JsonTokenStore> {
        val store = JsonTokenStore(FakeKeyValueStore(), clock = FakeClock())
        store.writeSession(STALE_TOKENS, TEST_USER)
        val repository = MalSessionRepository(
            store = store,
            clock = FakeClock(),
            initialConfig = TEST_CONFIG,
            clientFactory = mal.clientFactory,
        )
        repository.restore()
        return repository to store
    }

    @Test
    fun a_bare_401_with_no_challenge_header_still_triggers_a_refresh() = runTest {
        val mal = FakeMal(sendChallengeHeader = false)
        val (repository, _) = signedIn(mal)

        val user = repository.fetchUser()

        assertEquals(TEST_USER, user)
        assertEquals(1, mal.tokenEndpointHits, "the bare 401 must have driven exactly one refresh")
        repository.close()
    }

    @Test
    fun a_401_carrying_a_challenge_header_also_triggers_a_refresh() = runTest {
        val mal = FakeMal(sendChallengeHeader = true)
        val (repository, _) = signedIn(mal)

        repository.fetchUser()

        assertEquals(1, mal.tokenEndpointHits)
        repository.close()
    }

    @Test
    fun concurrent_401s_produce_exactly_one_refresh_and_every_request_still_succeeds() = runTest {
        val mal = FakeMal()
        val (repository, _) = signedIn(mal)

        val results = List(8) { async { repository.fetchUser() } }.awaitAll()

        assertEquals(1, mal.tokenEndpointHits, "the Auth plugin must coalesce the stampede")
        assertTrue(results.all { it == TEST_USER }, "all eight requests must ultimately succeed")
        repository.close()
    }

    @Test
    fun a_successful_refresh_persists_the_new_pair_before_the_retry_is_issued() = runTest {
        lateinit var store: JsonTokenStore
        var seenAtRetry: StoredSession? = null
        val mal = FakeMal(
            onAuthorizedRequest = { seenAtRetry = store.readSession() },
        )
        val (repository, s) = signedIn(mal)
        store = s

        repository.fetchUser()

        val persisted = assertNotNull(seenAtRetry, "the retried request never arrived")
        assertEquals("fresh-access", persisted.tokens.accessToken)
        assertEquals("fresh-refresh", persisted.tokens.refreshToken)
        repository.close()
    }

    @Test
    fun invalid_grant_clears_the_store_and_reports_a_rejected_refresh() = runTest {
        val mal = FakeMal(
            RefreshResponse.Rejected(HttpStatusCode.BadRequest, errorCode = "invalid_grant"),
        )
        val (repository, store) = signedIn(mal)

        runCatching { repository.fetchUser() }

        assertEquals(SignedOutReason.RefreshRejected, assertIs<SessionState.SignedOut>(repository.state.value).reason)
        assertEquals(null, store.readSession())
        repository.close()
    }

    @Test
    fun a_bare_400_from_the_token_endpoint_is_a_rejection() = runTest {
        val mal = FakeMal(RefreshResponse.Rejected(HttpStatusCode.BadRequest, errorCode = null))
        val (repository, store) = signedIn(mal)

        runCatching { repository.fetchUser() }

        assertEquals(SignedOutReason.RefreshRejected, assertIs<SessionState.SignedOut>(repository.state.value).reason)
        assertEquals(null, store.readSession())
        repository.close()
    }

    @Test
    fun a_401_from_the_token_endpoint_is_a_rejection() = runTest {
        val mal = FakeMal(RefreshResponse.Rejected(HttpStatusCode.Unauthorized, errorCode = "invalid_client"))
        val (repository, store) = signedIn(mal)

        runCatching { repository.fetchUser() }

        assertEquals(SignedOutReason.RefreshRejected, assertIs<SessionState.SignedOut>(repository.state.value).reason)
        assertEquals(null, store.readSession())
        repository.close()
    }

    @Test
    fun a_transport_failure_during_refresh_keeps_the_store_and_the_session() = runTest {
        val mal = FakeMal(RefreshResponse.TransportFailure)
        val (repository, store) = signedIn(mal)

        runCatching { repository.fetchUser() }

        // The refresh token is still good. An offline launch must not sign the user out.
        assertNotNull(store.readSession(), "a dropped connection must not clear the store")
        assertEquals("good-refresh", store.readSession()?.tokens?.refreshToken)
        assertIs<SessionState.SignedIn>(repository.state.value)
        repository.close()
    }

    @Test
    fun a_503_during_refresh_keeps_the_store_and_the_session() = runTest {
        val mal = FakeMal(RefreshResponse.ServerError())
        val (repository, store) = signedIn(mal)

        runCatching { repository.fetchUser() }

        assertNotNull(store.readSession(), "a 5xx must not clear the store")
        assertIs<SessionState.SignedIn>(repository.state.value)
        repository.close()
    }

    @Test
    fun a_failed_refresh_leaves_the_refreshing_flag_down() = runTest {
        val mal = FakeMal(RefreshResponse.ServerError())
        val (repository, _) = signedIn(mal)

        runCatching { repository.fetchUser() }

        assertEquals(false, assertIs<SessionState.SignedIn>(repository.state.value).refreshing)
        repository.close()
    }

    @Test
    fun refreshing_is_observable_while_a_refresh_is_in_flight_and_down_afterwards() = runTest {
        val release = CompletableDeferred<Unit>()
        val mal = FakeMal(releaseRefresh = release)
        val (repository, _) = signedIn(mal)

        val call = backgroundScope.launch { repository.fetchUser() }

        val midRefresh = repository.state.first { it is SessionState.SignedIn && it.refreshing }
        // A refresh must not unmount the screen, so the user survives on the state object.
        assertEquals(TEST_USER, (midRefresh as SessionState.SignedIn).user)

        release.complete(Unit)
        call.join()

        assertEquals(false, assertIs<SessionState.SignedIn>(repository.state.value).refreshing)
        repository.close()
    }

    @Test
    fun forcing_the_access_token_to_expire_drives_exactly_one_real_refresh() = runTest {
        // The debug panel's force-401 button. Writing a bad token into the store is not enough:
        // Ktor caches the access token in `AuthTokenHolder`, so without clearing that cache
        // nothing observable happens.
        val mal = FakeMal()
        val (repository, store) = signedIn(mal)
        repository.fetchUser()
        val hitsAfterFirstRefresh = mal.tokenEndpointHits

        repository.forceExpireAccessToken()
        val user = repository.fetchUser()

        assertEquals(TEST_USER, user)
        assertEquals(hitsAfterFirstRefresh + 1, mal.tokenEndpointHits)
        assertNotNull(store.readSession(), "a real refresh must leave a Session behind")
        repository.close()
    }

    @Test
    fun forcing_expiry_keeps_the_refresh_token_and_the_obtained_at_stamp() = runTest {
        val mal = FakeMal()
        val (repository, store) = signedIn(mal)
        val before = assertNotNull(store.readSession())

        repository.forceExpireAccessToken()

        val after = assertNotNull(store.readSession())
        assertEquals(before.tokens.refreshToken, after.tokens.refreshToken)
        assertEquals(before.obtainedAtEpochMs, after.obtainedAtEpochMs, "a debug button must not lie about token age")
        assertTrue(after.tokens.accessToken != before.tokens.accessToken)
        repository.close()
    }

    @Test
    fun signing_out_also_drops_ktors_cached_access_token() = runTest {
        // Otherwise the next request after a sign-out would still carry a working bearer token.
        val mal = FakeMal()
        val (repository, _) = signedIn(mal)
        repository.fetchUser()

        repository.signOut()

        val failure = runCatching { repository.fetchUser() }
        assertTrue(failure.isFailure, "a signed-out repository must not be able to fetch the user")
        repository.close()
    }
}
