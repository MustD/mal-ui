package io.challenge_workshop.mal_ui.mal

/**
 * Read directly rather than via `kotlinx.browser`, which the Wasm stdlib does not carry —
 * this avoids adding a dependency just to read one string.
 */
private fun currentOrigin(): String = js("window.location.origin")

internal actual fun browserOrigin(): String = currentOrigin()
