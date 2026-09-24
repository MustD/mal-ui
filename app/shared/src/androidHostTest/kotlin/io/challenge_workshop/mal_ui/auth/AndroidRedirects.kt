package io.challenge_workshop.mal_ui.auth

import android.content.Intent
import android.net.Uri
import io.challenge_workshop.mal_ui.mal.ANDROID_REDIRECT_URI

/** A redirect as MyAnimeList sends it, and as `MainActivity` receives it. */
internal fun androidRedirect(state: String, code: String = "a-code"): String =
    "$ANDROID_REDIRECT_URI?code=$code&state=$state"

/** A redirect as MyAnimeList sends it when the user declines access. */
internal fun androidDenial(state: String): String =
    "$ANDROID_REDIRECT_URI?error=access_denied&state=$state"

internal fun redirectIntent(state: String, code: String = "a-code"): Intent =
    viewIntent(androidRedirect(state, code))

internal fun denialIntent(state: String): Intent = viewIntent(androidDenial(state))

private fun viewIntent(uri: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)

/** MAL's authorization endpoint, which is where a channel reads the `state` to expect from. */
internal fun authorizationUrl(state: String): String =
    "https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=an-id" +
        "&code_challenge=a-verifier&state=$state&redirect_uri=$ANDROID_REDIRECT_URI"
