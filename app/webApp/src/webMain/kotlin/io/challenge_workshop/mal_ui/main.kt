package io.challenge_workshop.mal_ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.challenge_workshop.mal_ui.auth.relaySignInRedirectToOpener
import io.challenge_workshop.mal_ui.di.initKoin

/** One entry point serving both the `js` and `wasmJs` targets. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Before Koin and before Compose, and both halves of that matter. The sign-in popup lands on
    // the same callback route the app is served from, so it boots this same `main` — and a
    // `window.open`ed document gets its **own copy** of `sessionStorage`, not a shared view. An app
    // started here would read a Pending Authorization the opener still owns and clear a record the
    // opener would never see cleared. Its only job is to hand the address back and close.
    if (relaySignInRedirectToOpener()) return

    initKoin()
    ComposeViewport {
        App()
    }
}
