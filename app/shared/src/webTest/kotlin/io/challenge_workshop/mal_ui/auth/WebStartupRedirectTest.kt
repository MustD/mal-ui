@file:OptIn(ExperimentalWasmJsInterop::class)

package io.challenge_workshop.mal_ui.auth

import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs under both `jsTest` and `wasmJsTest`, against the real `window.location` and `history` —
 * there is nothing else to this class. The served URL is put back in teardown so the next test, and
 * Karma's own asset loading, see the page they were given.
 */
class WebStartupRedirectTest {

    private var originalHref: String = ""

    @BeforeTest
    fun remember_the_served_url() {
        originalHref = currentHref()
    }

    @AfterTest
    fun restore_the_served_url() {
        replaceUrl(originalHref)
    }

    @Test
    fun an_ordinary_launch_has_nothing_to_consume() = runTest {
        replaceUrl(currentPath())

        assertNull(WebStartupRedirect().consume())
    }

    @Test
    fun a_launch_on_the_callback_route_hands_the_whole_address_on() = runTest {
        replaceUrl("${currentPath()}?code=a-code&state=a-state")
        val href = currentHref()

        assertEquals(href, WebStartupRedirect().consume())
    }

    @Test
    fun consuming_a_redirect_takes_it_out_of_the_address_bar() = runTest {
        val path = currentPath()
        replaceUrl("$path?code=a-code&state=a-state")

        WebStartupRedirect().consume()

        // Before the exchange, not after: an authorization code is single-use, so a reload that
        // still carried it would fail — and under `plain` PKCE the verifier is in that URL too.
        assertEquals("", currentSearch())
        assertEquals(path, currentPath(), "the callback route is what boots the app")
        assertNull(WebStartupRedirect().consume(), "a startup redirect is consumed exactly once")
    }

    @Test
    fun a_denial_is_consumed_rather_than_ignored() = runTest {
        replaceUrl("${currentPath()}?error=access_denied&error_description=denied")
        val href = currentHref()

        // It has to reach the app as the same failure a pasted denial would, instead of leaving the
        // user on a sign-in screen with no explanation.
        assertEquals(href, WebStartupRedirect().consume())
        assertTrue(currentSearch().isEmpty())
    }
}

