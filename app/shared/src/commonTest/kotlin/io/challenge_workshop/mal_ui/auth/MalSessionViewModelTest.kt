@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui.auth

import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.mal.MalTokens
import io.challenge_workshop.mal_ui.mal.MalUser
import io.challenge_workshop.mal_ui.session.FakeKeyValueStore
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ACCESS = "a-very-secret-access-token"
private const val REFRESH = "a-very-secret-refresh-token"
private const val VERIFIER = "a-very-secret-code-verifier"

/**
 * The ViewModel's *surface*. Its behaviour is tested at the repository level in `:core`, where it runs
 * without a Compose runtime; what is left to check here is what the UI can see.
 */
class MalSessionViewModelTest {

    private lateinit var store: JsonTokenStore
    private lateinit var repository: MalSessionRepository

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which no test platform provides by default.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = JsonTokenStore(FakeKeyValueStore())
        repository = MalSessionRepository(store, initialConfig = MalAuthConfig(clientId = "prefilled"))
    }

    @AfterTest
    fun tearDown() {
        repository.close()
        Dispatchers.resetMain()
    }

    @Test
    fun nothing_the_ui_can_see_carries_a_token_a_code_or_a_verifier() = runTest {
        store.writeSession(MalTokens("Bearer", 2_415_600, ACCESS, REFRESH), MalUser(1, "someone"))
        store.writePending(VERIFIER, "the-state", "the-redirect", "the-client")
        val viewModel = MalSessionViewModel(repository)
        viewModel.reloadDiagnostics()

        val exposed = listOf(
            viewModel.state.value,
            viewModel.diagnostics,
            viewModel.clientId,
            viewModel.pastedRedirect,
            viewModel.redirectUri,
            viewModel.error,
        ).joinToString(" ") { it.toString() }

        assertFalse(ACCESS in exposed, exposed)
        assertFalse(REFRESH in exposed, exposed)
    }

    @Test
    fun the_client_id_field_is_prefilled_from_the_build_time_default() {
        assertEquals("prefilled", MalSessionViewModel(repository).clientId)
    }

    @Test
    fun editing_the_client_id_reaches_the_repository_config() {
        val viewModel = MalSessionViewModel(repository)

        viewModel.onClientIdChange("  typed-by-hand  ")

        assertEquals("typed-by-hand", repository.config.value.clientId)
    }

    @Test
    fun the_view_model_restores_the_session_on_first_construction() = runTest {
        store.writeSession(MalTokens("Bearer", 2_415_600, ACCESS, REFRESH), MalUser(1, "someone"))

        MalSessionViewModel(repository)

        assertEquals(SessionState.SignedIn(MalUser(1, "someone")), repository.state.value)
    }

    @Test
    fun a_second_view_model_does_not_re_run_the_restore() = runTest {
        MalSessionViewModel(repository)
        repository.signOut()

        MalSessionViewModel(repository)

        // A recreated ViewModel must not resurrect a Session the user just ended.
        assertTrue(repository.state.value is SessionState.SignedOut)
    }

    @Test
    fun signing_in_is_blocked_until_a_client_id_is_present() {
        val viewModel = MalSessionViewModel(
            MalSessionRepository(store, initialConfig = MalAuthConfig(clientId = "")),
        )

        assertFalse(viewModel.canStart)
        viewModel.onClientIdChange("something")
        assertTrue(viewModel.canStart)
    }
}
