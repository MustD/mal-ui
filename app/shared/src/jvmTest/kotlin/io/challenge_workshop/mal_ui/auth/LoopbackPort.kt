package io.challenge_workshop.mal_ui.auth

import io.challenge_workshop.mal_ui.mal.DESKTOP_LOOPBACK_PORT
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Whether 18040 is free, waiting a moment for it if it is not.
 *
 * The port is fixed and cannot be made ephemeral — MAL does no port-lenient matching — so every test
 * that arms a [LoopbackRedirectListener] contends for one real machine-wide resource. Each of them
 * asserts on this in its teardown rather than merely passing: a listener that outlives its flow
 * breaks the *next* test, and in the app the next sign-in.
 *
 * The wait exists because releasing is asynchronous — a cancelled coroutine has to reach its
 * `finally` — not because binding after `stop(0)` is unreliable.
 */
fun awaitLoopbackPortFree(): Boolean {
    repeat(250) {
        if (loopbackPortIsFree()) return true
        Thread.sleep(20)
    }
    return false
}

fun loopbackPortIsFree(): Boolean = try {
    ServerSocket().use {
        it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), DESKTOP_LOOPBACK_PORT))
    }
    true
} catch (e: Exception) {
    false
}

/** Follows nothing: the success path *is* a 302, so following it would hide the assertion. */
val loopbackHttp: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(5))
    .build()

/** A plain GET, as a browser landing on the Redirect URI would send it. */
fun loopbackGet(url: String): HttpResponse<String> =
    loopbackHttp.send(
        HttpRequest.newBuilder(URI(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )
