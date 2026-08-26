package io.challenge_workshop.mal_ui.animelist

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade

/**
 * The one [ImageLoader] the app loads cover art with.
 *
 * **Built by hand rather than left to Coil's default**, because the default only knows how to fetch
 * over the network on the targets that have a platform HTTP stack Coil ships a fetcher for. The web
 * targets do not, so a default loader there resolves every `https://` model to nothing and the card
 * Layout becomes a grid of placeholders — the exact failure ticket 07 exists to avoid, arriving
 * silently and only on two of the four Targets.
 *
 * **It must not be `MalSessionRepository`'s client.** That one carries Ktor's `Auth` plugin, so
 * reusing it would attach the user's MAL access token to every request to `cdn.myanimelist.net` — a
 * bearer token sent to a host that has no business seeing one. Cover art needs no credential at all:
 * the CDN answers anonymously and with `Access-Control-Allow-Origin: *`, which is what
 * `docs/mal-api/cover-art-cors.md` measured and what makes the same URL work unchanged on all four
 * Targets, with no Relay route and no per-platform branch.
 *
 * Coil's Ktor fetcher builds an engine-less `HttpClient()`, which resolves whichever engine is on
 * the target's runtime classpath — okhttp, CIO and the JS engine, each declared in this module's
 * build script beside a note saying why.
 */
fun malImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory()) }
        // A cover fading in reads as the art arriving; the same art appearing between two frames
        // reads as a flicker, and a grid of them lands at fifty different moments.
        .crossfade(true)
        .build()
