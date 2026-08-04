package io.challenge_workshop.mal_ui.auth

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.challenge_workshop.mal_ui.mal.MalTokens
import io.challenge_workshop.mal_ui.mal.MalUser

/**
 * Login screen for the MyAnimeList OAuth2 + PKCE flow.
 *
 * There is deliberately no password field: MAL supports only the authorization code grant,
 * so the user types their password on myanimelist.net and this app only ever handles the
 * resulting authorization code.
 */
@Composable
fun MalLoginScreen(
    modifier: Modifier = Modifier,
    viewModel: MalLoginViewModel = viewModel { MalLoginViewModel() },
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeContentPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Sign in to MyAnimeList", style = MaterialTheme.typography.headlineSmall)
            Text(
                "MAL has no password grant, so credentials are never entered here — you " +
                        "approve access on myanimelist.net and paste the code back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CredentialsSection(viewModel)
            HorizontalDivider()
            AuthorizeSection(viewModel) { url -> uriHandler.openUri(url) }

            if (viewModel.authRequest != null) {
                HorizontalDivider()
                ExchangeSection(viewModel)
            }

            viewModel.error?.let { message ->
                MessageCard(
                    title = "Login failed",
                    body = message,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            viewModel.notice?.let { message ->
                MessageCard(
                    title = "OK",
                    body = message,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            val tokens = viewModel.tokens
            if (tokens != null) {
                HorizontalDivider()
                SessionSection(tokens = tokens, user = viewModel.user, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun CredentialsSection(viewModel: MalLoginViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("1. App credentials", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = viewModel.clientId,
            onValueChange = viewModel::onClientIdChange,
            label = { Text("Client ID") },
            singleLine = true,
            enabled = !viewModel.busy,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("From myanimelist.net/apiconfig") },
        )
        OutlinedTextField(
            value = viewModel.clientSecret,
            onValueChange = viewModel::onClientSecretChange,
            label = { Text("Client Secret (optional)") },
            singleLine = true,
            enabled = !viewModel.busy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Leave empty for App Type `other` — PKCE replaces the secret") },
        )
        OutlinedTextField(
            value = viewModel.redirectUri,
            onValueChange = viewModel::onRedirectUriChange,
            label = { Text("Redirect URI") },
            singleLine = true,
            enabled = !viewModel.busy,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Must exactly match a URL registered on the MAL app") },
        )
        if (viewModel.usesRelay) {
            Text(
                "This build routes token and API calls through ${viewModel.endpoints.tokenEndpoint} " +
                        "because MAL sends no CORS headers to browsers. Run `./gradlew :server:run` first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuthorizeSection(viewModel: MalLoginViewModel, openUri: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("2. Approve access on MAL", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { viewModel.startAuthorization()?.let(openUri) },
            enabled = viewModel.canStart,
        ) {
            Text(if (viewModel.authRequest == null) "Open MAL sign-in page" else "Restart with a new code")
        }
        viewModel.authRequest?.let { request ->
            // Read-only rather than hidden: on some targets the browser will not open by
            // itself, and the URL still needs to be reachable by hand.
            OutlinedTextField(
                value = request.authorizationUrl,
                onValueChange = {},
                readOnly = true,
                label = { Text("Authorization URL") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Didn't open? Select this and paste it into a browser.") },
            )
        }
    }
}

@Composable
private fun ExchangeSection(viewModel: MalLoginViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("3. Paste the redirect", style = MaterialTheme.typography.titleMedium)
        Text(
            "After approving, the browser will fail to load the redirect URL — that is expected. " +
                    "Copy the whole address from the address bar and paste it below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            Button(onClick = viewModel::completeLogin, enabled = viewModel.canComplete) {
                Text("Complete login")
            }
            if (viewModel.busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun SessionSection(tokens: MalTokens, user: MalUser?, viewModel: MalLoginViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Session", style = MaterialTheme.typography.titleMedium)
        user?.let {
            Text("Signed in as ${it.name} (id ${it.id})", style = MaterialTheme.typography.bodyLarge)
            it.joinedAt?.let { joined -> LabelledValue("Joined", joined) }
            it.location?.takeIf(String::isNotBlank)?.let { loc -> LabelledValue("Location", loc) }
        }
        LabelledValue("Token type", tokens.tokenType)
        LabelledValue("Expires in", "${tokens.expiresIn}s")
        // Only a prefix: enough to tell two tokens apart without putting a usable
        // credential on screen for a screenshot or screen share to capture.
        LabelledValue("Access token", tokens.accessToken.take(12) + "…")
        LabelledValue("Refresh token", tokens.refreshToken.take(12) + "…")
        Text(
            "Tokens are held in memory only — they are gone when the app closes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = viewModel::fetchMe, enabled = !viewModel.busy) {
                Text("Verify token")
            }
            OutlinedButton(onClick = viewModel::refreshTokens, enabled = !viewModel.busy) {
                Text("Refresh")
            }
            TextButton(onClick = viewModel::signOut, enabled = !viewModel.busy) {
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, overflow = TextOverflow.Ellipsis, maxLines = 1)
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
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
