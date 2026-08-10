@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui.auth

import io.challenge_workshop.mal_ui.mal.HttpClientFactory
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.mal.MalUser
import io.challenge_workshop.mal_ui.session.FakeKeyValueStore
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.SessionState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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

private const val TEST_TOKEN_ENDPOINT = "https://mal.test/v1/oauth2/token"
private const val TEST_API_BASE_URL = "https://mal.test/v2"
private const val TEST_REDIRECT_URI = "http://127.0.0.1:18040/oauth/callback"

private val TEST_USER = MalUser(id = 42, name = "someone")

/**
 * What [MalSessionViewModel] does with an [AuthRedirectChannel].
 *
 * Every path here ends in `MalSessionRepository`, whose behaviour is already covered in `:core`. What
 * these hold down is the part only the ViewModel can get wrong: which phase runs when, and that a
 * captured redirect is not given a second, parallel code path of its own.
 */
class MalSessionViewModelRedirectTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun an_unsupported_channel_never_gets_opened_and_falls_back_to_paste_the_code() = redirectTest {
        val channel = RecordingAuthRedirectChannel(armResult = ArmResult.Unsupported)
        val opened = mutableListOf<String>()

        viewModel.signIn(channel, opened::add)
        settle()

        assertTrue(
            channel.openedUrls.isEmpty() && channel.awaited == 0,
            "An unarmed channel captures nothing, so driving it further would park the login " +
                "behind a redirect that is never coming.",
        )
        // The browser still opens, and the URL is still offered by hand — no platform's
        // browser-opening call reliably reports whether it worked.
        val pending = assertNotNull(store.readPending())
        assertEquals(listOf(viewModel.authorizationUrlFor(pending)), opened)
        assertEquals(SessionState.Authorizing(pending), repository.state.value)

        viewModel.onPastedRedirectChange("$TEST_REDIRECT_URI?code=the-code&state=${pending.state}")
        viewModel.completeSignIn()

        assertEquals(SessionState.SignedIn(TEST_USER), awaitSettledSession())
    }

    @Test
    fun an_armed_channel_opens_the_browser_itself_and_its_redirect_completes_the_login() = redirectTest {
        val channel = RecordingAuthRedirectChannel()
        val opened = mutableListOf<String>()

        viewModel.signIn(channel, opened::add)
        settle()

        val pending = assertNotNull(store.readPending())
        assertEquals(listOf(viewModel.authorizationUrlFor(pending)), channel.openedUrls)
        assertTrue(opened.isEmpty(), "An armed channel owns the browser; opening it twice opens two.")
        // A capture listening anywhere but where MAL redirects is a login that hangs.
        assertEquals(listOf(pending.redirectUri), channel.armedWith)

        channel.deliver(
            AuthRedirectResult.Received("$TEST_REDIRECT_URI?code=the-code&state=${pending.state}"),
        )

        assertEquals(SessionState.SignedIn(TEST_USER), awaitSettledSession())
    }

    @Test
    fun a_paste_that_beats_the_capture_lets_the_channel_go() = redirectTest {
        val channel = RecordingAuthRedirectChannel()
        viewModel.signIn(channel)
        settle()
        val pending = assertNotNull(store.readPending())

        viewModel.completeSignIn("$TEST_REDIRECT_URI?code=the-code&state=${pending.state}")

        assertEquals(SessionState.SignedIn(TEST_USER), awaitSettledSession())
        // Times out rather than returning if the channel is left listening — which on desktop means a
        // bound 18040 that the next sign-in cannot rebind.
        channel.awaitRelease()
    }

    /**
     * One parser, one set of error messages. A platform channel that quietly grew its own handling of
     * `error=access_denied` is the drift this is here to catch.
     */
    @Test
    fun a_received_redirect_fails_exactly_as_the_same_paste_would() = redirectTest {
        val denied = "$TEST_REDIRECT_URI?error=access_denied&error_description=denied"

        viewModel.signIn(RecordingAuthRedirectChannel(awaitResult = AuthRedirectResult.Received(denied)))
        settle()

        val pasting = another()
        try {
            pasting.viewModel.signIn(RecordingAuthRedirectChannel(armResult = ArmResult.Unsupported))
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
    fun a_cancelled_capture_re_enables_sign_in_and_keeps_the_pending_authorization() = redirectTest {
        viewModel.signIn(RecordingAuthRedirectChannel(awaitResult = AuthRedirectResult.Cancelled))
        settle()

        // Cancellation detection is best-effort on Android — a notification or a configuration change
        // reads as one — so destroying the verifier here would break logins that were about to work.
        assertNotNull(
            store.readPending(),
            "Cancelling must keep the Pending Authorization: a redirect that lands later is good.",
        )
        assertTrue(repository.state.value is SessionState.SignedOut, "${repository.state.value}")
        assertTrue(viewModel.canStart, "The sign-in button has to come back after a cancellation.")
    }

    @Test
    fun a_capture_that_breaks_leaves_the_paste_field_in_place() = redirectTest {
        viewModel.signIn(
            RecordingAuthRedirectChannel(awaitResult = AuthRedirectResult.Failed("the listener died")),
        )
        settle()

        assertEquals("the listener died", viewModel.error)
        // Still `Authorizing`, because the user is away on MAL and can paste what they land on.
        assertTrue(repository.state.value is SessionState.Authorizing, "${repository.state.value}")
    }

    @Test
    fun an_arm_failure_is_reported_before_any_authorization_is_started() = redirectTest {
        val channel = RecordingAuthRedirectChannel(
            armResult = ArmResult.Failed("Port 18040 is already in use."),
        )
        val opened = mutableListOf<String>()

        viewModel.signIn(channel, opened::add)
        settle()

        assertEquals("Port 18040 is already in use.", viewModel.error)
        assertTrue(channel.openedUrls.isEmpty() && channel.awaited == 0 && opened.isEmpty())
        // The whole reason `arm` is its own phase: nothing is minted for a flow that cannot finish,
        // and the user has not yet approved anything on myanimelist.net.
        assertNull(store.readPending())
        assertTrue(repository.state.value is SessionState.SignedOut, "${repository.state.value}")
        assertTrue(viewModel.canStart)
    }

    /**
     * One per test, built inside `runTest` against the scheduler [settle] drives — which is what makes
     * `viewModelScope`, and therefore every assertion here, deterministic.
     */
    private class Fixture(private val scope: TestScope) {
        val store = JsonTokenStore(FakeKeyValueStore())
        val repository = MalSessionRepository(
            store = store,
            initialConfig = MalAuthConfig(
                clientId = "a-client-id",
                redirectUri = TEST_REDIRECT_URI,
                tokenEndpoint = TEST_TOKEN_ENDPOINT,
                apiBaseUrl = TEST_API_BASE_URL,
            ),
            clientFactory = fakeMal(),
        )
        val viewModel = MalSessionViewModel(repository)

        /** Runs everything `viewModelScope` has outstanding. */
        fun settle() = scope.advanceUntilIdle()

        /**
         * Waits for a token exchange to land somewhere.
         *
         * [settle] is not enough for this one: `MockEngine` answers on its own dispatcher, which the
         * test scheduler does not drive, so virtual time runs out while the exchange is still in
         * flight. The states it waits for are the resting ones — `SignedIn` before the cached user
         * arrives is a step, not a destination.
         */
        suspend fun awaitSettledSession(): SessionState = repository.state.first {
            it is SessionState.SignedOut || (it is SessionState.SignedIn && it.user != null)
        }

        /** A second, independent app — for comparing two routes to the same outcome. */
        fun another() = Fixture(scope)
    }

    /** The browser-opening fallback is irrelevant to most of these, so it defaults to a no-op. */
    private fun MalSessionViewModel.signIn(channel: AuthRedirectChannel) = signIn(channel, openUri = {})

    private fun redirectTest(block: suspend Fixture.() -> Unit) = runTest {
        // viewModelScope runs on Dispatchers.Main, which no test platform provides by default.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        try {
            fixture.block()
        } finally {
            fixture.repository.close()
        }
    }
}

/** Answers the token exchange and `/users/@me`, and nothing else. */
private fun fakeMal(): HttpClientFactory {
    val engine = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            request.url.toString().startsWith(TEST_TOKEN_ENDPOINT) -> respond(
                content = """{"token_type":"Bearer","expires_in":2415600,""" +
                    """"access_token":"an-access-token","refresh_token":"a-refresh-token"}""",
                status = HttpStatusCode.OK,
                headers = json,
            )

            request.url.encodedPath.endsWith("/users/@me") -> respond(
                content = """{"id":42,"name":"someone"}""",
                status = HttpStatusCode.OK,
                headers = json,
            )

            else -> respondError(HttpStatusCode.NotFound, "unexpected ${request.url}")
        }
    }
    return HttpClientFactory { configure -> HttpClient(engine) { configure() } }
}
