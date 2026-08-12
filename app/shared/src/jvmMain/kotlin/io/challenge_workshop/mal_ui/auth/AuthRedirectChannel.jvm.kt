package io.challenge_workshop.mal_ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalUriHandler

/**
 * Desktop captures the redirect on a loopback listener; see [LoopbackRedirectListener].
 *
 * The browser is opened through `LocalUriHandler` rather than a hand-rolled `Desktop.browse()`.
 * `Desktop.Action.BROWSE` reports unsupported on plenty of Linux desktops — the JDK only advertises
 * it when GIO's default VFS claims the `http` scheme, and without `gvfs` installed GIO reports only
 * `file` and `resource` — while Compose Desktop's own handler falls back to `xdg-open`. A
 * hand-rolled `Desktop.browse()` would be strictly worse.
 *
 * The scope is the composition's, so a window that closes mid-sign-in takes the browser launch with
 * it. It is *not* how the listener is released: cancelling the coroutine in
 * [AuthRedirectChannel.await] is, which is what frees the port.
 */
@Composable
actual fun rememberAuthRedirectChannel(): AuthRedirectChannel {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    return remember(uriHandler, scope) {
        LoopbackRedirectListener(scope, uriHandler::openUri)
    }
}
