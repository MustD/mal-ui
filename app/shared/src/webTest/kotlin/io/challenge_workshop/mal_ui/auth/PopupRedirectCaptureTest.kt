@file:OptIn(ExperimentalWasmJsInterop::class)

package io.challenge_workshop.mal_ui.auth

import io.challenge_workshop.mal_ui.mal.HttpClientFactory
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.SessionState
import io.challenge_workshop.mal_ui.session.SessionStorageKeyValueStore
import io.challenge_workshop.mal_ui.session.SignedOutReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The popup capture and the opener's Session, end to end, over the real `sessionStorage`.
 *
 * `PopupRedirectChannelTest` covers the message hygiene and `:core` covers what
 * [MalSessionRepository] does with a redirect; what is only true once they are put together is that
 * the store the opener reads the Pending Authorization out of is its **own** — the popup gets a
 * copy, so anything it cleared would not reach here.
 */
class PopupRedirectCaptureTest {

    @Test
    fun a_relayed_redirect_signs_the_user_in() = captureTest {
        repository.beginAuthorization()
        val pending = assertNotNull(store.readPending())
        val channel = armedChannel()

        relay("$redirectUri?code=a-code&state=${pending.state}", channel)

        assertIs<SessionState.SignedIn>(repository.state.value)
        assertNull(store.readPending(), "A spent Pending Authorization must not survive.")
    }

    /**
     * The check that makes the popup safe to trust at all: `event.origin` and `event.source` say
     * *where* a message came from, and only `state` says whether it belongs to **this** sign-in.
     */
    @Test
    fun a_state_that_does_not_match_this_sign_in_ends_the_attempt() = captureTest {
        repository.beginAuthorization()
        val channel = armedChannel()

        relay("$redirectUri?code=a-code&state=some-other-sign-in", channel)

        assertEquals(
            SessionState.SignedOut(SignedOutReason.AuthorizationFailed),
            (repository.state.value as SessionState.SignedOut).copy(error = null),
        )
        assertNull(store.readPending(), "A refused redirect spends the attempt it was aimed at.")
    }

    /**
     * The full-page-redirect fallback, end to end and over the real `sessionStorage`.
     *
     * The two halves are separately covered — `WebStartupRedirectTest` reads the address bar,
     * `MalSessionViewModelStartupTest` completes over a fake store — and joining them is the point:
     * the code verifier has to come back from the **same** `sessionStorage` the document that
     * started the sign-in wrote it to, having survived that document being destroyed.
     */
    @Test
    fun a_boot_after_a_full_page_redirect_completes_from_session_storage() = captureTest {
        // Exactly what the blocked-popup path leaves behind before the tab navigates away.
        repository.beginAuthorization()
        val pending = assertNotNull(store.readPending())
        val originalHref = currentHref()
        replaceUrl("${currentPath()}?code=a-code&state=${pending.state}")

        try {
            val redirect = assertNotNull(
                WebStartupRedirect().consume(),
                "the launch is carrying a redirect and nothing else will deliver it",
            )
            repository.completeAuthorization(redirect)

            assertIs<SessionState.SignedIn>(repository.state.value)
            assertEquals("", currentSearch(), "a reload must not find the single-use code again")
            assertNull(store.readPending())
        } finally {
            replaceUrl(originalHref)
        }
    }

    /**
     * Hands the redirect over exactly as the popup would, then drives the opener's side of it —
     * which is the same call the paste field makes.
     */
    private suspend fun Fixture.relay(rawRedirect: String, channel: AuthRedirectChannel) {
        postToSelf(rawRedirect)
        val captured = withContext(Dispatchers.Default) {
            withTimeoutOrNull(2.seconds) { channel.await() }
        }
        val received = assertIs<AuthRedirectResult.Received>(captured)
        runCatching { repository.completeAuthorization(received.rawRedirect) }
    }

    private class Fixture {
        // The real one, not a fake: the opener's copy of `sessionStorage` is the thing under test.
        val store = JsonTokenStore(SessionStorageKeyValueStore("mal_ui.test.${counter++}"))
        val redirectUri = "${currentOrigin()}/oauth/callback"
        val repository = MalSessionRepository(
            store = store,
            initialConfig = MalAuthConfig(
                clientId = "a-client-id",
                redirectUri = redirectUri,
                tokenEndpoint = FAKE_MAL_TOKEN_ENDPOINT,
                apiBaseUrl = FAKE_MAL_API_BASE_URL,
            ),
            clientFactory = fakeMal(),
        )

        suspend fun armedChannel(): AuthRedirectChannel {
            val channel = PopupRedirectChannel(openPopup = { thisWindow() }, navigate = {})
            assertEquals(ArmResult.Armed, channel.arm(redirectUri))
            channel.open("https://myanimelist.net/v1/oauth2/authorize")
            return channel
        }

        companion object {
            /** A namespace per test, because `sessionStorage` outlives one and the tab is shared. */
            var counter = 0
        }
    }

    private fun captureTest(block: suspend Fixture.() -> Unit) = runTest {
        val fixture = Fixture()
        try {
            fixture.block()
        } finally {
            fixture.store.clear()
            fixture.repository.close()
        }
    }
}

