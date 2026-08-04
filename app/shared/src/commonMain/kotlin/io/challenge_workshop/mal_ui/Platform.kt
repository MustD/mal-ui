package io.challenge_workshop.mal_ui

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform