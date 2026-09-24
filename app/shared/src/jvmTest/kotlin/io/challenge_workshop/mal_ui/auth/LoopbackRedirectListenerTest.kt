package io.challenge_workshop.mal_ui.auth

import io.challenge_workshop.mal_ui.mal.DESKTOP_LOOPBACK_PORT
import io.challenge_workshop.mal_ui.mal.DESKTOP_REDIRECT_URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The desktop Redirect Capture, over a real socket.
 *
 * Nothing is faked: the listener binds 18040 for real and the requests are real HTTP, because every
 * bug this ticket names — a wildcard bind, a `stop()` that deadlocks, a port that outlives a
 * cancelled flow — is invisible to a test that stops at the object boundary.
 *
 * `runBlocking` rather than `runTest`: the waits here are wall-clock ones enforced against a real
 * socket, and virtual time would only hide them.
 *
 * The port is fixed and cannot be made ephemeral — MAL does no port-lenient matching — so these
 * tests contend for one real resource. [tearDown] asserts each of them gives it back.
 */
class LoopbackRedirectListenerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        // A listener that outlives its flow is the failure that makes the *next* sign-in impossible,
        // so every test is held to giving the port back rather than merely to passing.
        assertTrue(
            awaitLoopbackPortFree(),
            "Port $DESKTOP_LOOPBACK_PORT was still bound when the test finished — a listener was " +
                "left running, and the next sign-in would fail to arm.",
        )
    }

    @Test
    fun a_redirect_that_lands_on_the_loopback_port_is_captured() = runBlocking {
        val listener = listener()
        assertEquals(ArmResult.Armed, listener.arm(DESKTOP_REDIRECT_URI))
        listener.open(authorizationUrl(state = "a-state"))

        val response = loopbackGet("$DESKTOP_REDIRECT_URI?code=an-auth-code&state=a-state")

        assertEquals(302, response.statusCode(), response.body())
        assertEquals(
            "/oauth/callback/done",
            response.headers().firstValue("Location").orElse(""),
            "The address bar holds the authorization code, so the URL the user is left on has to be " +
                "a different one.",
        )
        assertEquals(
            AuthRedirectResult.Received("$DESKTOP_REDIRECT_URI?code=an-auth-code&state=a-state"),
            listener.await(),
            "The redirect goes into `parseRedirect()` verbatim, so it has to arrive whole.",
        )
    }

    @Test
    fun the_page_the_user_lands_on_is_self_contained() = runBlocking {
        val listener = listener()
        listener.arm(DESKTOP_REDIRECT_URI)
        listener.open(authorizationUrl(state = "a-state"))
        loopbackGet("$DESKTOP_REDIRECT_URI?code=an-auth-code&state=a-state")

        val done = loopbackGet("http://127.0.0.1:$DESKTOP_LOOPBACK_PORT/oauth/callback/done")

        assertEquals(200, done.statusCode())
        // `HttpServer` sends no content type of its own, so an omitted one renders as plain text.
        assertEquals("text/html; charset=utf-8", done.headers().firstValue("Content-Type").orElse(""))
        assertEquals("no-store", done.headers().firstValue("Cache-Control").orElse(""))
        assertEquals("no-referrer", done.headers().firstValue("Referrer-Policy").orElse(""))
        assertTrue(done.headers().firstValue("Content-Length").isPresent, "No content length.")
        // `window.close()` does nothing on a tab the script did not open, so the copy has to read
        // correctly without it.
        assertTrue("close this tab" in done.body(), done.body())
        // Any subresource at all would send a `Referer` carrying the authorization code.
        assertFalse("://" in done.body(), "The page fetches something external:\n${done.body()}")

        assertTrue(listener.await() is AuthRedirectResult.Received)
    }

    /**
     * The race the 302 creates. Sending it resumes the awaiting coroutine, which stops the server —
     * and `stop` closes every open connection — so without a deliberate grace period the follow-up
     * request loses and the user watches a connection error at the end of a sign-in that worked.
     */
    @Test
    fun the_browser_can_follow_the_redirect_while_the_flow_completes() = runBlocking {
        val listener = listener()
        listener.arm(DESKTOP_REDIRECT_URI)
        listener.open(authorizationUrl(state = "a-state"))
        val awaiting = scope.async { listener.await() }

        val browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
        val landed = browser.send(
            HttpRequest.newBuilder(URI("$DESKTOP_REDIRECT_URI?code=an-auth-code&state=a-state"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, landed.statusCode())
        assertEquals("/oauth/callback/done", landed.uri().path, "The code is still in the address bar.")
        assertTrue("close this tab" in landed.body(), landed.body())
        assertTrue(withTimeout(10.seconds) { awaiting.await() } is AuthRedirectResult.Received)
    }

    @Test
    fun the_socket_is_not_reachable_from_a_non_loopback_address() = runBlocking {
        val listener = listener()
        listener.arm(DESKTOP_REDIRECT_URI)

        nonLoopbackAddress()?.let { lanAddress ->
            Socket().use { socket ->
                try {
                    socket.connect(InetSocketAddress(lanAddress, DESKTOP_LOOPBACK_PORT), 500)
                    fail(
                        "The callback answered on ${lanAddress.hostAddress}, so it is exposed to " +
                            "the LAN — which is what `HttpServer.create(InetSocketAddress(port), 0)` " +
                            "does, and it looks identical from the browser.",
                    )
                } catch (expected: Exception) {
                    // Refused or timed out. Either way nothing off this machine can reach it.
                }
            }
        }

        // ...and it really is bound, so the check above was not passing vacuously.
        assertEquals(400, loopbackGet("$DESKTOP_REDIRECT_URI?nothing=here").statusCode())
        listener.release()
    }

    @Test
    fun a_second_listener_reports_the_bind_failure_instead_of_proceeding() = runBlocking {
        val first = listener()
        assertEquals(ArmResult.Armed, first.arm(DESKTOP_REDIRECT_URI))

        val armed = listener().arm(DESKTOP_REDIRECT_URI)

        val failure = armed as? ArmResult.Failed ?: fail(
            "Arming a second time reported $armed, so a second sign-in would send the user to a " +
                "browser that nothing is listening for.",
        )
        assertTrue(
            DESKTOP_LOOPBACK_PORT.toString() in failure.message,
            "The message has to name the port — it is the only thing the user can act on: " +
                failure.message,
        )
        first.release()
    }

    @Test
    fun a_state_mismatch_is_rejected_without_ending_the_flow() = runBlocking {
        val listener = listener()
        listener.arm(DESKTOP_REDIRECT_URI)
        listener.open(authorizationUrl(state = "a-state"))

        // Any local process can hit this port. Treating that as fatal would let one abort the login.
        val forged = loopbackGet("$DESKTOP_REDIRECT_URI?code=a-forged-code&state=not-a-state")
        assertEquals(400, forged.statusCode())

        val real = loopbackGet("$DESKTOP_REDIRECT_URI?code=an-auth-code&state=a-state")
        assertEquals(302, real.statusCode(), "The listener stopped after the forged request.")
        assertEquals(
            AuthRedirectResult.Received("$DESKTOP_REDIRECT_URI?code=an-auth-code&state=a-state"),
            listener.await(),
        )
    }

    /**
     * `open` records the expected `state` before it launches the browser, so until it runs nobody
     * has been sent to MyAnimeList and nothing arriving can be this flow's. Accepting an unchecked
     * redirect in that window would let any local process feed the app a code.
     */
    @Test
    fun a_redirect_that_arrives_before_the_browser_was_opened_is_refused() = runBlocking {
        val listener = listener()
        listener.arm(DESKTOP_REDIRECT_URI)

        assertEquals(400, loopbackGet("$DESKTOP_REDIRECT_URI?code=an-auth-code&state=a-state").statusCode())

        listener.release()
    }

    @Test
    fun exactly_one_of_two_concurrent_redirects_is_accepted() = runBlocking {
        val listener = listener()
        listener.arm(DESKTOP_REDIRECT_URI)
        listener.open(authorizationUrl(state = "a-state"))

        val statuses = Collections.synchronizedList(mutableListOf<Int>())
        val together = CountDownLatch(1)
        val threads = (1..2).map { index ->
            Thread {
                together.await()
                statuses += loopbackGet("$DESKTOP_REDIRECT_URI?code=code-$index&state=a-state").statusCode()
            }.apply { start() }
        }
        together.countDown()
        threads.forEach { it.join(10_000) }

        assertEquals(2, statuses.size, "A request never finished: $statuses")
        assertEquals(
            1,
            statuses.count { it == 302 },
            "Exactly one code may ever be accepted: $statuses",
        )
        assertTrue(statuses.any { it >= 400 }, "The losing request was not refused: $statuses")
        assertTrue(listener.await() is AuthRedirectResult.Received)
    }

    @Test
    fun a_denied_authorization_is_captured_verbatim_and_told_to_the_browser_tab() = runBlocking {
        val listener = listener()
        listener.arm(DESKTOP_REDIRECT_URI)
        listener.open(authorizationUrl(state = "a-state"))

        val response = loopbackGet("$DESKTOP_REDIRECT_URI?error=access_denied&state=a-state")

        // The *redirect* worked; the authorization did not. A 4xx here would blame the browser.
        assertEquals(200, response.statusCode())
        assertTrue("Sign-in was not approved" in response.body(), response.body())
        assertTrue("access_denied" in response.body(), response.body())

        // `Received`, not `Failed`: a capture transports and never interprets, so the denial is
        // judged by `completeAuthorization` exactly as the same redirect pasted by hand would be.
        assertEquals(
            AuthRedirectResult.Received("$DESKTOP_REDIRECT_URI?error=access_denied&state=a-state"),
            listener.await(),
        )
    }

    @Test
    fun anything_but_a_get_or_a_head_is_rejected() = runBlocking {
        val listener = listener()
        listener.arm(DESKTOP_REDIRECT_URI)

        val posted = loopbackHttp.send(
            HttpRequest.newBuilder(URI("$DESKTOP_REDIRECT_URI?code=an-auth-code"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(405, posted.statusCode())
        assertEquals("GET, HEAD", posted.headers().firstValue("Allow").orElse(""))
        listener.release()
    }

    @Test
    fun an_unregistered_path_is_a_404_rather_than_a_capture() = runBlocking {
        val listener = listener()
        listener.arm(DESKTOP_REDIRECT_URI)

        assertEquals(404, loopbackGet("http://127.0.0.1:$DESKTOP_LOOPBACK_PORT/favicon.ico").statusCode())

        listener.release()
    }

    @Test
    fun cancelling_the_await_releases_the_port_for_the_next_attempt() = runBlocking {
        val listener = listener()
        val awaiting = scope.launch {
            listener.arm(DESKTOP_REDIRECT_URI)
            listener.await()
        }
        awaitPortBound()

        awaiting.cancelAndJoin()

        // No retry loop: `cancelAndJoin` waits out the teardown, and rebinding straight after
        // `stop(0)` works — which together are what make retrying a cancelled flow possible.
        val second = listener()
        assertEquals(
            ArmResult.Armed,
            second.arm(DESKTOP_REDIRECT_URI),
            "The port was still held after the awaiting coroutine was cancelled.",
        )
        second.release()
    }

    @Test
    fun a_cancellation_between_arming_and_awaiting_still_releases_the_port() = runBlocking {
        val armed = CountDownLatch(1)
        val stranded = scope.launch {
            listener().arm(DESKTOP_REDIRECT_URI)
            armed.countDown()
            // Stands in for `beginAuthorization()`: a real suspension point between `arm` and
            // `await`, and therefore a place a cancellation can land.
            delay(60_000)
        }
        assertTrue(armed.await(5, TimeUnit.SECONDS))

        stranded.cancelAndJoin()

        val second = listener()
        assertEquals(ArmResult.Armed, second.arm(DESKTOP_REDIRECT_URI))
        second.release()
    }

    /**
     * The composable remembers one channel for the life of the composition, so the *second* sign-in
     * of a session runs through an instance whose port is spent, whose code has already been
     * claimed, and whose result is already decided. Per-attempt state that lived on the channel
     * would answer that second sign-in with the first one's redirect.
     */
    @Test
    fun the_same_channel_captures_a_second_sign_in_after_the_first() = runBlocking {
        val listener = listener()

        listener.arm(DESKTOP_REDIRECT_URI)
        listener.open(authorizationUrl(state = "first-state"))
        loopbackGet("$DESKTOP_REDIRECT_URI?code=first-code&state=first-state")
        assertTrue(listener.await() is AuthRedirectResult.Received)

        assertEquals(
            ArmResult.Armed,
            listener.arm(DESKTOP_REDIRECT_URI),
            "The channel could not be armed a second time.",
        )
        listener.open(authorizationUrl(state = "second-state"))
        // The first sign-in's `state` must no longer be accepted, and its result must not be reused.
        assertEquals(400, loopbackGet("$DESKTOP_REDIRECT_URI?code=first-code&state=first-state").statusCode())
        assertEquals(302, loopbackGet("$DESKTOP_REDIRECT_URI?code=second-code&state=second-state").statusCode())

        assertEquals(
            AuthRedirectResult.Received("$DESKTOP_REDIRECT_URI?code=second-code&state=second-state"),
            listener.await(),
        )
    }

    @Test
    fun giving_up_waiting_hands_the_flow_back_to_paste_the_code() = runBlocking {
        val listener = listener(timeout = 200.milliseconds)
        listener.arm(DESKTOP_REDIRECT_URI)
        listener.open(authorizationUrl(state = "a-state"))

        // `Unsupported`, not `Failed`: the ViewModel's handling of it leaves the authorization URL
        // and the paste field on screen, which is exactly what a user who took too long needs.
        assertEquals(AuthRedirectResult.Unsupported, withTimeout(10.seconds) { listener.await() })
    }

    @Test
    fun awaiting_a_listener_that_never_armed_returns_rather_than_parking_forever() = runBlocking {
        assertEquals(
            AuthRedirectResult.Unsupported,
            withTimeout(5.seconds) { listener().await() },
        )
    }

    @Test
    fun opening_the_browser_never_blocks_the_caller_and_never_gates_the_flow() = runBlocking {
        val launched = CountDownLatch(1)
        // Stands in for `gtk_show_uri`, which has open hang reports (JDK-8267572, JDK-8275494) —
        // and `open` is called on desktop from the Swing dispatcher.
        val listener = listener(launchBrowser = { launched.countDown(); Thread.sleep(3_000) })
        listener.arm(DESKTOP_REDIRECT_URI)

        val elapsed = measureTimeMillis { listener.open(authorizationUrl(state = "a-state")) }

        assertTrue(elapsed < 1_000, "`open` blocked its caller for ${elapsed}ms.")
        assertTrue(launched.await(5, TimeUnit.SECONDS), "The browser was never launched.")
        // A launch that hangs must not stop the redirect being captured: `xdg-open`'s exit code is
        // never checked, so a silent failure is indistinguishable from a success.
        assertEquals(302, loopbackGet("$DESKTOP_REDIRECT_URI?code=an-auth-code&state=a-state").statusCode())
        assertTrue(listener.await() is AuthRedirectResult.Received)
    }

    @Test
    fun a_browser_launch_that_throws_does_not_take_the_flow_down() = runBlocking {
        val listener = listener(launchBrowser = { error("no browser on this machine") })
        listener.arm(DESKTOP_REDIRECT_URI)

        listener.open(authorizationUrl(state = "a-state"))

        assertEquals(302, loopbackGet("$DESKTOP_REDIRECT_URI?code=an-auth-code&state=a-state").statusCode())
        assertTrue(listener.await() is AuthRedirectResult.Received)
    }

    @Test
    fun a_redirect_uri_this_target_cannot_listen_on_is_unsupported_rather_than_failed() = runBlocking {
        // Not an error card: `Unsupported` is the modelled Paste-the-code path.
        assertEquals(
            ArmResult.Unsupported,
            listener().arm("io.challenge-workshop.malui://oauth/callback"),
        )
    }

    @Test
    fun a_redirect_uri_that_is_not_loopback_is_refused_rather_than_bound() = runBlocking {
        // TEST-NET-1, so no DNS is involved. Binding a routable address would put the authorization
        // code on the network, which is the one thing this listener must never do.
        val armed = listener().arm("http://192.0.2.1:$DESKTOP_LOOPBACK_PORT/oauth/callback")

        assertNotEquals(ArmResult.Armed, armed)
        assertTrue(armed is ArmResult.Failed, "Reported as $armed")
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun listener(
        timeout: Duration = 5.seconds,
        launchBrowser: (String) -> Unit = {},
    ) = LoopbackRedirectListener(scope, launchBrowser, timeout)

    /**
     * Releases a listener a test armed but deliberately never drove to a result. Cancelling the
     * `await` is the only teardown path a channel has, by design.
     */
    private suspend fun LoopbackRedirectListener.release() {
        // UNDISPATCHED so `await` is certainly entered before the cancellation lands. A coroutine
        // cancelled before its body ever ran would release nothing, which is a property of `launch`
        // and not of the listener.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { await() }.cancelAndJoin()
    }

    private fun authorizationUrl(state: String): String =
        "https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=an-id" +
            "&code_challenge=a-verifier&code_challenge_method=plain&state=$state" +
            "&redirect_uri=$DESKTOP_REDIRECT_URI"

    private fun awaitPortBound() {
        repeat(250) {
            if (!loopbackPortIsFree()) return
            Thread.sleep(20)
        }
    }

    /**
     * A routable address of this machine, or null on a host that has none — a container, an offline
     * laptop — where there is nothing for the LAN-exposure check to aim at.
     */
    private fun nonLoopbackAddress(): InetAddress? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
}
