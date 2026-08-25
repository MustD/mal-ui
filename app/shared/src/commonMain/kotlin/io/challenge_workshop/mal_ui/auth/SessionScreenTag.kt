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
 * The first-page placeholder: the Anime List's own shape, drawn with nothing in it.
 *
 * Its own tag because "is the skeleton on screen" is the one question that cannot be asked of the
 * pager's state — `loadingFirstPage` says a request is in flight, not that the screen chose the
 * skeleton over a spinner, and the difference between the two is the whole of the state.
 */
val ANIME_LIST_SKELETON_TAG: String = "$ANIME_LIST_TAG.skeleton"

/**
 * The message shown when the Anime List has nothing in it.
 *
 * One tag for both of the empty states rather than two, because what a test needs to assert is
 * exactly the thing that separates them — the *words* — and a second tag would let the two branches
 * be asserted without ever comparing their copy. Collapsing "your list is empty" into "this filter
 * matched nothing" is the easiest mistake in this feature.
 */
val ANIME_LIST_EMPTY_TAG: String = "$ANIME_LIST_TAG.empty"

/**
 * The full-width error shown when the *first* page failed — nothing loaded, so the error is the
 * screen.
 *
 * Distinct from [ANIME_LIST_MORE_TAG], which is the other failure: entries on screen, retry at the
 * bottom, nothing discarded. Two tags because a test that could not tell them apart is the same test
 * the pager keeps two error fields to make possible.
 */
val ANIME_LIST_ERROR_TAG: String = "$ANIME_LIST_TAG.error"

/**
 * The Watch Status filter row above the Anime List.
 *
 * Sibling of the list rather than an item in it: the filter is a control over the list and has to
 * stay reachable from wherever the user has scrolled to, and a control that scrolls away from a list
 * fifty entries at a time is one the user cannot get back to.
 */
val ANIME_LIST_FILTERS_TAG: String = "${SessionScreenTag.SignedIn.tag}.filters"

/**
 * The Sort Order control above the Anime List — the button that names the current ordering.
 *
 * A sibling of the list for the same reason the filter row is: it is a control over the list, and
 * one that scrolls away from it fifty entries at a time is one the user cannot get back to.
 */
val ANIME_LIST_SORT_TAG: String = "${SessionScreenTag.SignedIn.tag}.sort"

/**
 * The Sort Order menu itself, once opened.
 *
 * Its own tag because the thing worth asserting about it is *how many* entries it has: MAL offers
 * four orderings and no way to reverse any of them, and a fifth entry — a "Reverse" toggle most of
 * all — is a well-meant addition that could only reverse the pages already loaded. See ADR-0003.
 */
val ANIME_LIST_SORT_MENU_TAG: String = "$ANIME_LIST_SORT_TAG.menu"
