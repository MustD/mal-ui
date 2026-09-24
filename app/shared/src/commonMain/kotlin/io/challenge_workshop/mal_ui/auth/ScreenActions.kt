package io.challenge_workshop.mal_ui.auth

import androidx.compose.runtime.Immutable
import io.challenge_workshop.mal_ui.animelist.AnimeListLayout
import io.challenge_workshop.mal_ui.animelist.AnimeListSortOrder
import io.challenge_workshop.mal_ui.animelist.LayoutPreference
import io.challenge_workshop.mal_ui.animelist.AnimeListRepository
import io.challenge_workshop.mal_ui.animelist.WatchStatus
import io.challenge_workshop.mal_ui.screen.ScreenState

/**
 * Everything a screen can *do*, held apart from [ScreenState], which is everything a screen can *be*.
 *
 * **The split is not tidiness, it is how Compose skips.** A composable is skipped when its arguments
 * are `equals` and stable, and a `data class` holding `() -> Unit` fields is neither: two lambdas
 * built in the same place on two recompositions are different objects. Folding these into
 * `ScreenState` would therefore make every emission — a page landing, a spinner starting — recompose
 * the whole signed-in screen, grid included, which is the cost the value was introduced to avoid.
 *
 * They are [Immutable] and built **once**, with `remember`, in `App()`. Rebuilt per recomposition
 * they would be exactly the unstable argument described above, and the annotation would be a lie.
 *
 * Not a property of the ViewModel: [SignedInActions] spans it, the Anime List and the Layout, and a
 * record any one of them exposed would have to reach into the others.
 */
@Immutable
data class ScreenActions(
    val signIn: SignInActions,
    val authorizing: AuthorizingActions,
    val signedIn: SignedInActions,
)

/**
 * The sign-in screen.
 *
 * [onSignIn] takes no arguments: the [AuthRedirectChannel] is bound where the ViewModels are, above
 * the routing `when`, so the channel never appears in a screen's interface. It has to be bound there
 * anyway — an Android `ActivityResultLauncher` can only be registered from composition, before the
 * Activity reaches `STARTED`, and a channel that left composition when this screen swapped for the
 * authorizing one would take that launcher with it.
 */
@Immutable
data class SignInActions(
    val onClientIdChange: (String) -> Unit,
    val onSignIn: () -> Unit,
)

/** The authorizing screen: Paste-the-code, and backing out. */
@Immutable
data class AuthorizingActions(
    val onPastedRedirectChange: (String) -> Unit,
    val onCompleteSignIn: () -> Unit,
    val onCancelSignIn: () -> Unit,
)

/**
 * The signed-in screen, which **is** the Anime List.
 *
 * This record is the reason the ViewModel exposes no actions record of its own: Sign out and the
 * diagnostics dialog come from the Session, Reload and the two query controls from the Anime List,
 * and the Layout from its preference.
 *
 * There is no "load the first page": the Anime List asks for it itself as the Session starts.
 */
@Immutable
data class SignedInActions(
    val onLoadMore: () -> Unit,
    val onRetry: () -> Unit,
    val onReload: () -> Unit,
    val onSelectWatchStatus: (WatchStatus?) -> Unit,
    val onSelectSortOrder: (AnimeListSortOrder) -> Unit,
    val onSelectLayout: (AnimeListLayout) -> Unit,
    val onSignOut: () -> Unit,
    val diagnostics: DiagnosticsActions,
)

/**
 * The three buttons in the session debug panel.
 *
 * Its own record rather than three more fields on [SignedInActions], because the panel is behind a
 * dialog that only opens deliberately — and because "Force 401" and "Reload profile" being one row
 * apart is the whole procedure for watching a refresh happen against real MAL.
 */
@Immutable
data class DiagnosticsActions(
    val onReloadDiagnostics: () -> Unit,
    val onRefreshUser: () -> Unit,
    val onForceExpireAccessToken: () -> Unit,
)

/**
 * Wires the ViewModel, the Anime List and the Layout to the three actions records, in one place so `App()` and the rendering
 * tests cannot drift about what a control does.
 *
 * Not a `@Composable` and not remembered here: the caller is what has to `remember` the result, since
 * the whole value of these records is being the *same object* across recompositions.
 *
 * @param openUri the browser-opening fallback for a target whose Redirect Capture declines to arm.
 * An armed [channel] opens the browser itself — on web that call *is* the popup.
 */
internal fun screenActions(
    viewModel: MalSessionViewModel,
    animeList: AnimeListRepository,
    layout: LayoutPreference,
    channel: AuthRedirectChannel,
    openUri: (String) -> Unit,
): ScreenActions = ScreenActions(
    signIn = SignInActions(
        onClientIdChange = viewModel::onClientIdChange,
        // Straight through, with no `launch` between the click and the channel: a web popup's user
        // activation is a timestamp window and WebKit's is one second wide.
        onSignIn = { viewModel.signIn(channel, openUri) },
    ),
    authorizing = AuthorizingActions(
        onPastedRedirectChange = viewModel::onPastedRedirectChange,
        onCompleteSignIn = { viewModel.completeSignIn() },
        onCancelSignIn = viewModel::cancelSignIn,
    ),
    signedIn = SignedInActions(
        onLoadMore = animeList::loadMore,
        onRetry = animeList::retry,
        onReload = animeList::reload,
        onSelectWatchStatus = animeList::setWatchStatus,
        onSelectSortOrder = animeList::setSortOrder,
        // Straight to the preference, which switches inside the click and writes behind it: a Layout
        // is a presentation choice, costs no request, and needs no coroutine of this caller's.
        onSelectLayout = layout::choose,
        onSignOut = viewModel::signOut,
        diagnostics = DiagnosticsActions(
            onReloadDiagnostics = { viewModel.reloadDiagnostics() },
            onRefreshUser = { viewModel.refreshUser() },
            onForceExpireAccessToken = { viewModel.forceExpireAccessToken() },
        ),
    ),
)
