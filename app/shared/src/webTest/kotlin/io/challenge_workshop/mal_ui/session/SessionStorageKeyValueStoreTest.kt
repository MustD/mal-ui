package io.challenge_workshop.mal_ui.session

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Runs under both `jsTest` and `wasmJsTest`, in a real browser, against real `sessionStorage`. */
class SessionStorageKeyValueStoreTest {

    @Test
    fun satisfies_the_key_value_store_contract() = runTest {
        assertKeyValueStoreRoundTrip(SessionStorageKeyValueStore("contract-test"))
    }

    @Test
    fun a_stored_empty_string_is_distinguishable_from_an_absent_key() = runTest {
        val store = SessionStorageKeyValueStore("empty-test")

        store.write("k", "")

        assertEquals("", store.read("k"))
        assertNull(store.read("absent"))
    }

    @Test
    fun namespaces_do_not_see_each_others_keys() = runTest {
        SessionStorageKeyValueStore("ns-a").write("shared", "a")
        SessionStorageKeyValueStore("ns-b").write("shared", "b")

        assertEquals("a", SessionStorageKeyValueStore("ns-a").read("shared"))
        assertEquals("b", SessionStorageKeyValueStore("ns-b").read("shared"))
    }
}
