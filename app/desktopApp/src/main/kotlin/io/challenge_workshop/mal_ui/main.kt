package io.challenge_workshop.mal_ui

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.challenge_workshop.mal_ui.di.initKoin

fun main() {
    initKoin()
    ui()
}

private fun ui() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "mal_ui",
    ) {
        App()
    }
}