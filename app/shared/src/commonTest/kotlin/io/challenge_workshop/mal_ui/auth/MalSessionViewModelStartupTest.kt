@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui.auth

import io.challenge_workshop.mal_ui.mal.HttpClientFactory
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.session.FakeKeyValueStore
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.PendingAuthorization
import io.challenge_workshop.mal_ui.session.SessionState
import io.challenge_workshop.mal_ui.session.SignedOutReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A web origin, because web is the only target a startup redirect can reach. */
private const val TEST_REDIRECT_URI = "https://mal-ui.localhost/oauth/callback"
private const val TEST_CLIENT_ID = "a-client-id"

/**
 * What [MalSessionViewModel] does with a [StartupRedirect] — the web full-page-redirect fallback
 * arriving as a launch rather than as a capture.
 *
 * In `commonTest` rather than `webTest` because none of it is browser-specific: reading the query
 * out of the address bar is `WebStartupRedirect`'s job and is tested there, and what is left is the
 * part that has to hold on any target that ever grows one — that it happens *after* the store has
 * been read, and that a failure is stated rather than swallowed.
 */
class MalSessionViewModelStartupTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun a_launch_carrying_a_redirect_completes_the_sign_in() = startupTest {
        val pending = seedPendingAuthorization()
        start(redirect = "$TEST_REDIRECT_URI?code=a-code&state=${pending.state}")

        assertEquals(SessionState.SignedIn(FAKE_MAL_USER), awaitSettledSession())
        assertNull(store.readPending(), "A spent Pending Authorization must not survive the launch.")
    }

    /**
     * Ordering, and it is the whole reason this runs from `init` rather than from a composable:
     * `restore()` settles the state from the store, so a redirect completed before it would have its
     * `SignedIn` overwritten by whatever the store said a moment earlier.
     */
    @Test
    fun the_store_is_read_before_the_redirect_is_completed() = startupTest {
        val pending = seedPendingAuthorization()
        start(redirect = "$TEST_REDIRECT_URI?code=a-code&state=${pending.state}")
        awaitSettledSession()

        assertEquals(
            SessionState.Authorizing(pending),
            stateWhenConsumed,
            "Restore has to have settled on the resumed Authorizing state before this runs.",
        )
    }

    @Test
    fun a_launch_carrying_a_code_with_nothing_to_complete_it_says_so() = startupTest {
        // The Pending Authorization holds the code verifier, and without one there is nothing to
        // exchange the code against. Silence here reads as "the sign-in button did nothing".
        start(redirect = "$TEST_REDIRECT_URI?code=a-code&state=a-state")
        settle()

        val error = assertNotNull(viewModel.error)
        assertTrue("no sign-in in progress" in error, error)
        assertEquals(SessionState.SignedOut(SignedOutReason.NeverSignedIn), repository.state.value)
    }

    /** One parser, one set of errors: a denial reads identically however the redirect arrived. */
    @Test
    fun a_launch_carrying_a_denial_fails_exactly_as_the_same_paste_would() = startupTest {
        val denied = "$TEST_REDIRECT_URI?error=access_denied&error_description=denied"
        seedPendingAuthorization()
        start(redirect = denied)
        settle()

        val pasting = another()
        try {
            pasting.seedPendingAuthorization()
            pasting.start(redirect = null)
            pasting.settle()
            pasting.viewModel.completeSignIn(denied)
            pasting.settle()

            assertNotNull(viewModel.error)
            assertEquals(pasting.viewModel.error, viewModel.error)
            assertEquals(pasting.repository.state.value, repository.state.value)
        } finally {
            pasting.repository.close()
        }
    }

    @Test
    fun an_ordinary_launch_restores_as_it_always_did() = startupTest {
        start(redirect = null)
        settle()

        assertEquals(SessionState.SignedOut(SignedOutReason.NeverSignedIn), repository.state.value)
        assertNull(viewModel.error)
        assertTrue(viewModel.canStart)
    }

    /**
     * One app's worth of graph. The ViewModel is built by [start] rather than in the constructor,
     * because a startup redirect is consumed from `init` and the store has to be seeded first.
     */
    private class Fixture(private val scope: TestScope) {
        val store = JsonTokenStore(FakeKeyValueStore())
        val repository = MalSessionRepository(
            store = store,
            initialConfig = MalAuthConfig(
                clientId = TEST_CLIENT_ID,
                redirectUri = TEST_REDIRECT_URI,
                tokenEndpoint = FAKE_MAL_TOKEN_ENDPOINT,
                apiBaseUrl = FAKE_MAL_API_BASE_URL,
            ),
            clientFactory = fakeMal(),
        )

        private var _viewModel: MalSessionViewModel? = null

        /** What the repository had settled on by the time the redirect was asked for. */
        var stateWhenConsumed: SessionState? = null
            private set

        val viewModel: MalSessionViewModel get() = requireNotNull(_viewModel) { "call start() first" }

        suspend fun seedPendingAuthorization(): PendingAuthorization = store.writePending(
            codeVerifier = "a-code-verifier",
            state = "a-state",
            redirectUri = TEST_REDIRECT_URI,
            clientId = TEST_CLIENT_ID,
        )

        /** Launches the app, with or without a redirect in hand. */
        fun start(redirect: String?) {
            _viewModel = MalSessionViewModel(
                repository = repository,
                startupRedirect = {
                    stateWhenConsumed = repository.state.value
                    redirect
                },
            )
        }

        fun settle() = scope.advanceUntilIdle()

        /**
         * `settle` is not enough: `MockEngine` answers on a dispatcher the test scheduler does not
         * drive, so virtual time runs out while the exchange is still in flight.
         */
        suspend fun awaitSettledSession(): SessionState = repository.state.first {
            it is SessionState.SignedOut || (it is SessionState.SignedIn && it.user != null)
        }

        fun another() = Fixture(scope)
    }

    private fun startupTest(block: suspend Fixture.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        try {
            fixture.block()
        } finally {
            fixture.repository.close()
        }
    }
}

