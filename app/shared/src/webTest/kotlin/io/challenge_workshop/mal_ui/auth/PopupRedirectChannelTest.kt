@file:OptIn(ExperimentalWasmJsInterop::class)

package io.challenge_workshop.mal_ui.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs under both `jsTest` and `wasmJsTest` — one `webMain` actual serves both targets.
 *
 * The popup handle is injected rather than opened for real: Karma has no user activation, so a real
 * `window.open` is blocked here and would give the channel nothing to compare `event.source` against.
 * Passing this window in its place makes a `postMessage` from the test *look* like one from the
 * popup, which is exactly the case the origin and source checks have to let through.
 */
class PopupRedirectChannelTest {

    @Test
    fun a_message_from_an_unexpected_origin_is_ignored() = channelTest {
        // Armed for an origin this document is not being served from, so the message the test posts
        // arrives with the wrong `event.origin` while everything else about it is right.
        val channel = channelFor(ELSEWHERE, popup = thisWindow())
        assertEquals(ArmResult.Armed, channel.arm("$ELSEWHERE$CALLBACK_PATH"))
        channel.open(AUTHORIZATION_URL)

        postToSelf("$ELSEWHERE$CALLBACK_PATH?code=a-code&state=a-state")

        assertNull(
            channel.awaitOrNull(),
            "An authorization code is a credential: a message from another origin is not ours.",
        )
    }

    @Test
    fun a_message_whose_source_is_not_the_popup_handle_is_ignored() = channelTest {
        // Right origin, wrong window: any frame on the page can post to this one, and only the
        // popup we opened is carrying our authorization code.
        val channel = channelFor(currentOrigin(), popup = aDifferentWindow())
        assertEquals(ArmResult.Armed, channel.arm("${currentOrigin()}$CALLBACK_PATH"))
        channel.open(AUTHORIZATION_URL)

        postToSelf("${currentOrigin()}$CALLBACK_PATH?code=a-code&state=a-state")

        assertNull(channel.awaitOrNull())
    }

    @Test
    fun a_message_from_the_popup_is_handed_on_verbatim() = channelTest {
        val channel = channelFor(currentOrigin(), popup = thisWindow())
        assertEquals(ArmResult.Armed, channel.arm("${currentOrigin()}$CALLBACK_PATH"))
        channel.open(AUTHORIZATION_URL)
        val redirect = "${currentOrigin()}$CALLBACK_PATH?code=a-code&state=a-state"

        postToSelf(redirect)

        // Unparsed on purpose: `state` verification and the exchange belong to the opener's
        // repository, so the popup path and the paste path share one parser and one set of errors.
        assertEquals(AuthRedirectResult.Received(redirect), channel.awaitOrNull())
    }

    @Test
    fun a_denial_from_the_popup_is_handed_on_as_received() = channelTest {
        val channel = channelFor(currentOrigin(), popup = thisWindow())
        assertEquals(ArmResult.Armed, channel.arm("${currentOrigin()}$CALLBACK_PATH"))
        channel.open(AUTHORIZATION_URL)
        val denial = "${currentOrigin()}$CALLBACK_PATH?error=access_denied&state=a-state"

        postToSelf(denial)

        // A capture transports and never interprets: the denial is the opener repository's to
        // judge, so it ends the same way here as on desktop and Android.
        assertEquals(AuthRedirectResult.Received(denial), channel.awaitOrNull())
    }

    @Test
    fun a_blocked_popup_falls_back_to_a_full_page_redirect() = channelTest {
        // `window.open` returning null is what a blocked popup — or a lost user activation — looks
        // like from here, and it is the only signal there is.
        val channel = channelFor(currentOrigin(), popup = null)
        assertEquals(ArmResult.Armed, channel.arm("${currentOrigin()}$CALLBACK_PATH"))

        channel.open(AUTHORIZATION_URL)

        assertEquals(listOf(AUTHORIZATION_URL), navigatedTo)
        // This document is on its way out, so there is nothing left here to capture. What completes
        // the flow is the boot after the reload, off the Pending Authorization in `sessionStorage`.
        assertEquals(AuthRedirectResult.Unsupported, channel.awaitOrNull())
    }

    @Test
    fun a_redirect_uri_on_another_origin_is_not_something_a_popup_can_capture() = channelTest {
        val channel = channelFor(currentOrigin(), popup = thisWindow())

        // Android's custom scheme, or a build pointed at the wrong origin. `postMessage` is
        // origin-scoped, so there would be no opener to message back.
        assertEquals(ArmResult.Unsupported, channel.arm("io.challenge-workshop.malui://oauth/callback"))
        assertEquals(ArmResult.Unsupported, channel.arm("$ELSEWHERE$CALLBACK_PATH"))
    }

    private fun channelFor(origin: String, popup: JsAny?) = PopupRedirectChannel(
        origin = origin,
        openPopup = { popup },
        navigate = { navigatedTo += it },
    )

    private val navigatedTo = mutableListOf<String>()

    /** Real time, not the test scheduler's: a `postMessage` is delivered by the browser's event loop. */
    private suspend fun AuthRedirectChannel.awaitOrNull(
        within: Duration = 400.milliseconds,
    ): AuthRedirectResult? = withContext(Dispatchers.Default) { withTimeoutOrNull(within) { await() } }

    private fun channelTest(block: suspend PopupRedirectChannelTest.() -> Unit) = runTest { block() }

    private companion object {
        const val ELSEWHERE = "https://not-this-origin.test"
        const val CALLBACK_PATH = "/oauth/callback"
        const val AUTHORIZATION_URL = "https://myanimelist.net/v1/oauth2/authorize?state=a-state"
    }
}

