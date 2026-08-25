package io.challenge_workshop.mal_ui.auth

/**
 * The four destinations of the `when` in [io.challenge_workshop.mal_ui.App], named so a test can ask
 * which one is on screen.
 *
 * These live in `commonMain` rather than in the test source set on purpose: they are the contract
 * between the routing `when` and the test that asserts it is total. One enum entry per
 * [io.challenge_workshop.mal_ui.session.SessionState] subtype, which is what lets the test compare
 * its own coverage against the sealed interface instead of trusting a hand-written list.
 */
enum class SessionScreenTag {
    Restoring,
    SignIn,
    Authorizing,
    SignedIn,
    ;

    val tag: String get() = "sessionScreen.$name"
}

/**
 * The [io.challenge_workshop.mal_ui.session.SignedOutReason] explanation on the sign-in screen.
 *
 * Tagged separately because the thing worth asserting about it is its *text* — the whole point of
 * `SignedOutReason` is that an expired session does not read like a deliberate sign-out — and there
 * is no other way to pick one paragraph out of a screen without pinning the test to its copy.
 */
val SIGNED_OUT_REASON_TAG: String = "${SessionScreenTag.SignIn.tag}.reason"

/**
 * The Anime List on the signed-in screen — the lazy list itself, which is also the signed-in
 * screen's one scroll container.
 *
 * Derived from [SessionScreenTag.SignedIn] rather than being a scheme of its own: the thing worth
 * asserting is that the signed-in branch renders the list, so the two names should not be able to
 * drift apart.
 *
 * On the *scroll container* rather than on a wrapper, so a test can scroll it. Paging is triggered
 * by proximity to the end, so a test that cannot scroll cannot reach the behaviour at all.
 */
val ANIME_LIST_TAG: String = "${SessionScreenTag.SignedIn.tag}.animeList"

/**
 * The row at the bottom of the Anime List that says whether more is coming.
 *
 * Its own tag because it is the only part of the list that is about *paging* rather than about an
 * entry, and both of the things it can say — loading more, and the retry after a failed later page
 * — are invisible in an assertion over entries.
 */
val ANIME_LIST_MORE_TAG: String = "$ANIME_LIST_TAG.more"

/**
 * The Watch Status filter row above the Anime List.
 *
 * Sibling of the list rather than an item in it: the filter is a control over the list and has to
 * stay reachable from wherever the user has scrolled to, and a control that scrolls away from a list
 * fifty entries at a time is one the user cannot get back to.
 */
val ANIME_LIST_FILTERS_TAG: String = "${SessionScreenTag.SignedIn.tag}.filters"
