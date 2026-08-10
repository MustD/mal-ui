package io.challenge_workshop.mal_ui.auth

import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException

/**
 * An [AuthRedirectChannel] that answers however a test tells it to and records what it was asked.
 *
 * [await] parks until [deliver] is called, so a test can inspect the Pending Authorization that the
 * sign-in just minted and then hand back a redirect carrying its `state` — which is what a real capture
 * receives and what the ViewModel checks. Pass [awaitResult] instead when the result does not depend on
 * anything the flow produced.
 *
 * `openedUrls` is a list rather than a flag on purpose: "was `open` called at all" is the assertion the
 * [ArmResult.Unsupported] path turns on.
 */
class RecordingAuthRedirectChannel(
    private val armResult: ArmResult = ArmResult.Armed,
    awaitResult: AuthRedirectResult? = null,
) : AuthRedirectChannel {

    val armedWith: MutableList<String> = mutableListOf()
    val openedUrls: MutableList<String> = mutableListOf()
    var awaited: Int = 0
        private set

    private val captured = CompletableDeferred<AuthRedirectResult>()
    private val released = CompletableDeferred<Unit>()

    init {
        awaitResult?.let { captured.complete(it) }
    }

    /** What the platform would do when the redirect lands. */
    fun deliver(result: AuthRedirectResult) {
        captured.complete(result)
    }

    /**
     * Suspends until [await] is cancelled — which is a real channel's only teardown path, and so the
     * only observable moment at which it lets go of a bound port or an open popup.
     */
    suspend fun awaitRelease() = released.await()

    override suspend fun arm(redirectUri: String): ArmResult {
        armedWith += redirectUri
        return armResult
    }

    override fun open(authorizationUrl: String) {
        openedUrls += authorizationUrl
    }

    override suspend fun await(): AuthRedirectResult {
        awaited++
        try {
            return captured.await()
        } catch (e: CancellationException) {
            released.complete(Unit)
            throw e
        }
    }
}
