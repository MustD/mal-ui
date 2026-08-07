@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.challenge_workshop.mal_ui.session

/**
 * Presence is probed separately from the value because a `js()` function's return type has to be
 * a type that crosses the Wasm boundary directly; `String` does, and a null-vs-empty distinction
 * inside one call would not. Two `sessionStorage` reads cost nothing at this volume, and the
 * alternative — treating `""` as absent — would silently swallow a legitimately empty value.
 */
private fun sessionStorageHas(key: String): Boolean = js("window.sessionStorage.getItem(key) !== null")

private fun sessionStorageGet(key: String): String = js("window.sessionStorage.getItem(key)")

private fun sessionStorageSet(key: String, value: String) {
    js("window.sessionStorage.setItem(key, value)")
}

private fun sessionStorageRemove(key: String) {
    js("window.sessionStorage.removeItem(key)")
}

/**
 * Web [KeyValueStore]: `sessionStorage`, one implementation shared by the `js` and `wasmJs`
 * targets.
 *
 * The whole Session — refresh token included — lives here, deliberately and against the letter of
 * `draft-ietf-oauth-browser-based-apps`. See `docs/adr/0001-refresh-token-in-web-session-storage.md`:
 * in-memory storage would make every reload a fresh login and would make the full-page-redirect
 * fallback impossible, because the code verifier has to survive a document that is destroyed by
 * design. `localStorage` is never an option — it is shared across tabs, so two tabs could race
 * each other's refresh.
 *
 * **A popup gets its own copy of this storage, not a shared view.** So the popup document must
 * never read or clear the Pending Authorization: its clear would not reach the opener.
 */
class SessionStorageKeyValueStore(private val namespace: String) : KeyValueStore {

    private fun scoped(key: String) = "$namespace.$key"

    override suspend fun read(key: String): String? =
        scoped(key).let { if (sessionStorageHas(it)) sessionStorageGet(it) else null }

    override suspend fun write(key: String, value: String) = sessionStorageSet(scoped(key), value)

    override suspend fun remove(key: String) = sessionStorageRemove(scoped(key))
}
