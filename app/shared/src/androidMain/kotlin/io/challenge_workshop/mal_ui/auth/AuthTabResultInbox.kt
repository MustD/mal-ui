package io.challenge_workshop.mal_ui.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

/**
 * What an Auth Tab reported, in the two fields this app has any use for.
 *
 * [resultCode] is one of `AuthTabIntent.RESULT_*`, kept as the raw `Int` rather than mapped here:
 * the mapping is a judgement about what each code *means* for a sign-in, and that belongs in
 * [AuthTabRedirectChannel] next to the race it feeds, not in the hand-off.
 *
 * [redirect] is androidx's `AuthResult.resultUri`, which it fills for `RESULT_OK` and leaves null
 * for everything else.
 */
internal data class AuthTabResult(val resultCode: Int, val redirect: String?)

/**
 * The hand-off from an `ActivityResultLauncher` callback to whatever is waiting for the Auth Tab to
 * finish.
 *
 * An *inbox* and named after [AuthRedirectInbox] on purpose: same job, same shape, and the same
 * reason for not being called a relay. It holds one result until something takes it.
 *
 * Separate from that inbox rather than folded into it, because the two are filled from different
 * places and only one of them is an `Intent`. And separate from the channel that reads it, because
 * the callback has to be registered during composition — unconditionally, before anything knows
 * whether an Auth Tab will be launched — so the thing it writes into cannot be the channel.
 *
 * `replay = 1` for the same reason as the inbox, though the margin is wider here: the callback runs
 * on the main thread when the tab closes, which is normally long after `await` has parked on
 * [claim]. Relying on that ordering would be relying on the scheduler, and the failure it produces —
 * a sign-in that hangs with no error anywhere — is the one worth spending a buffer slot on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AuthTabResultInbox {

    private val results = MutableSharedFlow<AuthTabResult>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Called from the `ActivityResultCallback`, where suspending is not an option. */
    fun deliver(result: AuthTabResult) {
        results.tryEmit(result)
    }

    /**
     * Drops anything a previous attempt left behind.
     *
     * Not tidiness: an Auth Tab the user closed while nothing was awaiting leaves a `RESULT_CANCELED`
     * sitting in the replay buffer, and the next sign-in would read it as its own answer and cancel
     * itself before the user had done anything. Unlike a redirect there is no `state` in a result
     * code to filter on, so clearing at [AuthTabRedirectChannel.arm] is the only guard there is.
     */
    fun clear() {
        results.resetReplayCache()
    }

    /** Suspends until this attempt's Auth Tab reports, and takes the result. */
    suspend fun claim(): AuthTabResult = results.first().also { results.resetReplayCache() }
}
