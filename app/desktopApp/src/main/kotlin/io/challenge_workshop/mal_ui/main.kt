package io.challenge_workshop.mal_ui

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "mal_ui",
    ) {
        App()
    }
}