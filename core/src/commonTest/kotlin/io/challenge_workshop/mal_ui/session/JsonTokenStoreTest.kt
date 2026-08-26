package io.challenge_workshop.mal_ui.session

import io.challenge_workshop.mal_ui.animelist.AnimeListLayout
import io.challenge_workshop.mal_ui.mal.MalTokens
import io.challenge_workshop.mal_ui.mal.MalUser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TOKENS = MalTokens(
    tokenType = "Bearer",
    expiresIn = 2_415_600,
    accessToken = "access-1",
    refreshToken = "refresh-1",
)

private val USER = MalUser(id = 42, name = "someone", joinedAt = "2020-01-01T00:00:00+00:00")

class JsonTokenStoreTest {

    private fun store(kv: FakeKeyValueStore, clock: FakeClock = FakeClock()) = JsonTokenStore(kv, clock = clock)

    @Test
    fun a_session_round_trips_unchanged() = runTest {
        val kv = FakeKeyValueStore()
        val clock = FakeClock()
        val store = store(kv, clock)

        val written = store.writeSession(TOKENS, USER)

        assertEquals(TOKENS, written.tokens)
        assertEquals(USER, written.user)
        assertEquals(clock.current.toEpochMilliseconds(), written.obtainedAtEpochMs)
        assertEquals(written, store.readSession())
    }

    @Test
    fun a_session_with_no_cached_user_round_trips() = runTest {
        val store = store(FakeKeyValueStore())

        store.writeSession(TOKENS, user = null)

        assertNull(store.readSession()?.user)
        assertEquals(TOKENS, store.readSession()?.tokens)
    }

    @Test
    fun a_pending_authorization_round_trips_unchanged() = runTest {
        val clock = FakeClock()
        val store = store(FakeKeyValueStore(), clock)

        val written = store.writePending(
            codeVerifier = "verifier",
            state = "state",
            redirectUri = "http://127.0.0.1:18040/oauth/callback",
            clientId = "client",
        )

        assertEquals("verifier", written.codeVerifier)
        assertEquals("state", written.state)
        assertEquals("http://127.0.0.1:18040/oauth/callback", written.redirectUri)
        assertEquals("client", written.clientId)
        assertEquals(clock.current.toEpochMilliseconds(), written.startedAtEpochMs)
        assertEquals(written, store.readPending())
    }

    @Test
    fun an_empty_store_reads_as_absent() = runTest {
        val store = store(FakeKeyValueStore())

        assertNull(store.readSession())
        assertNull(store.readPending())
    }

    @Test
    fun a_corrupt_session_blob_is_treated_as_absent_and_removed() = runTest {
        val kv = FakeKeyValueStore(mutableMapOf(JsonTokenStore.SESSION_KEY to "{not json"))
        val store = store(kv)

        assertNull(store.readSession())
        // Self-healing: leaving the bad value would make every subsequent launch re-read it.
        assertTrue(JsonTokenStore.SESSION_KEY !in kv.entries)
    }

    @Test
    fun a_session_blob_missing_a_required_field_is_treated_as_absent() = runTest {
        val kv = FakeKeyValueStore(mutableMapOf(JsonTokenStore.SESSION_KEY to """{"user":null}"""))

        assertNull(store(kv).readSession())
    }

    @Test
    fun a_corrupt_pending_blob_is_treated_as_absent_and_removed() = runTest {
        val kv = FakeKeyValueStore(mutableMapOf(JsonTokenStore.PENDING_KEY to "garbage"))
        val store = store(kv)

        assertNull(store.readPending())
        assertTrue(JsonTokenStore.PENDING_KEY !in kv.entries)
    }

    @Test
    fun clearing_the_session_leaves_a_subsequent_read_null() = runTest {
        val kv = FakeKeyValueStore()
        val store = store(kv)
        store.writeSession(TOKENS, USER)

        store.clearSession()

        assertNull(store.readSession())
    }

    @Test
    fun clearing_the_pending_authorization_leaves_a_subsequent_read_null() = runTest {
        val store = store(FakeKeyValueStore())
        store.writePending("v", "s", "r", "c")

        store.clearPending()

        assertNull(store.readPending())
    }

    @Test
    fun clear_removes_both_records() = runTest {
        val kv = FakeKeyValueStore()
        val store = store(kv)
        store.writeSession(TOKENS, USER)
        store.writePending("v", "s", "r", "c")

        store.clear()

        assertNull(store.readSession())
        assertNull(store.readPending())
        assertTrue(kv.entries.isEmpty())
    }

    @Test
    fun a_differently_versioned_key_is_not_read() = runTest {
        // A v0 blob from a hypothetical earlier format must read as absent — a clean
        // re-login rather than a deserialization crash loop.
        val kv = FakeKeyValueStore(
            mutableMapOf(
                "mal.session.v0" to """{"tokens":{"token_type":"Bearer"},"obtainedAtEpochMs":0}""",
                "mal.pending.v0" to """{"codeVerifier":"v"}""",
            ),
        )
        val store = store(kv)

        assertNull(store.readSession())
        assertNull(store.readPending())
        // ...and reading did not delete somebody else's key.
        assertTrue("mal.session.v0" in kv.entries)
    }

    @Test
    fun the_keys_are_version_stamped() {
        assertEquals("mal.session.v1", JsonTokenStore.SESSION_KEY)
        assertEquals("mal.pending.v1", JsonTokenStore.PENDING_KEY)
        assertEquals("mal.clientId.v1", JsonTokenStore.CLIENT_ID_KEY)
        assertEquals("mal.layout.v1", JsonTokenStore.LAYOUT_KEY)
    }

    @Test
    fun updating_tokens_keeps_the_cached_user_and_restamps_obtained_at() = runTest {
        val clock = FakeClock()
        val store = store(FakeKeyValueStore(), clock)
        store.writeSession(TOKENS, USER)
        clock.advanceBy(60_000)

        val refreshed = TOKENS.copy(accessToken = "access-2", refreshToken = "refresh-2")
        val updated = store.updateTokens(refreshed)

        assertEquals(refreshed, updated?.tokens)
        assertEquals(USER, updated?.user)
        assertEquals(clock.current.toEpochMilliseconds(), updated?.obtainedAtEpochMs)
        assertEquals(updated, store.readSession())
    }

    @Test
    fun updating_tokens_with_no_session_stored_writes_nothing() = runTest {
        val kv = FakeKeyValueStore()

        assertNull(store(kv).updateTokens(TOKENS))
        assertTrue(kv.entries.isEmpty())
    }

    @Test
    fun updating_the_user_keeps_the_tokens_and_the_obtained_at_stamp() = runTest {
        val clock = FakeClock()
        val store = store(FakeKeyValueStore(), clock)
        val original = store.writeSession(TOKENS, user = null)
        clock.advanceBy(60_000)

        val updated = store.updateUser(USER)

        assertEquals(USER, updated?.user)
        assertEquals(TOKENS, updated?.tokens)
        assertEquals(original.obtainedAtEpochMs, updated?.obtainedAtEpochMs)
    }

    @Test
    fun nothing_stored_ever_serializes_under_a_key_that_looks_unversioned() = runTest {
        val kv = FakeKeyValueStore()
        val store = store(kv)
        store.writeSession(TOKENS, USER)
        store.writePending("v", "s", "r", "c")
        store.writeClientId("a-client-id")
        store.writeLayout(AnimeListLayout.List)

        assertTrue(kv.entries.keys.all { it.endsWith(".v1") }, "keys were ${kv.entries.keys}")
    }

    @Test
    fun a_client_id_round_trips_unchanged() = runTest {
        val store = store(FakeKeyValueStore())

        store.writeClientId("a-client-id")

        assertEquals("a-client-id", store.readClientId())
    }

    @Test
    fun a_blank_client_id_is_written_as_absent() = runTest {
        val kv = FakeKeyValueStore()
        val store = store(kv)
        store.writeClientId("a-client-id")

        // Clearing the field is how a user drops a remembered Client ID, and an empty string
        // remembered as a value would then beat the build-time default forever.
        store.writeClientId("   ")

        assertNull(store.readClientId())
        assertTrue(JsonTokenStore.CLIENT_ID_KEY !in kv.entries)
    }

    @Test
    fun a_blank_stored_client_id_reads_as_absent() = runTest {
        // Nothing this store writes can be blank, but a hand-edited desktop file or an older build
        // could leave one, and a blank that read as a value would beat the build-time default.
        val kv = FakeKeyValueStore(mutableMapOf(JsonTokenStore.CLIENT_ID_KEY to "\"  \""))

        assertNull(store(kv).readClientId())
    }

    @Test
    fun a_corrupt_client_id_blob_is_treated_as_absent_and_removed() = runTest {
        val kv = FakeKeyValueStore(mutableMapOf(JsonTokenStore.CLIENT_ID_KEY to "{not json"))
        val store = store(kv)

        assertNull(store.readClientId())
        assertTrue(JsonTokenStore.CLIENT_ID_KEY !in kv.entries)
    }

    @Test
    fun clear_keeps_the_client_id() = runTest {
        val kv = FakeKeyValueStore()
        val store = store(kv)
        store.writeSession(TOKENS, USER)
        store.writePending("v", "s", "r", "c")
        store.writeClientId("a-client-id")

        store.clear()

        // The Client ID identifies the *app*, not the user: signing out, or a rejected refresh,
        // must not make the next sign-in a retyping exercise.
        assertEquals("a-client-id", store.readClientId())
    }

    @Test
    fun a_layout_round_trips_unchanged() = runTest {
        val store = store(FakeKeyValueStore())

        store.writeLayout(AnimeListLayout.List)

        assertEquals(AnimeListLayout.List, store.readLayout())
    }

    @Test
    fun a_missing_layout_record_reads_as_the_default() = runTest {
        // A first launch has no record, and a Layout is a preference rather than a credential:
        // there is nothing to fail about, so the answer is the Layout the app ships with.
        assertEquals(AnimeListLayout.Cards, store(FakeKeyValueStore()).readLayout())
    }

    @Test
    fun a_corrupt_layout_blob_reads_as_the_default_and_is_removed() = runTest {
        val kv = FakeKeyValueStore(mutableMapOf(JsonTokenStore.LAYOUT_KEY to "{not json"))
        val store = store(kv)

        assertEquals(AnimeListLayout.Cards, store.readLayout())
        assertTrue(JsonTokenStore.LAYOUT_KEY !in kv.entries)
    }

    @Test
    fun a_layout_this_build_does_not_know_reads_as_the_default() = runTest {
        // A record written by a later build that offers a third Layout. An unknown enum value is a
        // `SerializationException`, and a preference is never worth a crash loop over.
        val kv = FakeKeyValueStore(mutableMapOf(JsonTokenStore.LAYOUT_KEY to "\"Mosaic\""))

        assertEquals(AnimeListLayout.Cards, store(kv).readLayout())
    }

    @Test
    fun clear_keeps_the_layout() = runTest {
        val store = store(FakeKeyValueStore())
        store.writeSession(TOKENS, USER)
        store.writeLayout(AnimeListLayout.List)

        store.clear()

        // The same rule as the Client ID: a Layout is a device preference, not a credential, so
        // signing out must not reset how the next user of this device reads their list.
        assertNull(store.readSession())
        assertEquals(AnimeListLayout.List, store.readLayout())
    }
}
