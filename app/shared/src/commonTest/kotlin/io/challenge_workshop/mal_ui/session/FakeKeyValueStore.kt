package io.challenge_workshop.mal_ui.session

/**
 * In-memory [KeyValueStore] for the `:app:shared` tests.
 *
 * A near-copy of `:core`'s `FakeKeyValueStore` and not a reuse of it: test source sets are not
 * published, so nothing in `:core:commonTest` is on this module's classpath. The two are allowed to
 * drift — this one exists to give a [MalSessionRepository] somewhere to write, while `:core`'s also
 * exposes its map so tests can plant corrupt values.
 */
class FakeKeyValueStore : KeyValueStore {
    private val entries = mutableMapOf<String, String>()

    override suspend fun read(key: String): String? = entries[key]

    override suspend fun write(key: String, value: String) {
        entries[key] = value
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }
}
