@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui.auth

import androidx.browser.auth.AuthTabIntent
import androidx.lifecycle.Lifecycle
import io.challenge_workshop.mal_ui.mal.ANDROID_REDIRECT_URI
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which way the user gets sent to MyAnimeList.
 *
 * Three outcomes rather than two, because the middle one is the common case for years to come: Auth
 * Tab needs Chrome 137+, and a browser without it still gives a Custom Tab — an in-app tab with the
 * URL visible, which is both nicer and safer than handing the whole authorization URL to whatever
 * `ACTION_VIEW` resolves to.
 *
 * Pure by construction: the two things this needs from the framework — the ranked Custom Tabs
 * package and whether it supports Auth Tab — are the parameters. The second half of the file is
 * about what each plan then *builds*, which is the part that fails invisibly: every arrangement
 * completes a sign-in, so a swapped launcher costs the good behaviour and reports nothing.
 *
 * Robolectric for the `Uri` parsing inside the channels the second half assembles.
 */
@RunWith(RobolectricTestRunner::class)
class BrowserPlanTest {

    @Test
    fun a_browser_that_supports_auth_tab_gets_the_auth_tab() {
        assertEquals(
            BrowserPlan.AuthTab("com.android.chrome"),
            browserPlan(customTabsPackage = "com.android.chrome", isAuthTabSupported = { true }),
        )
    }

    @Test
    fun a_browser_without_auth_tab_support_still_gets_a_custom_tab() {
        // Firefox, Samsung Internet, or Chrome below 137. The redirect comes back through the
        // manifest's intent filter instead of through a result code — which is why that filter
        // stays after this ticket rather than being replaced by it.
        assertEquals(
            BrowserPlan.CustomTab("org.mozilla.firefox"),
            browserPlan(customTabsPackage = "org.mozilla.firefox", isAuthTabSupported = { false }),
        )
    }

    @Test
    fun no_custom_tabs_browser_at_all_falls_through_to_a_plain_view() {
        // A kiosk build, a stripped image, or a missing `<queries>` element — see CLAUDE.md for the
        // last one, which is why `AndroidManifestTest` guards it. `ACTION_VIEW` may still resolve
        // to something, and if it does not, `IntentRedirectChannel` reports `Unsupported` and
        // Paste-the-code takes over.
        assertEquals(
            BrowserPlan.PlainView,
            browserPlan(customTabsPackage = null, isAuthTabSupported = { true }),
        )
    }

    @Test
    fun the_auth_tab_check_is_asked_about_the_browser_that_will_be_used() {
        // `isAuthTabSupported` is per-package, and asking it about the wrong one is how an Auth Tab
        // gets launched at a browser that will silently degrade — or skipped for one that would
        // have worked.
        val asked = mutableListOf<String>()

        browserPlan(customTabsPackage = "com.android.chrome") { asked += it; true }

        assertEquals(listOf("com.android.chrome"), asked)
    }

    // --- What each plan builds ---

    private val inbox = AuthRedirectInbox()
    private val lifecycle = MutableStateFlow(Lifecycle.State.RESUMED)
    private val results = AuthTabResultInbox()
    private val launched = mutableListOf<String>()

    /** Each launcher tagged, so a swap between two of them is an assertion failure and not a shrug. */
    private val launchers = BrowserLaunchers(
        authTab = { launched += "authTab:$it" },
        customTab = { launched += "customTab:$it" },
        plainly = { launched += "plainly:$it" },
    )

    private fun BrowserPlan.channel() = redirectChannel(inbox, lifecycle, results, launchers)

    @Test
    fun the_auth_tab_plan_launches_an_auth_tab_and_honours_its_result_code() = runTest {
        val channel = BrowserPlan.AuthTab("com.android.chrome").channel()
        channel.arm(ANDROID_REDIRECT_URI)

        channel.open(authorizationUrl("our-state"))
        val capture = async { channel.await() }
        runCurrent()
        results.deliver(AuthTabResult(AuthTabIntent.RESULT_OK, androidRedirect("our-state")))

        assertEquals(listOf("authTab:${authorizationUrl("our-state")}"), launched)
        // Behaviour and not `assertIs`: what the Auth Tab plan is *for* is that a result code can
        // finish a sign-in, and that is what would be lost if this returned the bare intent filter.
        assertEquals(AuthRedirectResult.Received(androidRedirect("our-state")), capture.await())
    }

    @Test
    fun the_custom_tab_plan_launches_a_custom_tab_and_waits_on_the_intent_filter() = runTest {
        val channel = BrowserPlan.CustomTab("org.mozilla.firefox").channel()
        channel.arm(ANDROID_REDIRECT_URI)

        channel.open(authorizationUrl("our-state"))
        val capture = async { channel.await() }
        runCurrent()

        assertEquals(listOf("customTab:${authorizationUrl("our-state")}"), launched)

        // There is no Auth Tab in this arrangement, so a result code means nothing here — the
        // redirect has to come back as an `Intent`.
        results.deliver(AuthTabResult(AuthTabIntent.RESULT_OK, androidRedirect("our-state")))
        runCurrent()
        assertTrue(capture.isActive, "a Custom Tab has no result code to honour")

        inbox.deliver(redirectIntent("our-state"))
        assertEquals(AuthRedirectResult.Received(androidRedirect("our-state")), capture.await())
    }

    @Test
    fun the_plain_view_plan_opens_the_url_plainly_and_waits_on_the_intent_filter() = runTest {
        val channel = BrowserPlan.PlainView.channel()
        channel.arm(ANDROID_REDIRECT_URI)

        channel.open(authorizationUrl("our-state"))
        val capture = async { channel.await() }
        runCurrent()

        assertEquals(listOf("plainly:${authorizationUrl("our-state")}"), launched)

        inbox.deliver(redirectIntent("our-state"))
        assertEquals(AuthRedirectResult.Received(androidRedirect("our-state")), capture.await())
    }

    @Test
    fun an_auth_tab_that_fails_to_launch_falls_through_plainly_and_not_to_a_custom_tab() = runTest {
        // Asking the same browser that just refused an Auth Tab for a Custom Tab has no new answer in
        // it. The plain path resolves against everything installed, which does.
        val channel = BrowserPlan.AuthTab("com.android.chrome").redirectChannel(
            inbox, lifecycle, results,
            BrowserLaunchers(
                authTab = { throw android.content.ActivityNotFoundException("no Auth Tab") },
                customTab = { launched += "customTab:$it" },
                plainly = { launched += "plainly:$it" },
            ),
        )
        channel.arm(ANDROID_REDIRECT_URI)

        channel.open(authorizationUrl("our-state"))

        assertEquals(listOf("plainly:${authorizationUrl("our-state")}"), launched)
    }
}
