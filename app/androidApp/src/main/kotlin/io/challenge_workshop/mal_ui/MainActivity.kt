package io.challenge_workshop.mal_ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.challenge_workshop.mal_ui.auth.AuthRedirectInbox

/**
 * The app's one Activity, and — because of the manifest's custom-scheme filter — the one place a
 * MyAnimeList redirect can land.
 *
 * It forwards that redirect to [AuthRedirectInbox] and does nothing else with it. Everything that
 * has an opinion about a redirect lives above this class and outlives it: the channel waiting for
 * one belongs to a composition, the Pending Authorization it completes belongs to a process-scoped
 * repository, and on a cold start neither exists yet when the Intent arrives.
 *
 * `launchMode="singleTop"` in the manifest is what makes this work at all. Under `standard` the
 * redirect Intent starts a *second* `MainActivity` with its own `ViewModelStore`, and nothing
 * throws — the redirect simply does nothing.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A cold start *is* a redirect delivery when the process was killed while the user was away.
        // Before `setContent`, so the inbox is already holding it when the ViewModel asks.
        AuthRedirectInbox.Shared.deliver(intent)

        setContent {
            App()
        }
    }

    /** The warm path, and the usual one: `singleTop` routes the redirect to the live instance. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Otherwise `getIntent()` keeps returning the launch Intent, and a recreation would be
        // handed that one instead of this.
        setIntent(intent)
        AuthRedirectInbox.Shared.deliver(intent)
    }
}
