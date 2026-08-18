@file:OptIn(ExperimentalWasmJsInterop::class)

package io.challenge_workshop.mal_ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.js.ExperimentalWasmJsInterop

/**
 * One actual for both browser targets: [PopupRedirectChannel] has nothing target-specific in it, so
 * it lives in `webMain` and compiles once for `js` and `wasmJs`.
 *
 * Remembered rather than constructed per recomposition, because it holds the attempt in flight —
 * the message listener and the popup handle. A fresh channel between `arm` and `await` would leave
 * the real listener installed with nothing left holding it.
 *
 * Unlike the other two targets there is no browser to hand it: `window.open` *is* the browser, and
 * it has to happen inside [AuthRedirectChannel.open] to stay within the click's user activation.
 */
@Composable
actual fun rememberAuthRedirectChannel(): AuthRedirectChannel = remember { PopupRedirectChannel() }
