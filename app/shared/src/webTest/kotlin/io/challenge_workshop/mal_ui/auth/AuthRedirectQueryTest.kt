@file:OptIn(ExperimentalWasmJsInterop::class)

package io.challenge_workshop.mal_ui.auth

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs under both `jsTest` and `wasmJsTest`, which is the point of one shared `webMain` source file.
 *
 * Every test drives the real `window.location` and the real `history`, since there is nothing else
 * to these two functions. The original URL is put back afterwards so the next test — and Karma's own
 * relative asset loading — sees the page it was served.
 */
class AuthRedirectQueryTest {

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
    fun the_live_query_string_is_what_current_search_returns() {
        replaceUrl("${currentPath()}?code=abc123&state=xyz")

        assertEquals("?code=abc123&state=xyz", currentSearch())
    }

    @Test
    fun a_url_without_a_query_reads_as_an_empty_search() {
        replaceUrl(currentPath())

        assertEquals("", currentSearch())
    }

    @Test
    fun clearing_the_query_keeps_the_path_and_the_fragment() {
        val path = currentPath()
        replaceUrl("$path?code=abc123&state=xyz#somewhere")

        clearAuthQuery()

        assertEquals("", currentSearch(), "the single-use code must not survive a reload")
        assertEquals(path, currentPath(), "the callback route still has to boot the app")
        assertEquals("#somewhere", currentHash())
    }

    @Test
    fun clearing_the_query_leaves_no_history_entry_behind() {
        // `replaceState`, never `pushState`: under `plain` PKCE the query holds the code *and* the
        // verifier, so an entry that keeps them is the thing this is meant to remove, and Back would
        // walk straight back onto a spent code.
        replaceUrl("${currentPath()}?code=abc123&state=xyz")
        val entriesBefore = historyLength()

        clearAuthQuery()

        assertEquals(entriesBefore, historyLength())
    }

    @Test
    fun clearing_an_already_clean_url_changes_nothing() {
        val path = currentPath()
        replaceUrl(path)

        clearAuthQuery()

        assertEquals(path, currentPath())
        assertEquals("", currentSearch())
    }
}

private fun currentHref(): String = js("window.location.href")

private fun currentPath(): String = js("window.location.pathname")

private fun currentHash(): String = js("window.location.hash")

private fun historyLength(): Int = js("window.history.length")

private fun replaceUrl(url: String): Unit = js("window.history.replaceState(null, '', url)")
