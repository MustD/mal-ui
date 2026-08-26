package io.challenge_workshop.mal_ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.setSingletonImageLoaderFactory
import io.challenge_workshop.mal_ui.animelist.AnimeListViewModel
import io.challenge_workshop.mal_ui.animelist.malImageLoader
import io.challenge_workshop.mal_ui.auth.AuthRedirectChannel
import io.challenge_workshop.mal_ui.auth.AuthorizingScreen
import io.challenge_workshop.mal_ui.auth.MalSessionViewModel
import io.challenge_workshop.mal_ui.auth.RestoringScreen
import io.challenge_workshop.mal_ui.auth.SessionScreenTag
import io.challenge_workshop.mal_ui.auth.SignInScreen
import io.challenge_workshop.mal_ui.auth.SignedInScreen
import io.challenge_workshop.mal_ui.auth.rememberAuthRedirectChannel
import io.challenge_workshop.mal_ui.session.SessionState
import org.koin.compose.viewmodel.koinViewModel

/**
 * The whole app: a `when` over [SessionState].
 *
 * No navigation library. A two-destination switch does not need one, and adding one alongside Koin
 * would have meant two unfamiliar failure modes at once. Add it when there is a third destination.
 *
 * There is no `@Preview` any more: the screen resolves its ViewModel from Koin, so a preview without a
 * started Koin would fail at render time rather than usefully show anything.
 */
@Composable
fun App(
    viewModel: MalSessionViewModel = koinViewModel(),
    animeList: AnimeListViewModel = koinViewModel(),
) {
    // Coil's singleton, replaced here at the root because its default cannot fetch over the network
    // on the web Targets — see [malImageLoader]. `setSingletonImageLoaderFactory` remembers the
    // factory, so this is once per process and not once per recomposition. Deliberately *not* in
    // `initKoin()`: the loader needs a `PlatformContext`, which on Android is the one thing only a
    // composition (or an Activity) has, and each entry point starting Koin differently is exactly
    // how three of the four Targets would end up without one.
    setSingletonImageLoaderFactory { context -> malImageLoader(context) }

    MaterialTheme {
        Surface(modifier = Modifier) {
            SessionRoute(viewModel.state.collectAsStateWithLifecycle().value, viewModel, animeList)
        }
    }
}

/**
 * The routing itself, split from [App] so it can be rendered at a chosen [state] instead of only at
 * whichever one the repository happens to be in.
 *
 * The `when` is exhaustive over a sealed interface, so a new state cannot be added without the
 * compiler pointing here — which is what stops a state falling through to a blank screen. The
 * compiler only checks that each branch *exists*, though, so each one is tagged and
 * `SessionRouteTest` checks each one also renders something.
 *
 * No `modifier` parameter: both call sites are in this file and neither has anything to pass, and
 * one that existed only to be overwritten by `testTag` below would be a lie about the seam.
 *
 * [animeList] is a parameter rather than a `koinViewModel()` call inside the signed-in branch, so
 * this function can be rendered at a chosen state by a test without a started Koin — the same reason
 * [state] is one.
 *
 * The [AuthRedirectChannel] is remembered *here*, above the `when`, and not in [SignInScreen]: starting
 * a sign-in swaps that screen out for [AuthorizingScreen], and a channel that left composition at that
 * moment would take an Android `ActivityResultLauncher` with it.
 */
@Composable
internal fun SessionRoute(
    state: SessionState,
    viewModel: MalSessionViewModel,
    animeList: AnimeListViewModel,
) {
    val channel = rememberAuthRedirectChannel()

    when (state) {
        SessionState.Restoring ->
            RestoringScreen(Modifier.testTag(SessionScreenTag.Restoring.tag))

        is SessionState.SignedOut ->
            SignInScreen(state, viewModel, channel, Modifier.testTag(SessionScreenTag.SignIn.tag))

        is SessionState.Authorizing ->
            AuthorizingScreen(state, viewModel, Modifier.testTag(SessionScreenTag.Authorizing.tag))

        is SessionState.SignedIn ->
            SignedInScreen(state, viewModel, animeList, Modifier.testTag(SessionScreenTag.SignedIn.tag))
    }
}
