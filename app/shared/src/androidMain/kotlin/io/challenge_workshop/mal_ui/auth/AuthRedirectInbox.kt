package io.challenge_workshop.mal_ui.auth

import android.content.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

/**
 * The one hand-off from an Android `Intent` to whatever is waiting for a redirect.
 *
 * Named an inbox and not a relay: `Relay` is this project's word for the `/mal` routes on `:server`
 * (see `CONTEXT.md`), and nothing here forwards anything to MyAnimeList. This holds one redirect
 * until somebody takes it.
 *
 * A custom-scheme redirect does not arrive at a channel, a ViewModel or a composable. It arrives at
 * `MainActivity` — as a launch Intent on a cold start, or through `onNewIntent` on a warm one — and
 * `MainActivity` is the one part of this app that Compose, Koin and the session layer all sit
 * underneath. So it forwards to here and does nothing else, and the layers above take from here.
 *
 * **`replay = 1` is load-bearing.** `onNewIntent` runs before `onResume`, and on a cold start the
 * Intent is in hand before Koin has built a ViewModel — so a redirect is routinely delivered before
 * anything is collecting. Without a replay it would be dropped, and the symptom would be a sign-in
 * that hangs with no error anywhere.
 *
 * Process-scoped ([Shared]) rather than owned by anything, because the two things that take from it
 * have different lifetimes and neither one always exists: an armed [IntentRedirectChannel] on a warm
 * redirect, and [AndroidStartupRedirect] when the process was killed while the user was away and
 * there is no armed channel left to deliver to.
 */
class AuthRedirectInbox internal constructor() {

    /**
     * The newest redirect always wins and [deliver] can never fail to place one: it is called from
     * `onNewIntent`, on the main thread, where suspending is not an option and a dropped redirect
     * is a login that hangs. Nothing is lost by dropping an older one — a superseded authorization
     * code is worthless.
     */
    private val redirects = MutableSharedFlow<String>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Forwards the redirect [intent] carries, if it carries one, and empties it.
     *
     * Emptying is not tidiness. `MainActivity` calls `setIntent(intent)`, so this exact object is
     * what a later recreation is handed — and an authorization code is single-use, so a second
     * exchange of the same one fails and takes the Session down with it.
     *
     * @return whether there was a redirect in it. False for every ordinary launch.
     */
    fun deliver(intent: Intent): Boolean {
        val redirect = intent.data?.toString() ?: return false
        intent.data = null
        return redirects.tryEmit(redirect)
    }

    /**
     * Suspends until a redirect [isOurs] accepts arrives, and takes it.
     *
     * Filtered rather than taken outright because **any app on the device can fire this Intent**:
     * the manifest filter is exported and a private-use scheme is not owned. Treating a redirect
     * from elsewhere as ours would let any installed app end a sign-in at will, so one that does not
     * match is left alone and the capture keeps waiting — the same reasoning as the desktop
     * listener's `state` check.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun claim(isOurs: (String) -> Boolean): String =
        redirects.first(isOurs).also { redirects.resetReplayCache() }

    /**
     * Whether a redirect [isOurs] accepts is sitting here right now.
     *
     * A synchronous peek, and the reason [IntentRedirectChannel] needs no grace window around its
     * cancellation heuristic: `onNewIntent` runs before `onResume`, so by the time a resume is
     * observed anything of ours has already been delivered, whether or not a collector has picked
     * it up yet.
     */
    fun holds(isOurs: (String) -> Boolean): Boolean = redirects.replayCache.any(isOurs)

    /**
     * Takes whatever redirect this process was launched with, unfiltered.
     *
     * Unfiltered because there is nothing to filter against: nothing in this process minted a
     * `state`, which is exactly the situation — see [AndroidStartupRedirect]. The stored Pending
     * Authorization is what the redirect is checked against instead, in the repository, which is the
     * only thing holding one after a process death.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun claimLaunchRedirect(): String? =
        redirects.replayCache.firstOrNull()?.also { redirects.resetReplayCache() }

    companion object {
        /**
         * The process's inbox. A global rather than a Koin binding because `MainActivity` forwards
         * to it from `onCreate` and `onNewIntent`, and a redirect must not depend on the graph
         * being up.
         */
        val Shared: AuthRedirectInbox = AuthRedirectInbox()
    }
}
