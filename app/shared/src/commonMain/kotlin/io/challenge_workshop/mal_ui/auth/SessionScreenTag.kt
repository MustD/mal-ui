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
