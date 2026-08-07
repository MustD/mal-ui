package io.challenge_workshop.mal_ui.session

/**
 * The narrowest thing a Session can be persisted through: three suspending string operations.
 *
 * Deliberately hand-rolled rather than a multiplatform settings library — see
 * `docs/adr/0002-hand-rolled-key-value-store.md`. The implementations live in `:app:shared`,
 * not here, because they do filesystem I/O and `:server` depends on `:core` as a plain JVM
 * library; an `expect` here would force a desktop-shaped actual onto a Netty process.
 *
 * Narrow enough that an OS-keychain implementation could be swapped in without touching a
 * caller. Nothing is encrypted at rest on any target today.
 */
interface KeyValueStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
}
