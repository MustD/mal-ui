package io.challenge_workshop.mal_ui.session

import io.challenge_workshop.mal_ui.mal.MalTokens
import io.challenge_workshop.mal_ui.mal.MalUser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private val TOKENS = MalTokens(
    tokenType = "Bearer",
    expiresIn = 2_415_600,
    accessToken = "access-1",
    refreshToken = "refresh-1",
)

private val USER = MalUser(id = 42, name = "someone")

class MalSessionRepositoryRestoreTest {

    private class Fixture {
        val kv = FakeKeyValueStore()
        val clock = FakeClock()
        val store = JsonTokenStore(kv, clock = clock)
        val repository = MalSessionRepository(store, clock)
    }

    @Test
    fun the_state_starts_at_restoring_before_anything_is_read() {
        val f = Fixture()

        // Not SignedOut: showing a sign-in form and then swapping it out is the flicker this
        // state exists to prevent.
        assertEquals(SessionState.Restoring, f.repository.state.value)
    }

    @Test
    fun an_empty_store_restores_to_never_signed_in() = runTest {
        val f = Fixture()

        f.repository.restore()

        assertEquals(SessionState.SignedOut(SignedOutReason.NeverSignedIn), f.repository.state.value)
    }

    @Test
    fun stored_tokens_restore_to_signed_in_with_the_cached_user() = runTest {
        val f = Fixture()
        f.store.writeSession(TOKENS, USER)

        f.repository.restore()

        assertEquals(SessionState.SignedIn(USER), f.repository.state.value)
    }

    @Test
    fun stored_tokens_with_no_cached_user_still_restore_to_signed_in() = runTest {
        val f = Fixture()
        f.store.writeSession(TOKENS, user = null)

        f.repository.restore()

        assertEquals(SessionState.SignedIn(user = null), f.repository.state.value)
    }

    @Test
    fun a_restored_session_is_not_marked_refreshing() = runTest {
        val f = Fixture()
        f.store.writeSession(TOKENS, USER)

        f.repository.restore()

        assertEquals(false, (f.repository.state.value as SessionState.SignedIn).refreshing)
    }

    @Test
    fun a_fresh_pending_authorization_restores_to_authorizing() = runTest {
        val f = Fixture()
        val pending = f.store.writePending("verifier", "state", "redirect", "client")
        f.clock.advanceBy(9.minutes.inWholeMilliseconds)

        f.repository.restore()

        assertEquals(SessionState.Authorizing(pending), f.repository.state.value)
    }

    @Test
    fun a_stale_pending_authorization_is_removed_and_the_tokens_decide() = runTest {
        val f = Fixture()
        f.store.writePending("verifier", "state", "redirect", "client")
        f.store.writeSession(TOKENS, USER)
        f.clock.advanceBy(11.minutes.inWholeMilliseconds)

        f.repository.restore()

        assertEquals(SessionState.SignedIn(USER), f.repository.state.value)
        assertNull(f.store.readPending(), "a stale Pending Authorization must not be left behind")
    }

    @Test
    fun a_stale_pending_authorization_with_no_tokens_restores_to_never_signed_in() = runTest {
        val f = Fixture()
        f.store.writePending("verifier", "state", "redirect", "client")
        f.clock.advanceBy(11.minutes.inWholeMilliseconds)

        f.repository.restore()

        assertEquals(SessionState.SignedOut(SignedOutReason.NeverSignedIn), f.repository.state.value)
        assertNull(f.store.readPending())
    }

    @Test
    fun a_pending_authorization_exactly_at_the_ttl_is_still_stale() = runTest {
        val f = Fixture()
        f.store.writePending("verifier", "state", "redirect", "client")
        f.clock.advanceBy(MalSessionRepository.PENDING_AUTHORIZATION_TTL.inWholeMilliseconds)

        f.repository.restore()

        assertIs<SessionState.SignedOut>(f.repository.state.value)
    }

    @Test
    fun a_pending_authorization_stamped_in_the_future_is_treated_as_stale() = runTest {
        // A clock that went backwards — a suspended laptop, an NTP correction — must not pin the
        // app in Authorizing forever.
        val f = Fixture()
        f.store.writePending("verifier", "state", "redirect", "client")
        f.clock.current = f.clock.current - 5.minutes

        f.repository.restore()

        assertIs<SessionState.SignedOut>(f.repository.state.value)
        assertNull(f.store.readPending())
    }

    @Test
    fun a_corrupt_session_blob_restores_to_never_signed_in_and_cleans_the_store() = runTest {
        val f = Fixture()
        f.kv.entries[JsonTokenStore.SESSION_KEY] = "{not json"

        f.repository.restore()

        assertEquals(SessionState.SignedOut(SignedOutReason.NeverSignedIn), f.repository.state.value)
        assertTrue(f.kv.entries.isEmpty(), "left behind ${f.kv.entries.keys}")
    }

    @Test
    fun a_corrupt_pending_blob_falls_through_to_the_tokens() = runTest {
        val f = Fixture()
        f.store.writeSession(TOKENS, USER)
        f.kv.entries[JsonTokenStore.PENDING_KEY] = "{not json"

        f.repository.restore()

        assertEquals(SessionState.SignedIn(USER), f.repository.state.value)
    }

    @Test
    fun signing_out_reports_the_user_did_it_and_empties_the_store() = runTest {
        val f = Fixture()
        f.store.writeSession(TOKENS, USER)
        f.store.writePending("verifier", "state", "redirect", "client")
        f.repository.restore()

        f.repository.signOut()

        assertEquals(SessionState.SignedOut(SignedOutReason.UserSignedOut), f.repository.state.value)
        assertNull(f.store.readSession())
        assertNull(f.store.readPending())
    }

    @Test
    fun restoring_emits_restoring_first_and_then_exactly_one_settled_state() = runTest {
        val f = Fixture()
        f.store.writeSession(TOKENS, USER)
        val seen = mutableListOf<SessionState>()

        seen += f.repository.state.value
        f.repository.restore()
        seen += f.repository.state.value

        assertEquals(listOf(SessionState.Restoring, SessionState.SignedIn(USER)), seen)
    }

    @Test
    fun neither_settled_state_carries_a_token() = runTest {
        // Tokens live in the store and are read by the bearer provider. Keeping them out of the
        // state object keeps them out of Compose snapshots and out of every toString(), and stops
        // them drifting from what the HTTP client actually sends.
        //
        // The code verifier *is* in `Authorizing`, deliberately — it is what the eventual token
        // exchange needs, and under `plain` PKCE it is already in the authorization URL the UI
        // shows. Tokens have no such excuse.
        val signedIn = Fixture().apply { store.writeSession(TOKENS, USER); repository.restore() }
        val authorizing = Fixture().apply {
            store.writeSession(TOKENS, USER)
            store.writePending("verifier", "state", "redirect", "client")
            repository.restore()
        }

        assertIs<SessionState.SignedIn>(signedIn.repository.state.value)
        assertIs<SessionState.Authorizing>(authorizing.repository.state.value)
        for (state in listOf(signedIn.repository.state.value, authorizing.repository.state.value)) {
            val rendered = state.toString()
            assertTrue("access-1" !in rendered, rendered)
            assertTrue("refresh-1" !in rendered, rendered)
        }
    }

    @Test
    fun signed_out_reasons_are_distinguishable_so_the_ui_can_explain_itself() {
        assertTrue(
            SessionState.SignedOut(SignedOutReason.RefreshRejected) !=
                SessionState.SignedOut(SignedOutReason.NeverSignedIn),
        )
    }
}
