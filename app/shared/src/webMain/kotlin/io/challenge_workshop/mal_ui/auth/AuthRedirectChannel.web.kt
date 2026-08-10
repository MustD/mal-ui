package io.challenge_workshop.mal_ui.auth

import androidx.compose.runtime.Composable

/**
 * One actual for both browser targets, in `webMain`: ticket 13's popup-and-`postMessage` channel has
 * nothing target-specific in it either.
 */
@Composable
actual fun rememberAuthRedirectChannel(): AuthRedirectChannel = PasteOnlyRedirectChannel
