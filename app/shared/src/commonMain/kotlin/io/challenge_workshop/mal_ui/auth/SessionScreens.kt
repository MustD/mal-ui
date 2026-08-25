package io.challenge_workshop.mal_ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.challenge_workshop.mal_ui.animelist.AnimeListViewModel
import io.challenge_workshop.mal_ui.animelist.LoadMoreWhenNearEnd
import io.challenge_workshop.mal_ui.animelist.animeListItems
import io.challenge_workshop.mal_ui.session.SessionState
import io.challenge_workshop.mal_ui.session.SignedOutReason

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
    state: SessionState.SignedOut,
    viewModel: MalSessionViewModel,
    channel: AuthRedirectChannel,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    ScreenColumn(modifier) {
        Text("Sign in to MyAnimeList", style = MaterialTheme.typography.headlineSmall)
        Text(
            explain(state.reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(SIGNED_OUT_REASON_TAG),
        )

        OutlinedTextField(
            value = viewModel.clientId,
            onValueChange = viewModel::onClientIdChange,
            label = { Text("Client ID") },
            singleLine = true,
            enabled = !viewModel.busy,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    "From myanimelist.net/apiconfig. Prefilled from the last one used on this " +
                        "device, or from the build's `mal.clientId` — see local.properties.example.",
                )
            },
        )

        if (viewModel.usesRelay) {
            Text(
                "This build routes token and API calls through ${viewModel.endpoints.tokenEndpoint} " +
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
            // activation is a timestamp window, and WebKit's is one second wide.
            Button(
                onClick = { viewModel.signIn(channel, uriHandler::openUri) },
                enabled = viewModel.canStart,
            ) {
                Text("Sign in with MyAnimeList")
            }
            if (viewModel.busy) CircularProgressIndicator(Modifier.padding(4.dp))
        }

        state.error?.let { ErrorCard("Sign-in failed", it) }
        viewModel.error?.let { ErrorCard("Sign-in failed", it) }
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
    state: SessionState.Authorizing,
    viewModel: MalSessionViewModel,
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

        val authorizationUrl = viewModel.authorizationUrlFor(state.pending)
        @Suppress("DEPRECATION")
        // `LocalClipboard` supersedes this, but its `ClipEntry` has no common constructor from text
        // in Compose 1.11 — a copy button through it would need three actuals to write a string.
        val clipboard = LocalClipboardManager.current
        OutlinedTextField(
            value = authorizationUrl,
            onValueChange = {},
            readOnly = true,
            label = { Text("Authorization URL") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Didn't open? Copy this and paste it into a browser.") },
            // Selecting a long URL out of a text field by hand is exactly the friction that makes
            // people give up on the fallback, and the fallback is the only mechanism that always
            // works. Never logged: under `plain` PKCE the code verifier is inside this string.
            trailingIcon = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(authorizationUrl)) }) {
                    Text("Copy")
                }
            },
        )

        HorizontalDivider()

        OutlinedTextField(
            value = viewModel.pastedRedirect,
            onValueChange = viewModel::onPastedRedirectChange,
            label = { Text("Redirect URL or authorization code") },
            enabled = !viewModel.busy,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { viewModel.completeSignIn() }, enabled = viewModel.canComplete) {
                Text("Complete sign-in")
            }
            TextButton(onClick = viewModel::cancelSignIn, enabled = !viewModel.busy) { Text("Cancel") }
            if (viewModel.busy) CircularProgressIndicator(Modifier.padding(4.dp))
        }

        viewModel.error?.let { ErrorCard("Could not complete the sign-in", it) }
    }
}

/**
 * The signed-in screen, which **is** the Anime List.
 *
 * **One scroll container, and it is lazy.** The Anime List is unbounded now — scrolling near its end
 * fetches the next page — and a lazy list cannot be nested inside a scrolling [Column], so the
 * chrome around the list became items in the list rather than the list becoming a child of the
 * chrome. That is also what gives the paging trigger a `LazyListState` to read the last-visible
 * index off, which is the one signal that means the same thing on all four Targets.
 *
 * **All of that chrome sits *above* the entries, and that is not a layout preference.** Anything
 * placed after them is unreachable on a real account: every scroll towards it enters the prefetch
 * zone, appends fifty more entries and pushes it further down, so it only arrives once the whole
 * list has been paged in. Above the entries it is always one scroll up, and scrolling up never
 * fetches anything.
 *
 * The profile row and the debug panel are therefore both up there. Ticket 09 rehouses them into a
 * top app bar and an overflow menu — which is where they are heading anyway; until it does, neither
 * may be lost, because `SessionDebugPanel` is the only way a human ever sees the refresh path
 * execute.
 */
@Composable
fun SignedInScreen(
    state: SessionState.SignedIn,
    viewModel: MalSessionViewModel,
    animeList: AnimeListViewModel,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val list = animeList.state.collectAsStateWithLifecycle().value

    // Not in the ViewModel's `init`: the pager must only ask MAL for a list once there is a
    // signed-in screen to show one on. The pager itself ignores a repeat, so a recomposition
    // costs nothing.
    LaunchedEffect(Unit) { animeList.loadFirstPage() }
    // Armed on `loaded`, not on "there are entries": a first page can come back empty and still
    // carry a `paging.next`, and a pager that is not exhausted with no way left to ask it for more
    // is a list that has silently stopped. Disarmed once exhausted, so the trigger costs nothing at
    // the bottom of a finished list.
    LoadMoreWhenNearEnd(
        listState = listState,
        loadedCount = list.entries.size,
        enabled = list.loaded && !list.exhausted,
        onLoadMore = animeList::loadMore,
    )

    // The screen tag is on this wrapper rather than on the list, because the list carries its own
    // and a second `testTag` would replace it.
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .safeContentPadding()
                .testTag(ANIME_LIST_TAG),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.paneItem(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                state.user?.name ?: "Signed in",
                                style = MaterialTheme.typography.headlineSmall,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                            )
                            state.user?.let {
                                Text(
                                    "MAL id ${it.id}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // A refresh must not unmount this screen, so it shows as a spinner beside
                        // the name.
                        if (state.refreshing) CircularProgressIndicator(Modifier.padding(4.dp))
                        TextButton(onClick = viewModel::signOut, enabled = !viewModel.busy) {
                            Text("Sign out")
                        }
                    }
                    OutlinedButton(onClick = viewModel::refreshUser, enabled = !viewModel.busy) {
                        Text("Reload profile")
                    }
                    // Above the list rather than at the bottom of it, so a failed sign-out or
                    // profile reload is visible from where the user actually is.
                    viewModel.error?.let { ErrorCard("Something went wrong", it) }
                    SessionDebugPanel(viewModel)
                    HorizontalDivider()
                }
            }

            animeListItems(state = list, onRetry = animeList::retry, itemModifier = Modifier.paneItem())
        }
    }
}

/** `SignedOutReason` exists so this can say something specific instead of a bare "signed out". */
private fun explain(reason: SignedOutReason): String = when (reason) {
    SignedOutReason.NeverSignedIn ->
        "MAL has no password grant, so nothing is typed here — you approve access on " +
            "myanimelist.net and come straight back."

    SignedOutReason.UserSignedOut ->
        "Signed out. Your tokens have been deleted from this device."

    SignedOutReason.RefreshRejected ->
        "Your MyAnimeList session expired and could not be renewed, so you will need to " +
            "approve access again."

    SignedOutReason.AuthorizationFailed ->
        "That sign-in attempt did not complete. Starting again mints a fresh code."
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
internal fun Modifier.paneItem(): Modifier = widthIn(max = 560.dp).fillMaxWidth()

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
