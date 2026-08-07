package io.challenge_workshop.mal_ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.challenge_workshop.mal_ui.auth.AuthorizingScreen
import io.challenge_workshop.mal_ui.auth.MalSessionViewModel
import io.challenge_workshop.mal_ui.auth.RestoringScreen
import io.challenge_workshop.mal_ui.auth.SignInScreen
import io.challenge_workshop.mal_ui.auth.SignedInScreen
import io.challenge_workshop.mal_ui.session.SessionState
import org.koin.compose.viewmodel.koinViewModel

/**
 * The whole app: a `when` over [SessionState].
 *
 * No navigation library. A two-destination switch does not need one, and adding one alongside Koin
 * would have meant two unfamiliar failure modes at once. Add it when there is a third destination.
 *
 * The `when` is exhaustive over a sealed interface, so a new state cannot be added without the
 * compiler pointing here — which is what stops a state falling through to a blank screen.
 *
 * There is no `@Preview` any more: the screen resolves its ViewModel from Koin, so a preview without a
 * started Koin would fail at render time rather than usefully show anything.
 */
@Composable
fun App(viewModel: MalSessionViewModel = koinViewModel()) {
    MaterialTheme {
        Surface(modifier = Modifier) {
            when (val state = viewModel.state.collectAsStateWithLifecycle().value) {
                SessionState.Restoring -> RestoringScreen()
                is SessionState.SignedOut -> SignInScreen(state, viewModel)
                is SessionState.Authorizing -> AuthorizingScreen(state, viewModel)
                is SessionState.SignedIn -> SignedInScreen(state, viewModel)
            }
        }
    }
}
