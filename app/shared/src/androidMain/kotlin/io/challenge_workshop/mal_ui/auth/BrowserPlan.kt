package io.challenge_workshop.mal_ui.auth

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.Flow

/**
 * How this device is going to be sent to myanimelist.net, chosen once from what is installed.
 *
 * Three outcomes and not two, because the middle one is where most devices are and will stay for a
 * while: `AuthTabIntent` needs Chrome 137+, and on anything else it degrades to a plain Custom Tab
 * *silently* — so the difference has to be decided here rather than discovered from a result code.
 *
 * A WebView is deliberately not among them. RFC 8252 §8.12: the host app "can record every keystroke
 * entered in the login form", and §4 adds that it loses SSO with the system browser. Especially wrong
 * here, where the entire point of the architecture is that the password is only ever typed on
 * myanimelist.net.
 */
internal sealed interface BrowserPlan {

    /**
     * The best case: the redirect comes back through an `ActivityResultLauncher`, and a real result
     * code says whether the user finished or backed out.
     */
    data class AuthTab(val browserPackage: String) : BrowserPlan

    /**
     * An in-app tab with the URL on show, and the manifest's intent filter to catch the redirect.
     * What Firefox, Samsung Internet and Chrome below 137 get.
     */
    data class CustomTab(val browserPackage: String) : BrowserPlan

    /**
     * A bare `ACTION_VIEW` at whatever will take it — a kiosk build, a stripped image, or a device
     * with no Custom Tabs provider at all. Still capturable through the intent filter, and if nothing
     * handles it either, `IntentRedirectChannel` reports `Unsupported` and Paste-the-code takes over.
     */
    data object PlainView : BrowserPlan
}

/**
 * Picks the plan.
 *
 * Kept a function of two plain values rather than of a `Context`, because the choice is the part
 * worth pinning down and `PackageManager` queries are not: [customTabsPackage] is
 * `CustomTabsClient.getPackageName`, and [isAuthTabSupported] is `CustomTabsClient.isAuthTabSupported`
 * — which is per-package, and has to be asked about the package that will actually be launched.
 *
 * Both of those return nothing useful on API 30+ without the manifest's `<queries>` element, and no
 * exception is thrown when it is missing: sign-in simply degrades to Paste-the-code. `AndroidManifestTest`
 * is what guards it.
 */
internal fun browserPlan(
    customTabsPackage: String?,
    isAuthTabSupported: (String) -> Boolean,
): BrowserPlan = when {
    customTabsPackage == null -> BrowserPlan.PlainView
    isAuthTabSupported(customTabsPackage) -> BrowserPlan.AuthTab(customTabsPackage)
    else -> BrowserPlan.CustomTab(customTabsPackage)
}

/**
 * The three ways this app can put the user in a browser.
 *
 * One type rather than three parameters, because [BrowserPlan.redirectChannel] picks between them and
 * a caller that passed them positionally would have nothing stopping it from swapping two — which is
 * a silent bug: the sign-in still works, it just stops being an Auth Tab.
 *
 * Each is a `(String) -> Unit` over an authorization URL, and each may throw — a browser can be
 * uninstalled between the capability check and the click. Who catches what is
 * [IntentRedirectChannel.open]'s and [AuthTabRedirectChannel.open]'s business.
 */
internal class BrowserLaunchers(
    val authTab: (String) -> Unit,
    val customTab: (String) -> Unit,
    val plainly: (String) -> Unit,
)

/**
 * Builds the Redirect Capture this plan calls for.
 *
 * The other half of [browserPlan], and split from the composable that calls it for the same reason:
 * nothing here can be exercised on a device-less host, but *which launcher each plan reaches* can —
 * and getting that wrong is invisible, because every arrangement still completes a sign-in. It just
 * quietly stops being the good one.
 *
 * Every arrangement keeps [IntentRedirectChannel] in it. Even the Auth Tab needs it: Auth Tab
 * degrades to a plain Custom Tab silently, and the redirect then arrives as an `Intent` instead.
 */
internal fun BrowserPlan.redirectChannel(
    inbox: AuthRedirectInbox,
    lifecycleStates: Flow<Lifecycle.State>,
    results: AuthTabResultInbox,
    launchers: BrowserLaunchers,
): AuthRedirectChannel {
    val intentFilter = IntentRedirectChannel(
        inbox = inbox,
        lifecycleStates = lifecycleStates,
        // Not `customTab` under [BrowserPlan.AuthTab]: this launcher is only ever reached when the
        // Auth Tab failed to launch, and a Custom Tab would be asking the same browser that just
        // refused. The plain path is the one with a different answer available.
        launchBrowser = if (this is BrowserPlan.CustomTab) launchers.customTab else launchers.plainly,
    )

    return when (this) {
        is BrowserPlan.AuthTab -> AuthTabRedirectChannel(
            intentFilter = intentFilter,
            launchAuthTab = launchers.authTab,
            results = results,
        )

        // Nothing to race — the redirect can only come back through the intent filter.
        is BrowserPlan.CustomTab, BrowserPlan.PlainView -> intentFilter
    }
}
