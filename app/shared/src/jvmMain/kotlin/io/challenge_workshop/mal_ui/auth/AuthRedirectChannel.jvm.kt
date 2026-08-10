package io.challenge_workshop.mal_ui.auth

import androidx.compose.runtime.Composable

/** Ticket 11 replaces this with the loopback listener on 18040. */
@Composable
actual fun rememberAuthRedirectChannel(): AuthRedirectChannel = PasteOnlyRedirectChannel
