package io.challenge_workshop.mal_ui.auth

import androidx.compose.runtime.Composable

/**
 * What [AuthRedirectChannel.arm] managed to reserve.
 *
 * Reported *before* the browser opens, which is the whole reason `arm` is a separate phase: a desktop
 * port that will not bind has to be a message on the sign-in screen, not a failure discovered after the
 * user has already approved access on myanimelist.net.
 */
sealed interface ArmResult {
    /** The platform will deliver the redirect. Call [AuthRedirectChannel.open], then `await`. */
    data object Armed : ArmResult

    /**
     * No Redirect Capture here — a headless desktop, a browser with no Custom Tabs. Not an error: the
     * caller opens the browser itself and falls back to Paste-the-code.
     */
    data object Unsupported : ArmResult

    /** The platform *should* have been able to capture the redirect and could not. */
    data class Failed(val message: String) : ArmResult
}

/** How an armed [AuthRedirectChannel] ended. */
sealed interface AuthRedirectResult {
    /**
     * The redirect, exactly as the platform saw it. Not parsed here — it goes through the same
     * `parseRedirect()` as a paste, so both paths produce the same errors.
     */
    data class Received(val rawRedirect: String) : AuthRedirectResult

    /** The user backed out. The Pending Authorization survives; see `MalSessionViewModel.cancelSignIn`. */
    data object Cancelled : AuthRedirectResult

    /** The capture broke after the browser opened — a timeout, a listener that died. */
    data class Failed(val message: String) : AuthRedirectResult

    /** The channel gave up on capturing anything. Fall back to Paste-the-code. */
    data object Unsupported : AuthRedirectResult
}

/**
 * This target's Redirect Capture: a loopback listener on desktop, an Auth Tab result on Android, a
 * popup message on web.
 *
 * Three phases rather than one `suspend fun`, because the three platforms have incompatible natural
 * shapes and each phase exists to satisfy one of them:
 *
 * - **[arm] is separate** so a desktop `BindException` is reportable before the user has approved on
 *   MAL, and so no Pending Authorization is minted for a flow that cannot complete.
 * - **[open] is not `suspend`** so the web popup keeps its user activation. Activation is a timestamp
 *   window and WebKit caps gesture forwarding at **1 second**, so any `launch { }` or real suspension
 *   between the click and `window.open` loses it.
 * - **The channel comes from [rememberAuthRedirectChannel]**, a `@Composable`, so Android can hold an
 *   `ActivityResultLauncher` — which can only be registered from composition.
 *
 * The consequence, worth naming: the ViewModel cannot own the channel. The composable owns it and hands
 * it in. That is a real wart and the price of one API across three platforms.
 *
 * Cancelling the coroutine that is in [await] is how a channel releases whatever [arm] reserved. There
 * is deliberately no `close()`: an extra teardown method is one more thing a caller can forget on the
 * path where it matters most.
 */
interface AuthRedirectChannel {
    /**
     * Reserve whatever the platform needs, before the browser opens.
     *
     * Anything but [ArmResult.Armed] means this channel is out of the picture and the caller must not
     * call [open] or [await].
     *
     * @param redirectUri the URI MAL will send the user back to — byte-identical to the one going into
     * the authorization request, since that is what the platform has to listen on.
     */
    suspend fun arm(redirectUri: String): ArmResult

    /** Send the user to MAL. Called synchronously inside the click handler. */
    fun open(authorizationUrl: String)

    /** Suspends until the redirect lands, the user gives up, or the caller cancels. */
    suspend fun await(): AuthRedirectResult
}

/**
 * The channel that captures nothing, and Android's actual until ticket 16 replaces it.
 *
 * Not a stub: [ArmResult.Unsupported] is a modelled path that stays reachable on every platform
 * forever — headless desktop, a blocked popup, no Custom-Tabs browser — and Paste-the-code is the only
 * mechanism that works in all of them. It is also the only one that is already tested end to end, so
 * having every target start here means the platform channels land on a working login rather than
 * alongside one.
 */
object PasteOnlyRedirectChannel : AuthRedirectChannel {

    override suspend fun arm(redirectUri: String): ArmResult = ArmResult.Unsupported

    /** Nothing to open: an unarmed channel's caller opens the browser itself. */
    override fun open(authorizationUrl: String) = Unit

    /** Returns rather than suspending forever, so a caller that ignores [arm] stalls nothing. */
    override suspend fun await(): AuthRedirectResult = AuthRedirectResult.Unsupported
}

/**
 * This target's [AuthRedirectChannel], scoped to the composition that will use it.
 *
 * Remember it **above** the `SessionState` `when` and not inside the sign-in screen: the screen is
 * swapped out for `AuthorizingScreen` the moment the flow starts, and a channel that left composition
 * there would take an Android `ActivityResultLauncher` with it.
 */
@Composable
expect fun rememberAuthRedirectChannel(): AuthRedirectChannel
