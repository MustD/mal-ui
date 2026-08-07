package io.challenge_workshop.mal_ui.session

import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The write-read-remove round trip every [KeyValueStore] actual has to satisfy, asserted once so
 * the four target-specific tests cannot drift into testing different things.
 *
 * There is one contract test per target rather than a single common one because the whole point of
 * these implementations is the platform API underneath them — `SharedPreferences`, a `0600` file,
 * `sessionStorage`. A common test would exercise none of that.
 */
suspend fun assertKeyValueStoreRoundTrip(store: KeyValueStore) {
    val key = "contract.probe.v1"
    val other = "contract.other.v1"

    assertNull(store.read(key), "a key that was never written must read as null")

    store.write(key, """{"a":1}""")
    assertEquals("""{"a":1}""", store.read(key))

    store.write(key, """{"a":2}""")
    assertEquals("""{"a":2}""", store.read(key), "a second write must replace the first")

    store.write(other, "untouched")
    store.remove(key)
    assertNull(store.read(key), "remove must make a subsequent read null")
    assertEquals("untouched", store.read(other), "remove must not touch a neighbouring key")

    store.remove(other)
    store.remove(other) // removing an absent key is not an error
}
