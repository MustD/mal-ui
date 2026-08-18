package io.challenge_workshop.mal_ui.auth

import io.challenge_workshop.mal_ui.session.JsonTokenStore

/**
 * Android's [StartupRedirect]: a redirect that reached `MainActivity` before there was anything to
 * hand it to.
 *
 * That is process death while the user was away approving on myanimelist.net — an ordinary outcome
 * of being parked behind a browser on a low-RAM device, not a theoretical one, and arguably the top
 * cause of "OAuth works on my Pixel and fails on cheap phones". The redirect relaunches the app, so
 * the process that receives it has no armed [IntentRedirectChannel], no sign-in in flight and no
 * `state` in memory to check against. What it does have is the Pending Authorization the repository
 * persisted before the browser ever opened, which is the whole reason that record exists.
 *
 * Unfiltered by `state`, unlike [IntentRedirectChannel]: there is none in this process to compare
 * against, so that check happens where the Pending Authorization is — in
 * `MalSessionRepository.completeAuthorization`.
 */
internal class AndroidStartupRedirect(
    private val store: JsonTokenStore,
    private val inbox: AuthRedirectInbox = AuthRedirectInbox.Shared,
) : StartupRedirect {

    /**
     * Takes the launch redirect, but only while there is something left to complete it with.
     *
     * The guard is Android-specific and deliberate. A launch Intent is not a user action: it stays
     * on the `ActivityRecord` of an activity that a redirect started, so the *system* re-delivers it
     * on every later relaunch of that task — days after the login it belongs to finished. Handing
     * that on would put "there is no sign-in in progress on this device" over a Session that is
     * working perfectly, with nobody having done anything.
     *
     * Web hands its equivalent on unconditionally, and that difference is the point: a `?code=` in
     * the address bar always means the user *just* came back from MyAnimeList, so there the message
     * is worth showing. A redirect that arrives while this app is running — the ordinary case —
     * still goes through [IntentRedirectChannel] and still reports every error it produces.
     */
    override suspend fun consume(): String? {
        if (store.readPending() == null) return null
        return inbox.claimLaunchRedirect()
    }
}
