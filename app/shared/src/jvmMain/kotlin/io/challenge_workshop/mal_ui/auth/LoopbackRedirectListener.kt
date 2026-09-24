package io.challenge_workshop.mal_ui.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.challenge_workshop.mal_ui.mal.MalAuthException
import io.challenge_workshop.mal_ui.mal.parseRedirect
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop's Redirect Capture: a loopback HTTP listener on the one port the Redirect URI may name.
 *
 * `com.sun.net.httpserver.HttpServer` rather than a real server framework — it is not deprecated, it
 * ships in `jdk.httpserver`, it needs no module flags on a classpath, and it answers unregistered
 * paths with a 404 of its own. The desktop packaging has to opt into that module explicitly, though:
 * it is absent from Compose's `DEFAULT_RUNTIME_MODULES`, so `nativeDistributions { modules(...) }`
 * in `:app:desktopApp` is load-bearing and its absence only shows up in a packaged build.
 *
 * The port is fixed at [io.challenge_workshop.mal_ui.mal.DESKTOP_LOOPBACK_PORT] and cannot be made
 * ephemeral: MAL does no RFC 8252 §7.3 port-lenient matching, so the port is part of the byte-exact
 * registered string. A bind failure is therefore something to report rather than route around —
 * which is why [arm] is a phase of its own, and why the `BindException` doubles as a free
 * cross-process "one login at a time" guard.
 *
 * One instance serves many sign-ins: the composable remembers it for the life of the composition,
 * so everything belonging to a single attempt lives in [Attempt] and [arm] starts a fresh one.
 *
 * @param scope where the browser launch runs. Cancelling it is *not* how this listener is released —
 * cancelling the coroutine in [await] is; see [AuthRedirectChannel].
 * @param launchBrowser opens a URL. Compose Desktop's `LocalUriHandler` in the app. It is called off
 * the caller's thread and its outcome is deliberately ignored.
 */
class LoopbackRedirectListener(
    private val scope: CoroutineScope,
    private val launchBrowser: (String) -> Unit,
    private val timeout: Duration = AUTHORIZATION_TIMEOUT,
) : AuthRedirectChannel {

    private val current = AtomicReference<Attempt?>(null)

    /**
     * Binds the loopback port, or explains why it could not.
     *
     * Bound **before** the browser opens, so a port that is already taken is a message on the
     * sign-in screen rather than something discovered after the user has approved access on MAL.
     */
    override suspend fun arm(redirectUri: String): ArmResult {
        val callback = runCatching { URI(redirectUri) }.getOrNull() ?: return ArmResult.Unsupported
        // Not this channel's business: Android's custom scheme is a perfectly good Redirect URI, it
        // is just not one anything here can listen on, and Paste-the-code handles that.
        if (!callback.scheme.equals("http", ignoreCase = true)) return ArmResult.Unsupported
        val host = callback.host ?: return ArmResult.Unsupported
        val port = callback.port.takeIf { it > 0 } ?: return ArmResult.Unsupported
        val path = callback.path?.takeIf { it.isNotEmpty() } ?: return ArmResult.Unsupported

        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        // Binding anything routable would put the authorization code on the network. The registered
        // URI is an IP literal precisely so this stays a constant fact rather than a DNS result.
        if (addresses.isEmpty() || addresses.any { !it.isLoopbackAddress }) {
            return ArmResult.Failed(
                "$redirectUri is not a loopback address, so the sign-in redirect cannot be " +
                    "captured here without exposing the authorization code to the network.",
            )
        }

        // The *caller's* job, captured before `withContext` substitutes its own child. Cancelling
        // the `await` is a channel's documented release, but every caller has a suspension point
        // between `arm` and `await` — `beginAuthorization()` — and a cancellation landing in that
        // gap would otherwise strand the bound port for the life of the process.
        val callerJob = coroutineContext.job
        val attempt = Attempt(redirectUri, path)

        val result = withContext(Dispatchers.IO) {
            try {
                // Every loopback address the host resolves to. `DESKTOP_REDIRECT_URI` is an IP
                // literal, so today that is exactly one — but the validation above has to resolve
                // them all anyway, and binding each is the same loop. It is also what a `localhost`
                // form would need, since that name resolves to both families here and a browser is
                // free to pick either.
                for (address in addresses) {
                    attempt.servers += HttpServer.create(InetSocketAddress(address, port), 0).apply {
                        createContext(path) { attempt.handleCallback(it) }
                        createContext(attempt.donePath) { attempt.handleDone(it) }
                        // One `HTTP-Dispatcher` thread for everything, which is why no handler may
                        // ever call `stop()`: it waits for handlers to finish, so that deadlocks.
                        executor = null
                        start()
                    }
                }
                ArmResult.Armed
            } catch (e: IOException) {
                attempt.shutdown()
                ArmResult.Failed(explain(e, port))
            }
        }

        if (result is ArmResult.Armed) {
            // The bind above proves any previous attempt has released the port; this drops the
            // spent one so `await` cannot answer a new sign-in with an old result.
            current.getAndSet(attempt)?.shutdown()
            // Only on cancellation. A caller whose coroutine ends *normally* between `arm` and
            // `await` — a `withTimeout` or a `coroutineScope` around the arming — has not abandoned
            // anything, and tearing its reservation down there would be a trap.
            callerJob.invokeOnCompletion { cause -> if (cause != null) attempt.shutdown() }
        }
        return result
    }

    /**
     * Records the `state` to expect, then sends the user to MAL.
     *
     * The launch runs off the caller's thread. Where `Desktop.Action.BROWSE` is supported the native
     * path is `gtk_show_uri`, which has open hang reports (JDK-8267572, JDK-8275494), and on desktop
     * this is called from the Swing dispatcher.
     *
     * Its outcome is ignored on purpose. No platform's browser-opening call reports failure usefully
     * — `xdg-open`'s exit code is never even checked — so a silent failure and a success look
     * identical, and the flow must not depend on either. The authorization URL stays on screen to
     * copy by hand.
     */
    override fun open(authorizationUrl: String) {
        current.get()?.expectStateOf(authorizationUrl)
        scope.launch(Dispatchers.IO) {
            // Nothing here logs the URL: under `plain` PKCE the code verifier travels inside it.
            runCatching { launchBrowser(authorizationUrl) }
        }
    }

    /**
     * Waits for the redirect, then shuts the listener down on every exit path there is.
     *
     * A timeout reports [AuthRedirectResult.Unsupported] rather than a failure: the caller's
     * handling of it leaves the authorization URL and the paste field on screen, which is exactly
     * what a user who took longer than [timeout] needs.
     */
    override suspend fun await(): AuthRedirectResult {
        // A caller that ignored a failed `arm` gets an answer, instead of a coroutine parked on a
        // redirect that nothing is listening for.
        val attempt = current.get() ?: return AuthRedirectResult.Unsupported

        // The `finally` is out here rather than inside the `withContext` on purpose: a coroutine
        // that was cancelled *before* it reached this call never runs that block, and the port would
        // survive the one operation documented to release it.
        try {
            return withContext(Dispatchers.IO) {
                withTimeoutOrNull(timeout) { attempt.captured.await() }
                    ?: AuthRedirectResult.Unsupported
            }
        } finally {
            // Both of these block, and this runs on the caller's dispatcher — on desktop that is
            // `viewModelScope`, i.e. Swing. `NonCancellable` because the cancellation path is the
            // one that most needs the port back.
            withContext(NonCancellable + Dispatchers.IO) {
                attempt.lingerForTheDonePage()
                // Safe here and nowhere inside a handler: `stop` waits for handlers to finish.
                attempt.shutdown()
            }
        }
    }

    private fun explain(e: IOException, port: Int): String = when (e) {
        is BindException ->
            "Port $port is already in use, so the sign-in redirect cannot be captured. Another " +
                "mal_ui sign-in is probably still waiting — finish or cancel it — or another " +
                "program has taken the port. It cannot be moved: MyAnimeList matches the whole " +
                "registered redirect URI, port included."

        else -> "Could not listen on port $port for the sign-in redirect: ${e.message}"
    }

    /**
     * One sign-in's worth of listener.
     *
     * Everything here is single-use — a bound port, one accepted code, one result — and the channel
     * itself is not, so the two are kept apart rather than reset.
     */
    private class Attempt(private val redirectUri: String, private val callbackPath: String) {

        /** More than one only when the callback host resolves to several loopback addresses. */
        val servers = CopyOnWriteArrayList<HttpServer>()

        val captured = CompletableDeferred<AuthRedirectResult>()

        /**
         * The `state` this flow expects, read out of the authorization URL because that is the only
         * place a channel ever sees it. Null until [expectStateOf] runs — and nothing arriving
         * before then can be ours, since nobody has been sent to MyAnimeList yet.
         */
        private val expectedState = AtomicReference<String?>(null)

        fun expectStateOf(authorizationUrl: String) {
            expectedState.set(queryParam(authorizationUrl, "state"))
        }

        /**
         * Exactly one redirect is ever accepted, even when two arrive at once — which they can, if
         * both address families are bound or a browser retries.
         */
        private val claimed = AtomicBoolean(false)

        private val stopped = AtomicBoolean(false)

        /** True once a 302 has gone out and a browser is on its way to fetch [donePath]. */
        private val redirected = AtomicBoolean(false)

        private val donePageServed = CountDownLatch(1)

        val donePath: String get() = "$callbackPath/done"

        fun handleCallback(exchange: HttpExchange) {
            try {
                // Contexts match by prefix, so `/oauth/callbackanything` would land here too.
                if (exchange.requestURI.path != callbackPath) {
                    respond(exchange, 404, page("Not found", "There is nothing at this address."))
                    return
                }
                when (exchange.requestMethod.uppercase()) {
                    "GET" -> Unit
                    // A liveness probe; a browser never lands here with one. It consumes nothing.
                    "HEAD" -> {
                        prepare(exchange)
                        exchange.sendResponseHeaders(200, -1)
                        return
                    }

                    else -> {
                        exchange.responseHeaders.set("Allow", "GET, HEAD")
                        respond(exchange, 405, page("Not allowed", "This address only answers GET."))
                        return
                    }
                }

                val query = exchange.requestURI.rawQuery
                if (query.isNullOrEmpty()) {
                    respond(
                        exchange,
                        400,
                        page("Nothing to do", "This address expects a redirect from MyAnimeList."),
                    )
                    return
                }

                // Any local process can reach this port. Treating a mismatch as fatal would let one
                // abort the login at will, so it is refused and the listener keeps waiting.
                //
                // A `state` we never minted is refused as well, and so is anything arriving before
                // there is one to compare: `open` records it before the browser is launched, so
                // until then nobody has been sent to MyAnimeList and nothing here can be ours.
                if (parseQueryString(query)["state"] != expectedState.get()) {
                    respond(
                        exchange,
                        400,
                        page("Not this sign-in", "That redirect belongs to a different sign-in attempt."),
                    )
                    return
                }

                val raw = "$redirectUri?$query"
                // Read only to choose the page this tab renders and to refuse what MAL cannot have
                // sent. Judging the redirect is `completeAuthorization`'s, so a denial goes back as
                // `Received` like any other: see `AuthRedirectChannel`.
                val denial = try {
                    parseRedirect(raw)
                    null
                } catch (e: MalAuthException) {
                    // No `error` parameter means MAL did not send this — a hand-typed URL, some
                    // other process. Refuse it and keep waiting for the real redirect.
                    e.takeIf { it.errorCode != null } ?: run {
                        respond(exchange, 400, page("Not a MyAnimeList redirect", e.message.orEmpty()))
                        return
                    }
                }

                if (!claimed.compareAndSet(false, true)) {
                    respond(exchange, 409, page("Already done", "This sign-in has already been completed."))
                    return
                }

                if (denial != null) {
                    // The *redirect* succeeded; the authorization did not. A 4xx would blame the
                    // browser for something it got right.
                    respond(exchange, 200, page("Sign-in was not approved", denial.message.orEmpty()))
                } else {
                    // Straight on to a bare URL, so the authorization code does not linger in
                    // browser history — worth it under `plain` PKCE, where the code verifier travels
                    // in the authorization URL too.
                    prepare(exchange)
                    exchange.responseHeaders.set("Location", donePath)
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                    redirected.set(true)
                }
                captured.complete(AuthRedirectResult.Received(raw))
            } catch (e: Exception) {
                runCatching { respond(exchange, 500, page("Something went wrong", "")) }
            } finally {
                exchange.close()
            }
        }

        /** The page the user is left looking at, at a URL that carries no code. */
        fun handleDone(exchange: HttpExchange) {
            try {
                respond(exchange, 200, page("Signed in", "You can close this tab and return to mal_ui."))
            } finally {
                exchange.close()
                donePageServed.countDown()
            }
        }

        /**
         * Holds the socket open just long enough for the browser to follow the 302.
         *
         * Without this the listener wins that race nearly every time: the response goes out, the
         * awaiting coroutine resumes, and `stop(0)` closes the socket — and every open connection
         * with it — before the follow-up request lands. The sign-in would still succeed, and the
         * user would be looking at a connection error while it did.
         *
         * Bounded, and entered only when a redirect actually went out, so a timeout or a
         * cancellation still gives the port back immediately.
         */
        fun lingerForTheDonePage() {
            if (!redirected.get()) return
            runCatching {
                donePageServed.await(DONE_PAGE_GRACE.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
        }

        fun shutdown() {
            if (!stopped.compareAndSet(false, true)) return
            // `stop(0)` returns without waiting out a delay, and rebinding straight afterwards
            // works — which is what makes retrying a cancelled sign-in possible at all.
            servers.forEach { runCatching { it.stop(0) } }
            servers.clear()
        }
    }

    companion object {
        /**
         * How long a sign-in may sit waiting before the listener gives the port back and the flow
         * falls back to Paste-the-code. Long enough to create a MAL account mid-flow, short enough
         * that a forgotten browser window does not hold the port until the app is closed.
         */
        val AUTHORIZATION_TIMEOUT: Duration = 5.minutes

        /** Long enough for a local redirect on a loaded machine, short enough not to be felt. */
        private val DONE_PAGE_GRACE: Duration = 2.seconds
    }
}

private fun queryParam(url: String, name: String): String? =
    runCatching { URI(url).rawQuery }.getOrNull()?.let { parseQueryString(it)[name] }

private fun respond(exchange: HttpExchange, status: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    prepare(exchange)
    // `HttpServer` sends no content type of its own, so an omitted one renders as plain text.
    exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    // Closing the stream flushes it and finishes the exchange, so the response is on the wire
    // before anything can stop the server underneath it.
    exchange.responseBody.use { it.write(bytes) }
}

private fun prepare(exchange: HttpExchange) {
    exchange.responseHeaders.set("Cache-Control", "no-store")
    // The URL in the address bar contains the authorization code.
    exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
}

/**
 * A whole page in one string, with no subresources at all — no external stylesheet, font or image.
 * The URL that renders it can contain the authorization code, and any subresource request would
 * leak that code in a `Referer` header.
 */
private fun page(title: String, body: String): String {
    val safeTitle = escape(title)
    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="no-referrer">
        <title>$safeTitle</title>
        <link rel="icon" href="data:,">
        <style>
        body { font-family: system-ui, sans-serif; margin: 0; display: grid; place-items: center;
               min-height: 100vh; background: #14171c; color: #e6e8ea; }
        main { max-width: 32rem; padding: 2rem; }
        h1 { font-size: 1.25rem; margin: 0 0 0.75rem; }
        p { margin: 0; line-height: 1.5; color: #b7bcc3; white-space: pre-wrap; }
        </style>
        </head>
        <body><main><h1>$safeTitle</h1><p>${escape(body)}</p></main></body>
        </html>
    """.trimIndent()
}

/** MAL's `error` code reaches these pages out of a query string, so it is not trusted markup. */
private fun escape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
