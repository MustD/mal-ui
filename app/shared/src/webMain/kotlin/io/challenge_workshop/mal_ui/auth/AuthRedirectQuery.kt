@file:OptIn(ExperimentalWasmJsInterop::class)

package io.challenge_workshop.mal_ui.auth

import io.ktor.http.parseQueryString
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Reading and clearing the `?code=…` a Redirect Capture lands on in the browser.
 *
 * Both are written with `js()` in `webMain` rather than through `kotlinx.browser` so one
 * implementation serves the js and the wasmJs target: the stdlib declares `js()` as an `expect`
 * returning `Nothing` in its own shared web source set, so it satisfies any declared return type
 * and `String` crosses the boundary directly. Written *inside* `jsMain` these would bind to the
 * `dynamic` actual instead and stop being shareable.
 *
 * `js()` constrains what can be written here: the call must be the whole body, its argument must be
 * a compile-time constant, and the function must be package-level with an explicit return type. It
 * can, however, refer to the enclosing function's parameters.
 */

/**
 * The live query string, `?code=…&state=…` or `""`, straight off `window.location`.
 *
 * Live rather than captured at load, because the full-page-redirect path reads it during boot while
 * the popup path never reloads the document at all.
 */
internal fun currentSearch(): String = js("window.location.search")

/** This document's origin — scheme, host and port, the exact string `postMessage` compares. */
internal fun currentOrigin(): String = js("window.location.origin")

/** The whole address, which is what both Redirect Capture paths hand on unparsed. */
internal fun currentHref(): String = js("window.location.href")

/**
 * Whether [search] is a redirect from MyAnimeList rather than an ordinary visit.
 *
 * `error` counts: a denial has to reach the app as the same [io.challenge_workshop.mal_ui.mal.MalAuthException]
 * a pasted denial would, and a popup that stayed silent on one would leave its opener waiting.
 *
 * Deliberately not `parseRedirect` — that one throws to say "no code here", which is the right
 * answer for a paste and the wrong one for a page load that simply is not a redirect.
 */
internal fun carriesAuthRedirect(search: String): Boolean =
    parseQueryString(search.removePrefix("?")).let { it.contains("code") || it.contains("error") }

/**
 * Drops the query from the address bar, keeping the path and the fragment.
 *
 * Three reasons, all of them real:
 * - An authorization code is single-use, so a reload that still carried it would fail the exchange.
 * - Under `plain` PKCE the code *and* the verifier are both in that URL; leaving it in history
 *   leaves both there.
 * - The path has to survive, because the callback route is what boots the app.
 *
 * `replaceState`, never `pushState` — a new entry would keep exactly what this removes, and Back
 * would land on a spent code.
 */
internal fun clearAuthQuery(): Unit =
    js("window.history.replaceState(null, '', window.location.pathname + window.location.hash)")
