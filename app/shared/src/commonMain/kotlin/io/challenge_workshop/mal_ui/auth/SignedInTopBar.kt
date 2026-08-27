package io.challenge_workshop.mal_ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.challenge_workshop.mal_ui.animelist.AnimeListLayout
import io.challenge_workshop.mal_ui.animelist.AnimeListLayoutToggle
import io.challenge_workshop.mal_ui.animelist.AnimeListViewModel
import io.challenge_workshop.mal_ui.session.SessionState

/**
 * The chrome over the signed-in screen: who is signed in, how the list is drawn, and everything that
 * is not a query over it.
 *
 * It exists because the Anime List **is** the signed-in screen now. Everything that was on that
 * screen before — the name, Sign out, the debug panel — had nowhere left to sit: above the list they
 * were the first thing between the user and the thing they came for, and anywhere below the entries
 * they would be unreachable on a real account, because every scroll towards them enters the prefetch
 * zone and appends fifty more. A bar is the one place on this screen that neither scrolls nor
 * competes with the list.
 *
 * **The three menu entries are not a menu's worth of features, they are the three things the Anime
 * List taking over could otherwise have cost.** Reload is the way to pick up a change made on
 * myanimelist.net, Sign out is the only way back to the sign-in screen, and Session diagnostics is
 * the only way a human ever sees the refresh path execute. Nothing was deleted to make room.
 *
 * **Words, not glyphs, on the overflow button.** This project pulls in no Material icon dependency —
 * see `AnimeListLayout.layoutLabel` — and a three-dot glyph drawn by hand for four Targets would be
 * a `Canvas` apiece to say what one word says.
 *
 * `state.refreshing` is the **Session** refreshing, not the list: it shows as a spinner beside the
 * name precisely because a refresh must not unmount this screen. The list's own loading lives in the
 * list.
 *
 * Takes the two ViewModels rather than a parameter per control, which is the shape every other
 * screen in this package has — and the alternative decomposes into nine parameters, six of which are
 * one of the two ViewModels spelled out a field at a time. [onShowDiagnostics] is the exception
 * because the dialog is the *caller's* state and nothing here can own it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignedInTopBar(
    state: SessionState.SignedIn,
    viewModel: MalSessionViewModel,
    animeList: AnimeListViewModel,
    layout: AnimeListLayout,
    onShowDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier.testTag(SESSION_TOP_BAR_TAG),
        // Zero, because the caller has already applied `safeContentPadding()` to the whole screen.
        // The bar's default insets would consume the same status bar a second time and leave the
        // title floating below it.
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
            Text(
                state.user?.name ?: "Signed in",
                // A MAL username has no length this bar can rely on, and a bar that grew a second
                // line for one would push the list down by exactly as much.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(SESSION_USER_NAME_TAG),
            )
        },
        actions = {
            if (state.refreshing) CircularProgressIndicator(Modifier.padding(4.dp).size(20.dp))
            // In the bar rather than beside the Sort Order, because it is the one control on this
            // screen that is not a query: it re-draws the entries already loaded and asks MAL for
            // nothing, which is also why it is never disabled while a page is in flight.
            AnimeListLayoutToggle(selected = layout, onSelect = animeList::setLayout)
            // The menu is anchored to this `Box` rather than to the bar, so it opens under the
            // button that summoned it instead of at the corner of the window.
            Box {
                TextButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag(SESSION_MENU_BUTTON_TAG),
                ) {
                    Text("More")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.testTag(SESSION_MENU_TAG),
                ) {
                    // Each entry closes the menu before it acts. Leaving it open over a screen that
                    // has just started replacing itself would put a second tap one pixel away from
                    // the first.
                    DropdownMenuItem(
                        text = { Text("Reload") },
                        // The same reset the filter and the Sort Order go through, with neither of
                        // them changed — so it refetches the list on screen from `offset=0` rather
                        // than the default one.
                        onClick = {
                            menuOpen = false
                            animeList.reload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Sign out") },
                        enabled = !viewModel.busy,
                        onClick = {
                            menuOpen = false
                            viewModel.signOut()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Session diagnostics") },
                        onClick = {
                            menuOpen = false
                            onShowDiagnostics()
                        },
                    )
                }
            }
        },
    )
}

/**
 * [SessionDebugPanel] in a dialog, which is where the overflow menu's last entry opens it.
 *
 * A dialog and **not a third destination** — see [SESSION_DIAGNOSTICS_TAG]. The signed-in screen is
 * still underneath, with its scroll position and its loaded pages intact.
 *
 * The menu entry is the panel's disclosure now, which is why the panel itself no longer carries one —
 * a dialog that opened onto a collapsed panel would be two taps to say one thing.
 *
 * Scrollable, because the panel is a dozen diagnostic lines and a phone in landscape is shorter than
 * they are.
 *
 * The user's MAL id is here rather than in the bar because the bar has one line and the name has to
 * have it. The id is the half of the old profile row nobody reads until something is wrong — two
 * accounts on one device, or a name that was renamed — which is the definition of a diagnostic.
 */
@Composable
fun SessionDiagnosticsDialog(
    state: SessionState.SignedIn,
    viewModel: MalSessionViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(SESSION_DIAGNOSTICS_TAG),
        title = { Text("Session diagnostics") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabelledValue(
                    "Signed in as",
                    state.user?.let { "${it.name} (MAL id ${it.id})" } ?: "—",
                )
                SessionDebugPanel(viewModel)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
