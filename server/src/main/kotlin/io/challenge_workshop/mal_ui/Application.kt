package io.challenge_workshop.mal_ui

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Bound to loopback only: the relay forwards credentials upstream and is a dev tool. */
private const val PORT = 18010

fun main() {
    embeddedServer(Netty, port = PORT, host = "127.0.0.1", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Not load-bearing in normal use: both the reverse proxy and the webpack dev server
    // route `/mal` to this server on the page's own origin, so relay calls are same-origin.
    // Kept as a fallback for hitting the relay directly from another origin.
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHost("mal-ui.localhost", schemes = listOf("https"))
        allowHost("js.mal-ui.localhost", schemes = listOf("https"))
        listOf(18020, 18030).forEach { port ->
            allowHost("localhost:$port")
            allowHost("127.0.0.1:$port")
        }
    }
    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
        malRelay(this)
    }
}
