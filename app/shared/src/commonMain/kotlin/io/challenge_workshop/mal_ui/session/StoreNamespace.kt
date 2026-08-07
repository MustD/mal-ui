package io.challenge_workshop.mal_ui.session

/**
 * Names the store on every target: the `SharedPreferences` file on Android, the directory under
 * `$XDG_STATE_HOME` on desktop, the `sessionStorage` key prefix on web.
 *
 * Unversioned on purpose — the *keys* carry the version (`mal.session.v1`), so a format change is
 * a clean re-login without orphaning a whole namespace.
 */
const val MAL_STORE_NAMESPACE: String = "io.challenge_workshop.mal_ui"
