package io.challenge_workshop.mal_ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.challenge_workshop.mal_ui.getPlatform

/**
 * Collapsed diagnostics for the signed-in state.
 *
 * Everything in here answers a question that is otherwise unanswerable from inside a running build: a
 * misrouted web build silently talks to the wrong host, a Redirect URI mismatch reports as a 401 about
 * the Client ID, and nothing in a shell-only app ever crosses the one-hour access-token boundary.
 *
 * **No token value ever appears here.** [io.challenge_workshop.mal_ui.session.SessionDiagnostics] has
 * no field that could carry one, so that stays true however this panel is edited later.
 */
@Composable
fun SessionDebugPanel(viewModel: MalSessionViewModel, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide session diagnostics" else "Session diagnostics")
        }

        if (!expanded) return@Column

        LabelledValue("Target", getPlatform().name)
        LabelledValue("Token endpoint", viewModel.endpoints.tokenEndpoint)
        LabelledValue("API base", viewModel.endpoints.apiBaseUrl)
        LabelledValue("Redirect URI", viewModel.redirectUri)
        LabelledValue("Via relay", if (viewModel.usesRelay) "yes" else "no — MAL directly")

        val diagnostics = viewModel.diagnostics
        if (diagnostics == null) {
            Text(
                "No stored Session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LabelledValue("Access token obtained", "${diagnostics.obtainedAtEpochMs} ms epoch")
            LabelledValue("Access token age", "${diagnostics.ageMillis / 1000}s")
            LabelledValue("Access token length", "${diagnostics.accessTokenLength} chars")
            LabelledValue("Refresh token stored", if (diagnostics.hasRefreshToken) "yes" else "no")
            if (diagnostics.accessTokenIsDeliberatelyInvalid) {
                Text(
                    "The access token has been deliberately invalidated. The next request should " +
                        "get a real 401 from MAL and refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = viewModel::reloadDiagnostics, enabled = !viewModel.busy) {
                Text("Reload")
            }
            // The only way a human ever sees the refresh path execute against real MAL: a shell-only
            // app never sits open for the hour it would otherwise take.
            OutlinedButton(onClick = viewModel::forceExpireAccessToken, enabled = !viewModel.busy) {
                Text("Force 401")
            }
        }
        Text(
            "Force 401 writes an invalid access token, keeps the refresh token, and drops Ktor's " +
                "cached copy. Reload profile afterwards to watch the refresh happen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
