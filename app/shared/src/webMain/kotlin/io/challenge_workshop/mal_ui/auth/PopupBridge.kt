@file:OptIn(ExperimentalWasmJsInterop::class)

package io.challenge_workshop.mal_ui.auth

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The browser calls the popup Redirect Capture makes, written once for the `js` and the `wasmJs`
 * target.
 *
 * All of it goes through `js()` rather than `kotlin-browser`'s typed `web.*` API, for the same
 * reason [currentSearch] does: the stdlib declares `js()` as an `expect` returning `Nothing` in its
 * own shared web source set, so one `webMain` body compiles for both targets and `String`,
 * `Boolean` and `JsAny` all cross the boundary directly. `js()` constrains the shape — the call must
 * be the whole body, the argument a compile-time constant, and the function package-level with an
 * explicit return type — but it can refer to the enclosing function's parameters, including
 * function-typed ones.
 *
 * A window handle stays a [JsAny] and is never unwrapped. Nothing here needs a `Window` type; what
 * it needs is *identity*, and that is [isSameJsObject], done with JS `===` rather than Kotlin `==`
 * because a Wasm `externref` is free to arrive as a different Kotlin wrapper for the same object.
 */

/**
 * The name the sign-in popup is opened under, and the only reliable way that document can later
 * recognise *itself* as the popup.
 *
 * It survives the round trip. Chrome clears `window.name` when a browsing context navigates
 * cross-origin — so it is empty while the user is on myanimelist.net — and **restores it** on
 * returning to the origin that set it, which is exactly the shape of this flow. Verified in a real
 * browser, reading it from inside the popup's own document after the hop, because reasoning about
 * that behaviour from the spec alone is not enough to hang the fallback on.
 */
internal const val SIGN_IN_POPUP_NAME: String = "mal_ui_sign_in"

/**
 * Opens the sign-in popup, or returns null when the browser blocked it.
 *
 * Null is not an error: it is the documented trigger for the full-page-redirect fallback, and it is
 * what a lost user activation looks like from here.
 *
 * Called synchronously from [AuthRedirectChannel.open] and never from a `launch { }`: user
 * activation is a timestamp window, `window.open` consumes it, and WebKit caps gesture forwarding
 * at one second.
 *
 * [name] is a parameter rather than a literal in the JS because `js()` takes a compile-time
 * constant, and the name has to be the *same* string [SIGN_IN_POPUP_NAME] is checked against.
 */
internal fun openSignInPopup(url: String, name: String = SIGN_IN_POPUP_NAME): JsAny? =
    js("window.open(url, name, 'popup=yes,width=560,height=760')")

/** What this document was opened as. Empty for a tab the user opened themselves. */
internal fun currentWindowName(): String = js("window.name")

/**
 * Sends *this* document to MyAnimeList, destroying it.
 *
 * `assign` rather than `replace`, so Back returns to the app rather than skipping past it. The
 * Pending Authorization in `sessionStorage` is what makes the return survivable — nothing in memory
 * outlives this call.
 */
internal fun navigateTo(url: String): Unit = js("{ window.location.assign(url); }")

/**
 * Listens for the popup's `postMessage`, handing Kotlin the three things a decision needs.
 *
 * The listener function is returned so it can be removed again by reference: an armed channel that
 * left its listener behind would still be holding a credential-shaped callback after the sign-in it
 * belonged to had ended.
 */
internal fun addRedirectMessageListener(
    onMessage: (data: String, source: JsAny?, origin: String) -> Unit,
): JsAny = js(
    """{
        var listener = function (e) { onMessage(String(e.data), e.source, String(e.origin)); };
        window.addEventListener('message', listener);
        return listener;
    }""",
)

internal fun removeRedirectMessageListener(listener: JsAny): Unit =
    js("{ window.removeEventListener('message', listener); }")

/** JS reference identity. See the file comment for why this is not Kotlin `==`. */
internal fun isSameJsObject(a: JsAny?, b: JsAny?): Boolean = js("a === b")

/**
 * Whether this document was opened by another one — the popup half of the flow.
 *
 * `window.opener !== window` as well, because a document can be its own opener after a
 * `window.open(url, '_self')`, and that is not a popup.
 */
internal fun hasOpener(): Boolean = js("{ return !!window.opener && window.opener !== window; }")

/** Cross-origin-safe by construction: [targetOrigin] is checked by the browser before delivery. */
internal fun postToOpener(message: String, targetOrigin: String): Unit =
    js("{ window.opener.postMessage(message, targetOrigin); }")

/** Allowed without a prompt: a script may close a window that a script opened. */
internal fun closeSelf(): Unit = js("{ window.close(); }")

/** True for a window the user has closed, and for no handle at all. */
internal fun isWindowClosed(handle: JsAny?): Boolean = js("{ return !handle || handle.closed === true; }")

internal fun closeWindow(handle: JsAny?): Unit = js("{ if (handle && !handle.closed) handle.close(); }")
