package io.challenge_workshop.mal_ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.challenge_workshop.mal_ui.di.initKoin

/** One entry point serving both the `js` and `wasmJs` targets. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initKoin()
    ComposeViewport {
        App()
    }
}