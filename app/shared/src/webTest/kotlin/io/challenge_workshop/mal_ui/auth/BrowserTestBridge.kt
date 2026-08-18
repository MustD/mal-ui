@file:OptIn(ExperimentalWasmJsInterop::class)

package io.challenge_workshop.mal_ui.auth

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The browser calls the web tests drive, in one place because they run under both `jsTest` and
 * `wasmJsTest` and were otherwise copied per file.
 *
 * These are the test-side counterparts of `PopupBridge.kt`: same `js()` constraints, same reason
 * they live in `webMain`/`webTest` rather than a per-target source set.
 */

/**
 * Stands in for the popup handle.
 *
 * Karma has no user activation, so a real `window.open` is blocked and would leave the channel with
 * nothing to compare `event.source` against. Passing this window instead makes a `postMessage` from
 * a test *look* like one from the popup — which is the case the checks have to let through.
 */
internal fun thisWindow(): JsAny = js("window")

/** A same-origin window that is not this one, so `event.source` can be wrong without being absent. */
internal fun aDifferentWindow(): JsAny = js(
    """{
        var frame = document.createElement('iframe');
        frame.style.display = 'none';
        document.body.appendChild(frame);
        return frame.contentWindow;
    }""",
)

/** Delivers a message whose `event.source` is this window and whose `event.origin` is the real one. */
internal fun postToSelf(data: String): Unit = js("{ window.postMessage(data, window.location.origin); }")

internal fun currentPath(): String = js("window.location.pathname")

internal fun replaceUrl(url: String): Unit = js("window.history.replaceState(null, '', url)")
