package io.challenge_workshop.mal_ui.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs under both `jsTest` and `wasmJsTest`.
 *
 * The decision and the ordering are driven through the injectable form, because a test document
 * cannot be opened as a popup and must not close itself. What is checked against the real browser
 * is the one thing that matters for the app document: [relaySignInRedirectToOpener] leaves a
 * document with no opener alone, so the entry point goes on to boot the app.
 */
class SignInPopupRelayTest {

    private val posted = mutableListOf<Pair<String, String>>()
    private var closed = 0

    @Test
    fun a_popup_carrying_a_code_hands_the_whole_redirect_back_and_closes() {
        val relayed = relay(hasOpener = true, search = "?code=a-code&state=a-state")

        assertTrue(relayed, "The entry point must not boot the app in a popup.")
        // The whole href, unparsed: the opener owns the Pending Authorization, and one parser means
        // the popup path and the paste path fail identically.
        assertEquals(listOf(HREF to ORIGIN), posted)
        assertEquals(1, closed)
    }

    @Test
    fun a_denial_is_relayed_too_rather_than_leaving_the_opener_waiting() {
        assertTrue(relay(hasOpener = true, search = "?error=access_denied&state=a-state"))

        assertEquals(listOf(HREF to ORIGIN), posted)
    }

    @Test
    fun the_target_origin_is_this_documents_own_and_never_a_wildcard() {
        relay(hasOpener = true, search = "?code=a-code")

        // The popup's final document is same-origin with its opener — each origin registers its own
        // Redirect URI — so there is a real origin to name, and an authorization code is a
        // credential that must never be posted to `*`.
        assertEquals(ORIGIN, posted.single().second)
    }

    /**
     * The bug this guards: `hasOpener` alone is not enough to mean "popup".
     *
     * The full-page-redirect fallback lands the **app's own tab** on this exact query — and that tab
     * has an opener whenever the user reached the app through a `target="_blank"` link or another
     * page's `window.open`. Relaying there posts the redirect at a window that has nothing to do
     * with the sign-in and then closes the app mid-login, taking the whole fallback with it.
     */
    @Test
    fun a_tab_that_merely_has_an_opener_is_not_the_sign_in_popup() {
        val relayed = relay(hasOpener = true, search = "?code=a-code&state=a-state", windowName = "")

        assertFalse(relayed, "Only the window this app named is carrying its authorization code.")
        assertTrue(posted.isEmpty() && closed == 0)
    }

    @Test
    fun a_document_with_no_opener_is_the_app_itself_and_is_left_alone() {
        // The full-page-redirect fallback lands here: same query, no opener. Completing it is the
        // opener's boot path, not this one.
        assertFalse(relay(hasOpener = false, search = "?code=a-code&state=a-state"))

        assertTrue(posted.isEmpty() && closed == 0)
    }

    @Test
    fun a_popup_with_nothing_to_relay_is_not_ours() {
        assertFalse(relay(hasOpener = true, search = ""))
        assertFalse(relay(hasOpener = true, search = "?something=else"))

        assertTrue(posted.isEmpty() && closed == 0)
    }

    @Test
    fun the_real_document_running_these_tests_has_no_opener() {
        // Against the real `window`: if this ever answered true, every launch of the app would
        // relay and render nothing.
        assertFalse(relaySignInRedirectToOpener())
    }

    private fun relay(
        hasOpener: Boolean,
        search: String,
        windowName: String = SIGN_IN_POPUP_NAME,
    ) = relayRedirectToOpener(
        hasOpener = hasOpener,
        windowName = windowName,
        search = search,
        href = HREF,
        origin = ORIGIN,
        postToOpener = { message, targetOrigin -> posted += message to targetOrigin },
        closeSelf = { closed++ },
    )

    private companion object {
        const val ORIGIN = "https://mal-ui.localhost"
        const val HREF = "$ORIGIN/oauth/callback?code=a-code&state=a-state"
    }
}
