package io.challenge_workshop.mal_ui.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.challenge_workshop.mal_ui.mal.ANDROID_REDIRECT_SCHEME

/**
 * Android's Redirect Capture, assembled from what this device actually has.
 *
 * Three arrangements, one per [BrowserPlan], and every one of them keeps the manifest's intent filter
 * in the picture — see [IntentRedirectChannel] and [AuthTabRedirectChannel] for why the best case
 * still needs the worst case behind it.
 *
 * This is the one function in the Android capture that touches the framework. Everything it decides
 * is decided by [browserPlan], and everything it wires is tested through the two channels' own
 * constructor parameters, because none of what is here can be exercised off a device.
 *
 * The lifecycle is the hosting Activity's, which is the only thing here that has to be: the inbox is
 * process-scoped precisely because a redirect can outlive any composition, and the channel is
 * remembered above the `SessionState` `when` so that starting a sign-in does not take it out of
 * composition mid-flow.
 */
@Composable
actual fun rememberAuthRedirectChannel(): AuthRedirectChannel {
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // `LocalActivity` and not `LocalContext`: a Custom Tab has to open in *this* task, and
    // `startActivity` on a non-Activity Context needs FLAG_ACTIVITY_NEW_TASK, which would put the tab
    // in a task of its own and strand the user there. Null only where there is no Activity at all — a
    // Compose preview — and the plan degrades accordingly.
    val activity = LocalActivity.current

    val results = remember { AuthTabResultInbox() }
    // Registered unconditionally, and before anything has decided whether an Auth Tab will be used,
    // because androidx requires exactly that: an `ActivityResultLauncher` must be registered before
    // the Activity reaches STARTED, so a registration behind an `if` would be missing after the
    // process death that this whole flow is most likely to be interrupted by.
    val launcher = rememberLauncherForActivityResult(
        remember { AuthTabIntent.AuthenticateUserResultContract() },
    ) { result ->
        results.deliver(AuthTabResult(result.resultCode, result.resultUri?.toString()))
    }

    val plan = remember(activity) {
        if (activity == null) {
            BrowserPlan.PlainView
        } else {
            browserPlan(
                customTabsPackage = CustomTabsClient.getPackageName(activity, null),
                isAuthTabSupported = { CustomTabsClient.isAuthTabSupported(activity, it) },
            )
        }
    }

    return remember(plan, activity, launcher, results, uriHandler, lifecycle) {
        plan.redirectChannel(
            inbox = AuthRedirectInbox.Shared,
            lifecycleStates = lifecycle.currentStateFlow,
            results = results,
            launchers = BrowserLaunchers(
                authTab = { url -> launcher.launchAuthTab(plan.browserPackageOrEmpty(), url) },
                customTab = { url -> activity?.launchCustomTab(plan.browserPackageOrEmpty(), url) },
                // Compose's `AndroidUriHandler`: a bare `ACTION_VIEW` on the Activity's own Context.
                // The last rung and never the primary one — it has no result channel and no way to
                // close the tab — but it is exactly the plain `ACTION_VIEW` the plan asks for, and
                // hand-rolling it would only reimplement it worse.
                plainly = uriHandler::openUri,
            ),
        )
    }
}

/**
 * The package the plan chose, or `""` for [BrowserPlan.PlainView] — which has none, and whose
 * launchers are never called.
 */
private fun BrowserPlan.browserPackageOrEmpty(): String = when (this) {
    is BrowserPlan.AuthTab -> browserPackage
    is BrowserPlan.CustomTab -> browserPackage
    BrowserPlan.PlainView -> ""
}

private fun ActivityResultLauncher<Intent>.launchAuthTab(browserPackage: String, url: String) {
    AuthTabIntent.Builder().build()
        // Without this the Intent goes to whatever resolves `ACTION_VIEW`, which may not be the
        // browser the plan was chosen from — and Auth Tab support was checked against that one
        // package.
        .also { it.intent.setPackage(browserPackage) }
        // The scheme alone, not the whole Redirect URI: it is what the browser watches for to decide
        // the auth flow is over and the tab can close itself.
        .launch(this, Uri.parse(url), ANDROID_REDIRECT_SCHEME)
}

private fun Activity.launchCustomTab(browserPackage: String, url: String) {
    CustomTabsIntent.Builder().build()
        .also { it.intent.setPackage(browserPackage) }
        .launchUrl(this, Uri.parse(url))
}
