package io.challenge_workshop.mal_ui.mal

import kotlinx.browser.window

internal actual fun browserOrigin(): String = window.location.origin
