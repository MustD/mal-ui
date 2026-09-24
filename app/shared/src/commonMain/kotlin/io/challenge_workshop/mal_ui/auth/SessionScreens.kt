package io.challenge_workshop.mal_ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.challenge_workshop.mal_ui.animelist.AnimeListFilters
import io.challenge_workshop.mal_ui.animelist.AnimeListSortMenu
import io.challenge_workshop.mal_ui.animelist.LoadMoreWhenNearEnd
import io.challenge_workshop.mal_ui.animelist.animeListItems
import io.challenge_workshop.mal_ui.animelist.contentMaxWidth
import io.challenge_workshop.mal_ui.animelist.gridCells
import io.challenge_workshop.mal_ui.screen.ScreenState

/**
 * Shown while the store is being read.
 *
 * Deliberately not a sign-in form: rendering one and then swapping it out for a signed-in screen is
 * exactly the flicker `Restoring` exists to prevent.
 */
@Composable
fun RestoringScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Sign-in. There is no password field and never will be: MAL supports only the authorization code
 * grant, so the password is typed on myanimelist.net and this app only ever sees a code.
 */
@Composable
fun SignInScreen(
    state: ScreenState.SignedOut,
    actions: SignInActions,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(modifier) {
        Text("Sign in to MyAnimeList", style = MaterialTheme.typography.headlineSmall)
        Text(
            // The copy is the state's, not this screen's: what separates the four Signed Out Reasons
            // is exactly what they say, and `:core`'s mapping test compares all four in one place on
            // four Targets.
            state.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(SIGNED_OUT_REASON_TAG),
        )

        OutlinedTextField(
            value = state.form.clientId,
            onValueChange = actions.onClientIdChange,
            label = { Text("Client ID") },
            singleLine = true,
            enabled = !state.form.busy,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    "From myanimelist.net/apiconfig. Prefilled from the last one used on this " +
                        "device, or from the build's `mal.clientId` — see local.properties.example.",
                )
            },
        )

        if (state.routing.usesRelay) {
            Text(
                "This build routes token and API calls through ${state.routing.endpoints.tokenEndpoint} " +
                    "because MAL sends no CORS headers to browsers. Run `./gradlew :server:run` first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Called straight from the click and not out of a `launch { }`: a web popup's user
            // activation is a timestamp window, and WebKit's is one second wide. A lambda hop is
            // synchronous, so routing this through an actions record does not spend any of it —
            // `PopupUserActivationTest` is what holds that to the production dispatcher.
            Button(onClick = actions.onSignIn, enabled = state.form.canStart) {
                Text("Sign in with MyAnimeList")
            }
            if (state.form.busy) CircularProgressIndicator(Modifier.padding(4.dp))
        }

        state.error?.let { ErrorCard("Sign-in failed", it) }
        state.form.error?.let { ErrorCard("Sign-in failed", it) }
    }
}

/**
 * The user is away on myanimelist.net.
 *
 * Paste-the-code stays visible throughout rather than appearing only on failure. It is the one
 * mechanism that works headless, behind a blocked popup, and with no Custom-Tabs browser, so it is a
 * modelled path — and the authorization URL has to be reachable by hand because no platform's
 * browser-opening call reliably reports whether it worked.
 */
@Composable
fun AuthorizingScreen(
    state: ScreenState.Authorizing,
    actions: AuthorizingActions,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(modifier) {
        Text("Waiting for MyAnimeList…", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Approve access in the browser. If you land on a page that will not load, copy the whole " +
                "address from the address bar and paste it below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(Modifier.fillMaxWidth())

        @Suppress("DEPRECATION")
        // `LocalClipboard` supersedes this, but its `ClipEntry` has no common constructor from text
        // in Compose 1.11 — a copy button through it would need three actuals to write a string.
        val clipboard = LocalClipboardManager.current
        OutlinedTextField(
            value = state.authorizationUrl,
            onValueChange = {},
            readOnly = true,
            label = { Text("Authorization URL") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Didn't open? Copy this and paste it into a browser.") },
            // Selecting a long URL out of a text field by hand is exactly the friction that makes
            // people give up on the fallback, and the fallback is the only mechanism that always
            // works. Never logged: under `plain` PKCE the code verifier is inside this string.
            trailingIcon = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(state.authorizationUrl)) }) {
                    Text("Copy")
                }
            },
        )

        HorizontalDivider()

        OutlinedTextField(
            value = state.form.pastedRedirect,
            onValueChange = actions.onPastedRedirectChange,
            label = { Text("Redirect URL or authorization code") },
            enabled = !state.form.busy,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = actions.onCompleteSignIn, enabled = state.form.canComplete) {
                Text("Complete sign-in")
            }
            TextButton(onClick = actions.onCancelSignIn, enabled = !state.form.busy) { Text("Cancel") }
            if (state.form.busy) CircularProgressIndicator(Modifier.padding(4.dp))
        }

        state.form.error?.let { ErrorCard("Could not complete the sign-in", it) }
    }
}

/**
 * The signed-in screen, which **is** the Anime List.
 *
 * **One scroll container, and it is lazy.** The Anime List is unbounded now — scrolling near its end
 * fetches the next page — and a lazy layout cannot be nested inside a scrolling [Column], so the
 * chrome around the list became items in the grid rather than the grid becoming a child of the
 * chrome. That is also what gives the paging trigger a `LazyGridState` to read the last-visible
 * index off, which is the one signal that means the same thing on all four Targets.
 *
 * **One `LazyVerticalGrid` for both Layouts.** The dense Layout is the same grid at one column, so
 * the Layout changes the column count and the width cap and nothing else — no second scroll state,
 * no second paging trigger, and no second copy of the five screen states.
 *
 * The Layout arrives on [state] like everything else. It is a remembered choice — read from the
 * store and written back there by `LayoutPreference` — so the toggle changes it by asking, not by
 * owning it. Switching it re-draws the entries already loaded and makes no request, which is why the
 * toggle in the bar is not disabled while a page is in flight and the other two controls are.
 *
 * **All of that chrome sits *above* the entries, and that is not a layout preference.** Anything
 * placed after them is unreachable on a real account: every scroll towards it enters the prefetch
 * zone, appends fifty more entries and pushes it further down, so it only arrives once the whole
 * list has been paged in. Above the entries it is always one scroll up, and scrolling up never
 * fetches anything.
 *
 * Everything that is *not* a query over the list has left the list entirely: the name, the Layout
 * toggle, Reload, Sign out and the debug panel are in [SignedInTopBar], which neither scrolls nor
 * competes with the entries. The filter row and the Sort Order stay between the bar and the list,
 * because those two are the query.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignedInScreen(
    state: ScreenState.SignedIn,
    actions: SignedInActions,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    // Whether the diagnostics dialog is over the screen. A dialog, not a destination — see
    // [SESSION_DIAGNOSTICS_TAG].
    var diagnosticsOpen by remember { mutableStateOf(false) }
    val list = state.list
    val layout = state.layout
    val contentWidth = Modifier.widthIn(max = layout.contentMaxWidth()).fillMaxWidth()

    // Armed on `loaded`, not on "there are entries": a first page can come back empty and still
    // carry a `paging.next`, and a pager that is not exhausted with no way left to ask it for more
    // is a list that has silently stopped. Disarmed once exhausted, so the trigger costs nothing at
    // the bottom of a finished list.
    LoadMoreWhenNearEnd(
        gridState = gridState,
        loadedCount = list.entries.size,
        revision = list.revision,
        enabled = list.loaded && !list.exhausted,
        onLoadMore = actions.onLoadMore,
    )

    // The replacement page has landed and the content underneath the user's scroll position has
    // been swapped out, so that position is into a list that no longer exists. Keyed on the pager's
    // revision rather than on the filter, because the scroll has to happen when the new page
    // *arrives*, not when the chip is tapped — the old entries are deliberately still on screen in
    // between.
    //
    // `requestScrollToItem`, not `scrollToItem`: it is applied by the very measure pass that first
    // lays the new entries out, so no frame is ever laid out with the new list at the old scroll
    // position. Suspending until after that frame instead would leave one — and a user who changed
    // filter from the bottom of a long list would be at the bottom of the new one for it, which is
    // exactly where `LoadMoreWhenNearEnd` fires and fetches a page nobody scrolled to.
    LaunchedEffect(list.revision) {
        if (list.revision > 0) gridState.requestScrollToItem(0)
    }

    // The screen tag is on this wrapper rather than on the list, because the list carries its own
    // and a second `testTag` would replace it.
    Column(
        modifier = modifier.fillMaxSize().safeContentPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SignedInTopBar(
            state = state,
            actions = actions,
            onShowDiagnostics = { diagnosticsOpen = true },
            modifier = Modifier.fillMaxWidth(),
        )
        // Outside the lazy grid, so both controls stay put while the list scrolls under them.
        // `contentWidth` is the grid's own cap, so they line up with the entries they act on
        // rather than running the full width of a desktop window — and so the dense Layout's
        // narrower list does not leave its filter row floating out over empty surface.
        // The padding goes *outside* the width cap, not inside it: padding applied after it would
        // spend 32dp of that cap and leave these controls inset from the very entries they act on —
        // the grid pads its own with `contentPadding`, which is outside their cap.
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).then(contentWidth)) {
            AnimeListFilters(
                selected = list.watchStatus,
                // Both are disabled by the same flag, because both go through the same reset: the
                // entries on screen are the previous query's until the replacement lands, so a live
                // control would invite a second pick against a list that has not changed yet.
                enabled = !list.loadingFirstPage,
                onSelect = actions.onSelectWatchStatus,
            )
            // Still a `FlowRow` with one child in it, now that the Layout toggle has gone to the
            // top app bar: the Sort Order button names its direction in words ("Last updated
            // (newest first)"), which is wider than a narrow phone, and a `FlowRow` is what lets it
            // take the line it needs rather than being clipped off the edge — with no size class
            // and nothing to keep in step with the four Targets.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                AnimeListSortMenu(
                    selected = list.sortOrder,
                    enabled = !list.loadingFirstPage,
                    onSelect = actions.onSelectSortOrder,
                )
            }
            // Above the list rather than at the bottom of it, so a failed sign-out, Reload or
            // profile reload is visible from where the user actually is — and outside the grid, so
            // it does not scroll away from the controls that caused it. Under the `FlowRow` rather
            // than in it: it is a card the width of the pane, not a control to lay out beside one.
            state.error?.let { ErrorCard("Something went wrong", it) }
        }
        LazyVerticalGrid(
            // The Layout is entirely this: how many columns the entries get, and how wide the whole
            // thing is allowed to be. Everything inside `animeListItems` is written once.
            columns = layout.gridCells(),
            // `weight`, not `fillMaxSize`: a child that fills the height inside a `Column` takes
            // the whole window and hangs the last entries of the list below the bottom of it,
            // because the filter row above has already taken its share.
            modifier = Modifier
                .weight(1f)
                .then(contentWidth)
                .testTag(ANIME_LIST_TAG),
            state = gridState,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            animeListItems(
                state = list,
                layout = layout,
                onRetry = actions.onRetry,
                // "Show all" is the same gesture as tapping the All chip, and goes through the same
                // reset — an empty slice's way out must not become a second way of changing filter.
                onShowAll = { actions.onSelectWatchStatus(null) },
            )
        }

        if (diagnosticsOpen) {
            SessionDiagnosticsDialog(
                state = state,
                actions = actions.diagnostics,
                onDismiss = { diagnosticsOpen = false },
            )
        }
    }
}

@Composable
private fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeContentPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.paneItem(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

/**
 * The width one column of this app's content gets: as wide as the window, up to a line length that
 * is still readable on a desktop or a browser maximised across a monitor.
 *
 * A modifier rather than a wrapper composable, because the signed-in screen's content is now lazy
 * items and there is no single node left to wrap.
 */
internal fun Modifier.paneItem(): Modifier = widthIn(max = PANE_MAX_WIDTH).fillMaxWidth()

/**
 * One line length, in one place. The Anime List's dense Layout caps itself at the same value — a
 * pane and a list of one-line rows are the same reading problem — and its card grid deliberately
 * does not. See `AnimeListLayout.contentMaxWidth`.
 */
internal val PANE_MAX_WIDTH = 560.dp

/** Internal, not private: the Anime List reuses it rather than growing an error card of its own. */
@Composable
internal fun ErrorCard(title: String, body: String) {
    MessageCard(
        title = title,
        body = body,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
internal fun MessageCard(title: String, body: String, container: Color, content: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun LabelledValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, overflow = TextOverflow.Ellipsis, maxLines = 1)
    }
}
