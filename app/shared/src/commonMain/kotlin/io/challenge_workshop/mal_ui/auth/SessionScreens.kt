package io.challenge_workshop.mal_ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    ScreenColumn(modifier) {
        Text("Sign in to MyAnimeList", style = MaterialTheme.typography.headlineSmall)
        Text(
            explain(state.reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = viewModel.clientId,
            onValueChange = viewModel::onClientIdChange,
            label = { Text("Client ID") },
            singleLine = true,
            enabled = !viewModel.busy,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text("From myanimelist.net/apiconfig. Prefilled from the build if `mal.clientId` is set.")
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
            Button(onClick = { viewModel.signIn(uriHandler::openUri) }, enabled = viewModel.canStart) {
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

        OutlinedTextField(
            value = viewModel.authorizationUrlFor(state.pending),
            onValueChange = {},
            readOnly = true,
            label = { Text("Authorization URL") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Didn't open? Select this and paste it into a browser.") },
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

/** The shell. No feature screens, and no MAL API call beyond `/users/@me`. */
@Composable
fun SignedInScreen(
    state: SessionState.SignedIn,
    viewModel: MalSessionViewModel,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(modifier) {
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
            // A refresh must not unmount this screen, so it shows as a spinner beside the name.
            if (state.refreshing) CircularProgressIndicator(Modifier.padding(4.dp))
            TextButton(onClick = viewModel::signOut, enabled = !viewModel.busy) { Text("Sign out") }
        }

        HorizontalDivider()

        Text(
            "Signed in. There is nothing else here yet — this build is a shell around the " +
                "authentication work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = viewModel::refreshUser, enabled = !viewModel.busy) {
            Text("Reload profile")
        }

        viewModel.error?.let { ErrorCard("Something went wrong", it) }

        HorizontalDivider()
        SessionDebugPanel(viewModel)
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
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ErrorCard(title: String, body: String) {
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
