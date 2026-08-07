package io.challenge_workshop.mal_ui.session

import kotlin.time.Clock
import kotlin.time.Instant

/** In-memory [KeyValueStore], with the raw map exposed so tests can plant corrupt or stale values. */
class FakeKeyValueStore(
    val entries: MutableMap<String, String> = mutableMapOf(),
) : KeyValueStore {
    override suspend fun read(key: String): String? = entries[key]

    override suspend fun write(key: String, value: String) {
        entries[key] = value
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }
}

/** A [Clock] that only moves when a test moves it. */
class FakeClock(var current: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)) : Clock {
    override fun now(): Instant = current

    fun advanceBy(millis: Long) {
        current = Instant.fromEpochMilliseconds(current.toEpochMilliseconds() + millis)
    }
}
