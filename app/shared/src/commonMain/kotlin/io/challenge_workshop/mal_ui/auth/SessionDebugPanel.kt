package io.challenge_workshop.mal_ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.challenge_workshop.mal_ui.getPlatform

/**
 * Diagnostics for the signed-in state.
 *
 * Everything in here answers a question that is otherwise unanswerable from inside a running build: a
 * misrouted web build silently talks to the wrong host, a Redirect URI mismatch reports as a 401 about
 * the Client ID, and nothing in a shell-only app ever crosses the one-hour access-token boundary.
 *
 * **No token value ever appears here.** [io.challenge_workshop.mal_ui.session.SessionDiagnostics] has
 * no field that could carry one, so that stays true however this panel is edited later.
 *
 * It no longer carries its own expand/collapse. It is opened from the top app bar's overflow menu
 * into [SessionDiagnosticsDialog], and that menu entry is the disclosure — a dialog opening onto a
 * collapsed panel would be two taps to say one thing. "Force 401" is still behind that one tap,
 * which is what keeps it off the screen a stray finger is on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionDebugPanel(viewModel: MalSessionViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

        // A `FlowRow`, because three buttons of this width do not fit a phone side by side and a
        // `Row` would clip the last of them off the edge with no way to reach it.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = viewModel::reloadDiagnostics, enabled = !viewModel.busy) {
                Text("Reload diagnostics")
            }
            // Here rather than on the screen, and next to "Force 401" rather than anywhere else:
            // reloading the profile is the request that makes a forced 401 refresh, and the two
            // being one row apart is the whole procedure.
            OutlinedButton(onClick = viewModel::refreshUser, enabled = !viewModel.busy) {
                Text("Reload profile")
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
