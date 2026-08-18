package io.challenge_workshop.mal_ui.auth

/**
 * What a sign-in popup does when MyAnimeList redirects it back: hand the whole address to its
 * opener and get out of the way.
 *
 * Called from the web entry point **before Koin and before Compose**, and that ordering is the
 * point. A `window.open`ed document gets its **own copy** of the opener's `sessionStorage`, not a
 * shared view — so a popup that booted the app would read a Pending Authorization the opener still
 * owns, exchange the code against its own copy, and clear a record that the opener would never see
 * cleared. Everything durable stays with the opener; this document only carries a string across.
 *
 * It is also why the popup is cheap despite serving the whole SPA bundle: nothing here starts it.
 *
 * @return true if this document was a sign-in popup and has relayed its redirect, in which case the
 * caller must not boot the app.
 */
fun relaySignInRedirectToOpener(): Boolean = relayRedirectToOpener(
    hasOpener = hasOpener(),
    windowName = currentWindowName(),
    search = currentSearch(),
    href = currentHref(),
    origin = currentOrigin(),
    postToOpener = ::postToOpener,
    closeSelf = ::closeSelf,
)

/**
 * The decision and the ordering, with the browser handed in.
 *
 * Three conditions, not one, and each rules out a document that would otherwise be mistaken for the
 * popup: [hasOpener] excludes the app's own launch, [windowName] excludes any other window that
 * happens to have an opener — including the app tab returning from the full-page-redirect fallback
 * — and [search] excludes an ordinary page load.
 *
 * Separate from [relaySignInRedirectToOpener] because a test document cannot be opened as a popup
 * and must not close itself, and because the two things worth pinning down here — that the whole
 * href goes across unparsed, and that [origin] is named explicitly rather than `*` — are both
 * observable from this side.
 */
internal fun relayRedirectToOpener(
    hasOpener: Boolean,
    windowName: String,
    search: String,
    href: String,
    origin: String,
    postToOpener: (message: String, targetOrigin: String) -> Unit,
    closeSelf: () -> Unit,
): Boolean {
    if (!hasOpener) return false
    // An opener is not enough, and getting this wrong breaks the fallback rather than the popup.
    // The full-page-redirect path lands the **app's own tab** on this same query, and that tab has
    // an opener whenever the user arrived through a `target="_blank"` link or another page's
    // `window.open` — relaying there would post the redirect at an unrelated window and then close
    // the app mid-sign-in. Only the window this app opened and named is carrying its code.
    if (windowName != SIGN_IN_POPUP_NAME) return false
    // An ordinary page load in a window that happens to have an opener is not a redirect, and
    // relaying one would close a window the user opened on purpose.
    if (!carriesAuthRedirect(search)) return false

    // The whole address, unparsed. `state` verification and the token exchange belong to the
    // opener, which is the only side holding the real Pending Authorization.
    //
    // [origin] and never `*`: the popup's final document is same-origin with its opener, because
    // each origin registers its own Redirect URI, so there is always a real origin to name — and an
    // authorization code is a credential.
    postToOpener(href, origin)
    closeSelf()
    return true
}
