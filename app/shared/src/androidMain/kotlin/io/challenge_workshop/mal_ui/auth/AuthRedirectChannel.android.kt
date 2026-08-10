package io.challenge_workshop.mal_ui.auth

import androidx.compose.runtime.Composable

/** Ticket 16 replaces this with `AuthTabIntent` raced against ticket 15's intent filter. */
@Composable
actual fun rememberAuthRedirectChannel(): AuthRedirectChannel = PasteOnlyRedirectChannel
