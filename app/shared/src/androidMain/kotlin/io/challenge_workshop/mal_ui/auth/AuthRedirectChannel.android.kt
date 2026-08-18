package io.challenge_workshop.mal_ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Android captures the redirect through the manifest's custom-scheme filter; see
 * [IntentRedirectChannel].
 *
 * Ticket 16 adds `AuthTabIntent` in front of this and races the two — Auth Tab needs Chrome 137+ and
 * degrades silently on everything else, so the intent filter stays as the fallback rather than being
 * replaced.
 *
 * The lifecycle is the hosting Activity's, which is the only thing here that has to be: the inbox is
 * process-scoped precisely because a redirect can outlive any composition, and the channel is
 * remembered above the `SessionState` `when` so that starting a sign-in does not take it out of
 * composition mid-flow.
 */
@Composable
actual fun rememberAuthRedirectChannel(): AuthRedirectChannel {
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return remember(uriHandler, lifecycle) {
        IntentRedirectChannel(
            inbox = AuthRedirectInbox.Shared,
            lifecycleStates = lifecycle.currentStateFlow,
            launchBrowser = uriHandler::openUri,
        )
    }
}
