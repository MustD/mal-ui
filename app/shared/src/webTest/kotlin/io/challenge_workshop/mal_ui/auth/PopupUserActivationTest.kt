@file:OptIn(ExperimentalWasmJsInterop::class)

package io.challenge_workshop.mal_ui.auth

import io.challenge_workshop.mal_ui.mal.HttpClientFactory
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.SessionStorageKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one thing a popup Redirect Capture cannot survive being wrong about.
 *
 * `window.open` consumes a **user activation**, which is a timestamp window and not a call-stack
 * position — Chromium and Firefox allow about five seconds, **WebKit caps gesture forwarding at
 * one**. The button's `onClick` calls [MalSessionViewModel.signIn], which reaches
 * [AuthRedirectChannel.open] from inside `viewModelScope.launch`, and that only stays inside the
 * click's window while the whole stretch runs *synchronously*: `viewModelScope` uses
 * `Dispatchers.Main.immediate`, whose contract is to run in place unless something really suspends.
 *
 * Two things in that stretch could break it and neither is obvious from reading it — this target's
 * `arm`, and `beginAuthorization`, which mints PKCE and writes the Pending Authorization to
 * `sessionStorage`. Both are `suspend`; neither may reach a suspension point.
 *
 * So this asserts the stretch has already reached `open` **by the time `signIn` returns**, with the
 * production dispatcher and the production channel, on both web targets. If it ever stops holding,
 * the popup is silently blocked on Safari first and the symptom is a login that only ever takes the
 * full-page-redirect path.
 */
class PopupUserActivationTest {

    private val store = JsonTokenStore(SessionStorageKeyValueStore("mal_ui.activation-test"))
    private val redirectUri = "${currentOrigin()}/oauth/callback"
    private val repository = MalSessionRepository(
        store = store,
        initialConfig = MalAuthConfig(
            clientId = "a-client-id",
            redirectUri = redirectUri,
            tokenEndpoint = "https://mal.test/v1/oauth2/token",
            apiBaseUrl = "https://mal.test/v2",
        ),
        clientFactory = unreachableMal(),
    )

    @AfterTest
    fun tearDown() = runTest {
        // `signIn` mints a Pending Authorization, and `sessionStorage` is shared by every test in
        // the tab and outlives this one.
        store.clear()
        repository.close()
    }

    @Test
    fun the_popup_opens_before_sign_in_returns() {
        val opened = mutableListOf<String>()
        // The real channel, so its `arm` is under test too — not just the ViewModel's ordering.
        val channel = PopupRedirectChannel(
            openPopup = { url -> opened += url; thisWindow() },
            navigate = { opened += "navigated to $it" },
        )
        // No `Dispatchers.setMain`, deliberately: the production `Dispatchers.Main.immediate` is the
        // thing being measured, and a test dispatcher would answer a different question.
        val viewModel = MalSessionViewModel(repository, StartupRedirect.None)

        viewModel.signIn(channel, openUri = { opened += "fell back to $it" })

        assertEquals(1, opened.size, "signIn returned before reaching the browser: $opened")
        assertTrue(
            opened.single().startsWith("https://myanimelist.net/v1/oauth2/authorize"),
            // A fallback here means `arm` declined, which would take the popup path away entirely.
            "the armed channel has to be the one opening the browser: ${opened.single()}",
        )
    }
}

/** Nothing in this test should reach the network; a call that does is a failure worth seeing. */
private fun unreachableMal(): HttpClientFactory {
    val engine = MockEngine { respondError(HttpStatusCode.NotImplemented, "no request expected") }
    return HttpClientFactory { configure -> HttpClient(engine) { configure() } }
}
