# MAL auth: from paste-the-code to a real redirect flow

Research and an implementation order for turning the current three-step manual login into
`login → myanimelist.net → redirect back → tokens`, on web, desktop and Android.

Companion to [`errors.md`](errors.md), which covers the CORS/relay problem already solved. This document is about what
is *not* built yet.

**Confidence is marked throughout.** `[verified]` was measured or read from source during this research; `[docs]` comes
from an official document; `[reported]` is community evidence; `[unverified]`
is an assumption that needs checking. The two `[unverified]` items in [§1](#1-verified-2026-08-06) that changed the
design are now **settled by measurement** — see that section for the answers and what they closed off.

---

## Where things stand

`:core`'s `mal/` package is in better shape than the missing feature suggests. Already correct and reusable as-is:

| Piece                                                                                     | Status                     |
|-------------------------------------------------------------------------------------------|----------------------------|
| `Pkce` — verifier charset/length per RFC 7636 §4.1, `plain` challenge, `state` generation | Correct                    |
| `MalAuthClient.beginAuthorization()` — builds the authorize URL                           | Correct                    |
| `MalAuthClient.completeAuthorization()` — **verifies `state`** (`MalAuthClient.kt:71`)    | Correct                    |
| `parseRedirect()` — full URL / bare query / bare code, decodes `error=` callbacks         | Correct, reusable verbatim |
| `exchangeCode` / `refresh` / `me`, MAL error-code hints                                   | Correct                    |
| `platformMalEndpoints()` + the `/mal` relay                                               | Correct                    |

So the token half is done. What is missing is the *transport of the code back into the app*, plus everything that
follows from tokens outliving a single screen:

1. **No redirect capture on any platform.** `platformRedirectUri()` now names a per-target Redirect URI, but nothing
   listens on the desktop port; Android has no intent filter; web has no callback route. (The `LOOPBACK_REDIRECT_URI`
   this originally described has since been replaced by `DESKTOP_REDIRECT_URI` on port 18040.)
2. **The PKCE verifier is only in memory.** `MalLoginViewModel.authRequest` is a `mutableStateOf`. It survives an
   Android configuration change — but *not* Android process death while the user is in the browser, and *not* a web
   full-page redirect. Both are on the happy path of a redirect flow, so this is the single biggest correctness gap.
   `completeLogin()` does `val request = authRequest ?: return`, which means it fails *silently*.
3. **No persistence.** Tokens die with the app; every launch is a fresh login.
4. **No automatic refresh.** `refreshTokens()` is a button.
5. **State is 10 independent `mutableStateOf` fields**, so illegal combinations are representable and the screen infers
   state from null checks.

---

## 1. Verified 2026-08-06

**Both unknowns are settled, and both landed on the permissive answer.** The design branched on them, so this section
now records answers rather than experiments.

Method, and how to re-run it if MAL's behaviour changes:
[`docs/adr/mal-redirect-uri-probes.http`](adr/mal-redirect-uri-probes.http). Registration itself needs a logged-in
apiconfig session, but everything after it is machine-checkable, because MAL validates the client and the redirect URI
*before* it looks at the authorization code. Posting a deliberately undecryptable code to
`/v1/oauth2/token` therefore separates the two cases without a browser or a session:

| Response to a bogus code                                              | Meaning                              |
|-----------------------------------------------------------------------|--------------------------------------|
| 400 `invalid_request`, hint `"Cannot decrypt the authorization code"` | the `redirect_uri` **matched**       |
| 401 `invalid_client`, `"Client authentication failed"`                | the `redirect_uri` **did not match** |

Note MAL uses `invalid_request` where RFC 6749 §5.2 asks for `invalid_grant`, and that the mismatch body is
byte-identical to a genuinely wrong client ID — the status is the only signal `[verified]`.

### 1.1 Can one app register multiple redirect URLs?

**Yes. Eight URIs are registered on one client ID and all eight work** `[verified]`. The research disagreed with itself
here and this was the highest-leverage unknown; it resolves in favour of the clean design.

The 2020 thread where a user reported *"This error appears as soon as I add more than one redirect URL — bug?"* and MAL
staff replied *"there's currently something wrong with the redirection"* `[reported]` describes a bug that is either
fixed or never applied to this path. The `App Redirect URL` field is indeed a textarea taking line-separated URLs, as
DailyAL's two-URI registration implied `[verified]`.

So: one client ID, one redirect per platform/origin, `redirect_uri` sent explicitly on both authorize and token
(byte-identical). The registered set, which ticket 09 hardcodes:

```
io.challenge-workshop.malui://oauth/callback      # Android
http://127.0.0.1:18040/oauth/callback             # Desktop loopback
http://localhost:18020/oauth/callback             # web, wasmJs dev server
http://localhost:18030/oauth/callback             # web, js dev server
https://mal-ui.localhost/oauth/callback           # web, wasmJs via reverse proxy
https://js.mal-ui.localhost/oauth/callback        # web, js via reverse proxy
http://localhost:18040/oauth/callback             # registered, redundant — see §1.2
http://localhost:8080/oauth/callback              # legacy paste-the-code, kept as a control
```

**The consequence bites immediately, and it was measured:** with one URI registered, omitting `redirect_uri` at
`/v1/oauth2/authorize` returned 303 → `login.php`; with eight registered the identical request returns 401
`invalid_client` `[verified]`. The `null` branch documented on `MalAuthConfig.redirectUri` is therefore dead, which is
what narrows it to a non-null `String` in ticket 09. A null there can now only ever be a bug, and it reports as a
misleading 401 implicating the client ID.

The *token* endpoint does not enforce the same rule: with `redirect_uri` omitted it still returns the 400, because it
validates the parameter only when present `[verified]`. Whether it enforces "the same URI as authorize, or neither"
can only be seen with a real decryptable code, so treat MAL's documented requirement as binding and always send it.

The rejected alternative — one MAL app *per platform*, each with its own client ID and single redirect URL, omitting
`redirect_uri` entirely (the Aniyomi approach `[reported]`) — is no longer needed. That would have made the client ID
per-platform config and complicated [§7](#7-client-id-configuration).

### 1.2 Does the registration form accept these URI forms?

**All three, plus the plain-`localhost` and dev-server forms. Every URI tested was accepted at registration and at
`/v1/oauth2/authorize`** `[verified]`. None of this was documented; two of the three predictions here were wrong, in the
permissive direction.

- A custom scheme — `io.challenge-workshop.malui://oauth/callback`. **Accepted**, as expected: MoeList ships
  `moelist://…`, Aniyomi ships `aniyomi://myanimelist-auth` `[reported]`, and MAL staff explicitly recommend *"register
  a custom URI scheme… for example `myapp://auth`"* `[reported]`.
- A bare IP literal — `http://127.0.0.1:18040/oauth/callback`. **Accepted**, which was the doubtful one — the form is
  unattested against MAL and some providers reject it. RFC 8252 §8.3 prefers the literal IP, so the desktop listener
  uses it and the `localhost` fallback is unnecessary. That also sidesteps the dual-address-family trap: `localhost`
  resolves to both `127.0.0.1` and `::1` here, which is the same thing that caused the dev-server bug in
  [`errors.md`](errors.md), and an IP literal has no such ambiguity. `http://localhost:18040/oauth/callback` is
  registered too but redundant.
- A `.localhost` subdomain — `https://mal-ui.localhost/oauth/callback` and `https://js.mal-ui.localhost/oauth/callback`.
  **Both accepted**, which is the prediction that failed: Google, Entra and Slack are all documented to reject
  `.localhost` subdomains `[docs, other providers]`, and this was called the most likely of the three to fail. MAL does
  not care. The reverse-proxy hostnames therefore keep auto-redirect instead of falling back to paste-the-code.
- The dev-server origins — `http://localhost:18020/oauth/callback` and `http://localhost:18030/oauth/callback`.
  **Accepted.**

### 1.3 Two behaviours worth knowing (do not need testing)

- **Exact-match is byte-exact, and stricter than RFC 3986.** Re-measured 2026-08-06 against a URI that *is* registered,
  so these are rejections rather than absences `[verified]`. Every mutation returns 401 `invalid_client`:

  | Mutation of a registered URI | Result |
    |---|---|
  | trailing slash appended | rejected |
  | host upper-cased (`MAL-UI.localhost`) | rejected — though RFC 3986 §3.2.2 makes host case-insensitive |
  | scheme default port made explicit (`:443` on `https`) | rejected — no port normalization at all |
  | extra query parameter appended | rejected |
  | different loopback port (`:54321` vs registered `:18040`) | rejected |

  So MAL performs **no** RFC 3986 normalization, and therefore **no RFC 8252 §7.3 loopback-port leniency** — every port
  must be registered explicitly, which is why the desktop port is fixed at 18040 rather than ephemeral.
- **A redirect-URI mismatch reports as HTTP 401 `invalid_client` / "Client authentication failed"**
  `[verified]`, which misleadingly implicates the Client ID. `MalAuthClient.hintFor()` currently maps
  `invalid_client` to "check the Client ID" — worth extending to mention redirect-URI mismatch, or this will cost an
  hour of debugging.

---

## 2. Security: `plain` PKCE is weaker than it looks

MAL supports `code_challenge_method=plain` only — re-confirmed, twice in the docs and by staff; claims that S256 was
added later have no source `[docs]`. `Pkce`'s design is right.

But be clear-eyed about what that buys. With `plain`, `code_challenge == code_verifier`, so **the verifier travels in
cleartext in the authorization URL**. RFC 7636 §7.2:

> With the "plain" method, there is a chance that "code_challenge" will be observed by the attacker on
> the device or in the http request. Since the code challenge is the same as the code verifier in this
> case, the "plain" method does not protect against the eavesdropping of the initial request.

Practical consequences for this app:

- Against an attacker who sees only the **redirect** (a local process racing the desktop loopback port, a malicious
  Android app claiming the same custom scheme), PKCE still works — they have the code but not the verifier.
- Against an attacker who can read the **authorization URL** (browser history, a browser extension, a log line, a shell
  history entry), PKCE provides *no protection at all*.

So: **never log the authorization URL, the code, or the verifier**; prefer stripping the code from browser history where
possible ([§5.2](#52-callback-response-page), [§6.4](#64-clean-the-url-after-capture)); and treat the `state` check as
load-bearing rather than decorative. It already exists — keep it.

This is MAL's limitation, not something the implementation can fix. It belongs in `errors.md`
alongside the existing `plain`-only note.

---

## 3. Shared design

### 3.1 Module boundary

**Interfaces and policy in `:core`; every `expect`/`actual` platform binding in `:app:shared`.**

The reason is `:server`, which depends on `:core` as a plain JVM library. An
`expect fun platformTokenStore()` in `:core` forces a jvm actual that writes to `~/.local/state/mal_ui/` — semantically
wrong for a Netty process and a surprising side effect on the server's classpath. Contrast the existing
`platformMalEndpoints()`: its actuals are pure constants with no I/O, which is exactly why it is harmless there. A store
actual does filesystem I/O, so it goes one layer up. The Android actual also needs a `Context`, which is an
`:app:shared`/`:app:androidApp` concern.

| `:core` (commonMain, Compose-free)                                                | `:app:shared`                                                     |
|-----------------------------------------------------------------------------------|-------------------------------------------------------------------|
| `interface KeyValueStore` (3 suspend methods)                                     | `expect fun platformKeyValueStore(ns): KeyValueStore` + 4 actuals |
| `interface TokenStore` + `JsonTokenStore(kv, json)`                               | `expect` redirect capture + 4 actuals                             |
| `@Serializable StoredSession(tokens, user?, obtainedAtMs)`                        | `MalLoginScreen` and the rest of the UI                           |
| `@Serializable PendingAuthorization(verifier, state, redirectUri, startedAtMs)`   | ViewModel (thin wrapper over the repository)                      |
| `sealed interface SessionState`, `SignedOutReason`                                | Android `Context` plumbing                                        |
| `MalSessionRepository` — owns store + client + the `Auth`-configured `HttpClient` |                                                                   |

Keeping the repository in `:core` also means one `./gradlew :core:allTests` covers it on all four targets, and it is the
code `:server` would need if the relay ever became a
[backend-for-frontend](#63-token-storage-on-web).

### 3.2 Persist the in-flight authorization

This is the fix for gap 2 and the thing that makes a redirect flow possible at all:

```kotlin
@Serializable
data class PendingAuthorization(
    val codeVerifier: String,
    val state: String,
    val redirectUri: String,       // shipped non-null; a record written without one is discarded on restore
    val clientId: String,          // entered at runtime today — without it the token call can't be rebuilt
    val startedAtEpochMs: Long,
)
```

Write it in `beginAuthorization()`, read it when a redirect arrives, clear it on success, on `state`
mismatch, and after a ~10-minute TTL (MAL codes are single-use and short-lived). `kotlin.time.Clock`
is stable as of Kotlin 2.3 `[docs]`, so `Clock.System.now()` works in `commonMain` with no kotlinx-datetime dependency —
and can be injected as a fake in tests.

**Use the token store, not `SavedStateHandle`.** `SavedStateHandle` exists in KMP but only does real process-death
restore on Android; the store works on all four targets. (On Android specifically,
`SavedStateHandle` would *also* work and is the more idiomatic choice — but then web needs a second mechanism, so prefer
the one that covers both.)

### 3.3 Session state

Replace the 10 loose fields with a sealed interface behind a `StateFlow`, and keep only genuinely ephemeral form text
(`pastedRedirect`, a client-ID override field) as `mutableStateOf`:

```kotlin
sealed interface SessionState {
    /** App start: reading the store. UI shows a splash, not a login form. */
    data object Restoring : SessionState
    data class SignedOut(val reason: SignedOutReason, val error: String? = null) : SessionState

    /** User is away in the browser. */
    data class Authorizing(val pending: PendingAuthorization) : SessionState
    data class SignedIn(val user: MalUser?, val refreshing: Boolean = false) : SessionState
}

enum class SignedOutReason { NeverSignedIn, UserSignedOut, RefreshRejected, AuthorizationFailed }
```

Three deliberate choices:

- **No resting `Expired` state.** Expiry is discovered mid-request and immediately either recovers or becomes
  `SignedOut(RefreshRejected)`. A distinct `Expired` state adds no transitions and invites
  "expired but still rendering the list" bugs. The nuance lives in `SignedOutReason`, so the UI can say *"Your MAL
  session expired"* rather than a bare *"Signed out"*.
- **`refreshing` is a flag on `SignedIn`, not a state** — a refresh must not unmount the screen.
- **Tokens are not in the state object.** They live in the `TokenStore` and are read by the bearer provider. That keeps
  them out of Compose snapshots and out of any `toString()`, and stops them drifting from what the HTTP client actually
  sends.

**Startup: restore optimistically, do not silent-refresh.**

```
Restoring
 ├─ store empty                        → SignedOut(NeverSignedIn)
 ├─ PendingAuthorization present, fresh → Authorizing(...)   // resume an interrupted flow
 └─ tokens present                      → SignedIn(cachedUser)
                                          first API call's 401 drives refresh
```

An eager refresh on every launch costs a round trip, fails offline, and — because MAL rotates refresh tokens — can lose
the session if the launch is killed mid-refresh. Cache `MalUser` next to the tokens for the app bar, and let the
ordinary authenticated `/users/@me` call be the refresh trigger.

`androidx.lifecycle` 2.11 covers JVM, Android and Web (JS + WasmJS) `[docs]`, so the existing
`viewModel { }` usage is fine. Consume with `collectAsStateWithLifecycle()` — `lifecycle-runtimeCompose`
is already a dependency.

### 3.4 Automatic refresh: use Ktor's `Auth` plugin, do not hand-roll

Ktor 3.5.1's `AuthTokenHolder` is a `Mutex` plus a generation check `[verified from 3.5.1 source]`:
N concurrent 401s on the same cached token produce **one** `refreshTokens` call, and the losers reuse the winner's
result. Every historical stampede/cancellation bug (KTOR-8285, KTOR-6569, KTOR-4759, KTOR-8470, KTOR-5681) is fixed by
3.4.0 `[verified from CHANGELOG]`. The common "wrap it in your own Mutex" advice predates that and serializes all
parallel requests.

One non-obvious behaviour that removes the usual reason to hand-roll: with **exactly one provider installed**, a 401
carrying **no `WWW-Authenticate` header at all** still triggers refresh
`[verified from Auth.kt source]`. So it does not matter whether MAL sends a bearer challenge.

MAL specifics:

- **`expires_in` is useless for access-token expiry.** MAL documents access = 1 hour, refresh = 1 month, while the
  example `expires_in` is `2415600` (~28 days) — it describes the *refresh* token `[docs]`.
  `MalModels.kt`'s KDoc already says this correctly. Refresh reactively on 401; treat any
  `obtainedAt + 55m` heuristic as a hint only.
- **Refresh tokens rotate, but the old one keeps working.** MAL: *"You can still use an old refresh token after
  obtaining a new one until it expires"*, though discarding it is "highly recommended"
  `[docs]`. So `MalAuthClient.refresh()`'s existing KDoc is **correct** — one research pass claimed otherwise and was
  wrong. This makes a lost-write less catastrophic than full rotation would, but still set`nonCancellableRefresh = true`
  (KTOR-8285) so a cancelled coroutine can't drop a successful refresh.
- **Distinguish failure kinds.** `invalid_grant` / 400 / 401 → clear the store, `SignedOut(RefreshRejected)`. Transport
  error or 5xx → **do not clear**; the refresh token is still good and an offline launch must not log the user out. This
  is the regression test worth writing first.

Persist inside `refreshTokens` *before* returning, and use a **separate** `Auth`-free client for the token endpoint —
Ktor's own docs warn that reusing the authenticated client there deadlocks.

---

## 4. Android

**Recommended:** `AuthTabIntent` (androidx.browser 1.9.0+) as the primary path, custom-scheme intent-filter +
`onNewIntent` as the fallback, both wired.

### 4.1 Browser choice

WebView is disallowed, not merely discouraged — RFC 8252 §8.12: the host app "can record every keystroke entered in the
login form to capture usernames and passwords", and §4 adds that it loses SSO with the system browser. Especially wrong
here: MAL has no password grant, so the whole point of the current architecture is that the password is only ever typed
on myanimelist.net.

**`androidx.browser:browser:1.10.0`** — stable, 2026-03-25; AAR declares `minSdkVersion 23` so
`minSdk = 24` is fine; no dependency conflicts with Kotlin 2.4.10 / compileSdk 36 `[verified from
Google Maven + the AAR]`. No AGP 9 incompatibility found, but not build-tested `[unverified]`.

`AuthTabIntent` (`androidx.browser.auth`, added in 1.9.0 `[verified in the sources jar]`) is purpose-built for this and
is the biggest change since this problem was last "solved": it returns the redirect URI through an
`ActivityResultLauncher` — **no intent filter needed** — and it reports cancellation as a real result code:

```
RESULT_OK, RESULT_CANCELED, RESULT_VERIFICATION_FAILED, RESULT_VERIFICATION_TIMED_OUT, RESULT_UNKNOWN_CODE
```

**Why you still need the intent filter.** Auth Tab requires Chrome 137+; on other browsers it silently degrades to a
plain Custom Tab and your launcher gets `RESULT_CANCELED` **even on success**, with the redirect arriving via the intent
filter instead. So treat `RESULT_CANCELED` as "cancelled *unless* a redirect arrives within a short grace window", and
race the two channels.

Two fallbacks to keep separate: no Custom-Tabs-capable browser at all (`CustomTabsClient.getPackageName`
returns null → plain `ACTION_VIEW`), and Auth Tab unsupported (above).

`LocalUriHandler` is **not** sufficient. Its Android actual is a bare `ACTION_VIEW` with no
`FLAG_ACTIVITY_NEW_TASK`, no Custom Tabs, no result channel and no way to close the tab
`[verified from AndroidUriHandler.android.kt]`. Custom Tabs needs a real platform hook.

### 4.2 Receiving the redirect

Custom scheme is the right choice. RFC 8252 §7.1 wants reverse-DNS naming, i.e.
`io.challenge-workshop.malui://oauth/callback` rather than `malui://callback`.

App Links (`https` + `assetlinks.json`) are more secure — ownership is cryptographically proven, no hijacking possible —
but need a real domain hosted purely to bounce a redirect. Disproportionate here. Loopback on Android is actively worse
than a custom scheme: any other app on the device can bind or probe localhost.

Scheme hijacking by another app is still a real risk in 2026 (RFC 8252 §8.6: "multiple apps can typically register the
same scheme"), mitigated by PKCE + `state` — see [§2](#2-security-plain-pkce-is-weaker-than-it-looks)
for how much `plain` weakens that.

### 4.3 Manifest and plumbing

Put manifest changes in `:app:androidApp` — whether `com.android.kotlin.multiplatform.library` supports a
`src/androidMain/AndroidManifest.xml` contributing `<queries>`/activities is undocumented
`[unverified]`, and keeping them in the application module sidesteps the question.

```xml
<!-- Required on API 30+, or CustomTabsClient.getPackageName() and
     isAuthTabSupported() see no browsers at all. The AAR ships no <queries>. -->
<queries>
    <intent><action android:name="android.support.customtabs.action.CustomTabsService"/></intent>
    <intent>
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.BROWSABLE"/>
        <data android:scheme="https"/>
    </intent>
</queries>

<activity android:name=".MainActivity" android:exported="true"
          android:launchMode="singleTop">
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <category android:name="android.intent.category.BROWSABLE"/>
        <data android:scheme="io.challenge-workshop.malui"
              android:host="oauth" android:path="/callback"/>
    </intent-filter>
</activity>
```

Two changes beyond adding the filter:

- **`launchMode="singleTop"` is mandatory.** The current `standard` mode stacks a *second*
  `MainActivity` with a *different* `ViewModelStore`, so the instance receiving the code is not the one holding the
  verifier. Classic silent failure. Avoid `singleTask`/`singleInstance` — a documented source of task-management grief.
- **`android:allowBackup="false"`** (currently `true`) before persisting tokens, or Auto Backup exfiltrates them to the
  cloud. Also set `dataExtractionRules` to exclude the token file.

`ComponentActivity.onNewIntent` is `open`/`@CallSuper`, and `addOnNewIntentListener` exists as a composable alternative
`[verified in activity-1.13.0 sources]`. Route through a small process-scoped
`SharedFlow` (`replay = 1`, so a redirect landing before the UI collects is not dropped) that the Compose layer
collects, keeping `MainActivity` thin. Call `setIntent(intent)` and null out `intent.data`
after handling so a recreation doesn't re-process it.

### 4.4 What happens while the user is in the tab

Precisely, because this is where the bugs are:

- A Custom Tab is another activity in your task: `MainActivity` goes `onPause` → `onStop`, **not**
  destroyed. The ViewModel survives that and survives rotation.
- The ViewModel does **not** survive process death — and being parked behind a browser on a low-RAM device is an
  ordinary way to get killed, not a theoretical one. This is arguably the top cause of
  "OAuth works on my Pixel, fails on cheap devices".
- On process death + return via redirect you get a fresh ViewModel with `authRequest == null`, and
  `completeLogin()` silently returns. [§3.2](#32-persist-the-in-flight-authorization) is the fix.

### 4.5 Cancellation without Auth Tab

There is no official API. The `onResume` heuristic (flag on launch, check on resume) has real false positives, roughly
in order of how often they bite: the redirect itself resumes you; any interruption (call, notification, biometric
prompt, split-screen) resumes you mid-login; the first `onResume` after launch fires before the tab is on top; a config
change recreates and resumes.

So: best-effort only. **Never destroy the verifier on suspected cancellation** — just re-enable the button. A stale
redirect arriving later is caught by the `state` check.

### 4.6 Do not add AppAuth-Android

`net.openid:appauth` latest is **0.11.1, published 2021-12-22** — no release in 4½ years, empty GitHub Releases page,
240 open issues `[verified via Maven Central + GitHub API]`. It predates Android 11 package visibility, so browser
selection breaks on `targetSdk ≥ 30` without you adding `<queries>`
anyway; it has no Auth Tab support; and its `singleTask` management activity is a known pain point.

It *can* do `plain` — the 3-arg `setCodeVerifier(v, v, CODE_CHALLENGE_METHOD_PLAIN)` overload is unvalidated, so MAL
compatibility is reachable `[verified from source]`; the 1-arg overload always picks S256 and would fail. But it would
replace the easy half of what `:core` already does with an Android-only dependency, breaking the KMP story `:core`exists
for, while leaving the browser plumbing to you.

`kalinjul/kotlin-multiplatform-oidc` is worth reading for API shape — its `startLogin()` /
`canContinueLogin()` / `continueLogin()` split is designed for exactly the process-death problem — but Desktop and
WasmJS are both marked experimental, it lags this repo's Kotlin/Ktor versions, and whether it supports `plain` is
unverified. Don't depend on it.

---

## 5. Desktop

**Recommended:** `com.sun.net.httpserver.HttpServer` on a fixed loopback port (RFC 8252 §7.3), bound before the browser
opens.

### 5.1 Listener and port

`HttpServer` is not deprecated, is in module `jdk.httpserver`, needs no module flags on the classpath, and answers
unregistered paths (including `/favicon.ico`) with an automatic 404
`[verified by running probes on JDK 21 and 25 on this machine]`. Zero new dependencies.

**One trap that will bite at packaging time.** Compose's `DEFAULT_RUNTIME_MODULES` is
`["java.base", "java.desktop", "java.logging", "jdk.crypto.ec"]` `[verified in
compose-gradle-plugin-1.11.1 sources]` — `jdk.httpserver` is absent. So `:app:desktopApp:run` works (full toolchain JDK)
while `packageDeb`/`runDistributable` fails with `NoClassDefFoundError`. Add:

```kotlin
nativeDistributions { modules("jdk.httpserver") }
```

Embedding Ktor instead would drag Netty (~4 MB, plus `sun.misc.Unsafe` → also needs
`jdk.unsupported`, and it is being removed on JDK 24+) onto a client app for one GET. There is nothing to share with
`MalRelay` — the listener answers one route and never talks to MAL.

**Port:** fixed, because MAL does no port-lenient matching
([§1.3](#13-two-behaviours-worth-knowing-do-not-need-testing)). **18040** fits the documented decade-slot convention. Do
*not* keep the current `localhost:8080` — 8080 is heavily contended on a dev machine and outside this project's block.
**Done:** `LOOPBACK_REDIRECT_URI` was deleted rather than repointed, because its KDoc ("the browser fails to load it,
but the address bar still shows `?code=…`") stops being true once something listens. `DESKTOP_REDIRECT_URI` and
`DESKTOP_LOOPBACK_PORT` in `:core` replace it, and 18040 is registered on the MAL app.

**Bind loopback explicitly.** `HttpServer.create(InetSocketAddress(port), 0)` — the obvious-looking call — binds
`0.0.0.0` and exposes the callback to the LAN `[verified]`. Use
`InetSocketAddress(InetAddress.getLoopbackAddress(), port)`. Prefer `127.0.0.1` in the registered URI per RFC 8252 §8.3;
if MAL rejects the IP literal, keep `localhost` but bind **both** families on the same port (verified to work
simultaneously) — `localhost` resolves to both here, which already caused the dev-server bug in `errors.md`.

**Port in use:** the OS refuses the second bind with `BindException` `[verified]`; Java sets no
`SO_REUSEPORT`, so two listeners can never silently race. That makes the exception a free cross-process
"only one login at a time" guard *and* a loud signal if something hijacked the port — provided you **bind before opening
the browser** and surface the error instead of proceeding. Rebinding immediately after `stop(0)` works, so retrying a
cancelled flow is fine `[verified]`.

For reference: `gh` and `aws sso` dodge all of this with the device grant, which MAL does not support.
`gcloud` uses a fixed port with a `--no-launch-browser` fallback — the same shape recommended here.

### 5.2 Callback response page

Serve self-contained HTML with `Content-Type: text/html; charset=utf-8` (`HttpServer` sends no default),
`Cache-Control: no-store`, `Referrer-Policy: no-referrer`, and a real content length.

**No subresources at all** — no external CSS, fonts, or images. The URL in the address bar *contains the authorization
code*, so any subresource request would leak it in a `Referer` header. Inline the styling.

`window.close()` does not work for tabs the script didn't open, so the page must read correctly without it: *"Signed in.
You can close this tab and return to mal_ui."* Optionally 302 to a bare
`/oauth/callback/done` so the code doesn't linger in history — worth it given `plain` PKCE.

Reuse `parseRedirect()` verbatim for the query, so `error=access_denied` produces the same
`MalAuthException` as the paste path. Serve that case as HTTP 200 with an explanation — the *redirect*
succeeded, the authorization didn't. Accept only `GET`/`HEAD`; answer anything else 405.

### 5.3 Lifecycle

- **Bind, then open the browser, then await.** Separating `bind()` from `awaitRedirect()` is what makes a`BindException`
  reportable before the user has already approved on MAL.
- **Never call `stop()` from inside a handler** — `stop(delay)` blocks until handlers complete
  `[docs]`, so that deadlocks. With `executor = null` all handling is on one `HTTP-Dispatcher` thread
  `[verified]`; resume the continuation and do the token exchange in the coroutine, never in the handler.
- **`state` mismatch → 400 and keep listening.** Do not treat it as fatal, or any local process can abort the login at
  will. An `AtomicBoolean` CAS guarantees exactly one code is accepted even if both address families get a hit.
- **Shut down after one success**, on timeout (~5 min, then fall back to the paste field), and on cancellation.
  `suspendCancellableCoroutine` + `invokeOnCancellation { stop(0) }` inside
  `withContext(Dispatchers.IO).use { }` covers every exit path. Add `authJob?.cancel()` to
  `onCleared()`, which currently only closes the `HttpClient`.

### 5.4 Opening the browser: keep `LocalUriHandler`

**`Desktop.browse()` does not work on this machine** — `Desktop.Action.BROWSE` reports `false` on JDK 11, 21, 25 and 26
`[verified]`. Root cause: the JDK adds BROWSE only if GIO's default VFS advertises the `http` scheme, and with `gvfs`not
installed GIO reports only `['file', 'resource']` `[verified by
probing libgio directly]`.

Compose Desktop already handles this — its `DesktopUriHandler` falls back to `xdg-open` on Linux when BROWSE is
unsupported `[verified in ui-desktop-1.11.1 sources]`. So the existing `LocalUriHandler`
approach is correct and a hand-rolled `Desktop.browse()` would be *worse*. Wrap it anyway:

- Run it off the EDT (`withContext(Dispatchers.IO)`) — where BROWSE *is* supported the native path is
  `gtk_show_uri`, which has known hang reports (JDK-8267572, JDK-8275494), and `viewModelScope` on desktop is the Swing
  dispatcher `[verified in lifecycle-viewmodel-desktop-2.11.0-beta01 sources]`.
- It never checks `xdg-open`'s exit code, so success is indistinguishable from silent failure. **Never make the flow
  depend on the launch succeeding** — always show the URL in a selectable field with a copy button, and keep the paste
  path visible. That is the real fallback for headless/SSH/broken-`xdg-open`.

---

## 6. Web

**Recommended:** popup + `postMessage`, with a same-origin `/oauth/callback` route, falling back to a full-page redirect
and then to paste-the-code.

### 6.1 Popup vs full-page redirect

A full-page redirect tears down and re-initializes a multi-MB Wasm bundle (re-download, re-instantiate, re-init Skia).
The popup avoids that, and is viable here:

- **MAL sends no COOP header** on `/`, `/login.php`, or `/v1/oauth2/authorize` `[verified by curl]`, so it stays at the
  default `unsafe-none`, no browsing-context-group switch, and `window.opener` survives the round trip. MAL *does* send
  `X-Frame-Options: SAMEORIGIN`, which independently rules out an iframe flow — popup is the only non-navigating option.
- **webpack-dev-server sets no COOP/COEP header at all** `[verified: zero matches for
  `cross-origin- (opener|embedder|resource)` across wds 5.2.3, and its `set-headers` middleware only
  registers when `options.headers` is defined]`. Kotlin's `DevServer` DSL has no `headers` field. **Do not add
  `COOP: same-origin`** to the dev server or `:server`; if cross-origin isolation is ever needed,
  `same-origin-allow-popups` exists for exactly this case.
- **User gesture** is transient activation — a timestamp window, not lexical synchrony. Chromium and Firefox allow ~5 s;
  **WebKit caps gesture forwarding at 1 s**. Activation is consumed by
  `window.open`, so one popup per gesture, and a blocked popup returns `null` (detectable).

Compose/Wasm is safe here: it registers real DOM `pointerdown`/`pointerup` listeners and dispatches synchronously, and
`Clickable` calls a bare `onClick()` in `handleUpEvent` `[verified in Compose
1.11.1 sources]`. So `Button(onClick = { window.open(...) })` runs with activation intact. **The hazard is your own
code** — any `launch { }` or suspend between the click and `window.open` can lose activation, near-certainly on Safari.
If verifier generation is suspending, open `about:blank`
synchronously and set `location.href` after.

`postMessage`: check **both** `event.origin` (exact match including port) and
`event.source === popupHandle`, and always pass an explicit `targetOrigin`, never `*` — an authorization code is a
credential. Cross-port `postMessage` does work (`opener` and `postMessage` are both on the spec's cross-origin
allowlist), but if each origin registers its own callback path the popup's final document is same-origin with the opener
and the question doesn't arise. Note
`BroadcastChannel` and the `storage` event are origin-scoped (port included), so neither bridges
`:18010` → `:18020`.

### 6.2 Redirect target

Register one callback per web origin ([§1.1](#11-can-one-app-register-multiple-redirect-urls)) and derive `redirect_uri`
at runtime from `window.location.origin`, exactly as `relayEndpointsFor()`
already does.

The alternative — pointing MAL at `:server` (always running for web anyway) and having it hand the code back — **is not
recommended**. It needs server-side `state`→origin mapping, or an origin smuggled through `state` that must then be
allowlist-validated or you have built an open redirector that leaks authorization codes. It only becomes attractive
if [§1.1](#11-can-one-app-register-multiple-redirect-urls)
comes back "one URI only".

### 6.3 Token storage on web

The relevant spec is **`draft-ietf-oauth-browser-based-apps-27`** (July 2026), intended status BCP, **submitted to the
IESG — no RFC number yet** `[verified on datatracker]`. It is blunt: *"none of these options can fully mitigate token
exfiltration when the attacker can execute malicious code"*, and *"there is no guarantee that browser storage is
encrypted at rest"*. It ranks `localStorage` worst (no protection, synchronous), notes `sessionStorage` "is not shared
between multiple tabs… which slightly reduces the exposure", and rates in-memory best but non-persistent.

Recommendation, in order:

1. **Default: in-memory only.** Correct, trivial, matches §8.4 of the draft.
2. **If persistence is wanted: `sessionStorage`, opt-in.** Tab scoping also removes a real hazard — two tabs sharing
   `localStorage` can both refresh and one loses. Never `localStorage` for a refresh token.
3. **The real fix, if it ever matters: promote `MalRelay` to a token-mediating backend or BFF.** It is already
   same-origin and already proxies `/mal`; the upgrade is holding tokens in a server-side session keyed by an `HttpOnly`
   cookie. Worth a note as the "if this were production" path, not worth building now.

### 6.4 Clean the URL after capture

Strip `?code=…` so a reload doesn't retry a single-use code.

`js()` needs **no** `external` declarations and no `JsAny`/`JsString` conversion for this. Its return type is `Nothing`,
so it satisfies any declared return type; it **can** use the enclosing function's parameters; `String`/`Int`/`Boolean`/
`Double` and function types cross the boundary directly
`[verified in kotlin-stdlib-wasm-js-2.4.10 sources]`. Constraints: the call must be the only expression in the body,
`code` must be a compile-time constant, and the function must be package-level with an explicit return type.

```kotlin
fun currentSearch(): String = js("window.location.search")

fun clearAuthQuery() {
    js("window.history.replaceState(null, '', window.location.pathname + window.location.hash)")
}
```

`js()` emits an opt-in **warning** (`ExperimentalWasmJsInterop`, `level = WARNING`), which already fires at
`MalEndpoints.wasmJs.kt:7`. Silence it with a file-level `@OptIn` or
`-opt-in=kotlin.js.ExperimentalWasmJsInterop`.

### 6.5 Sharing browser code between js and wasmJs

**`js()` compiles in shared `webMain` for both targets** — verified by putting a `js()`-using file in
`core/src/webMain/` in a scratch copy and running `:core:compileKotlinJs` and
`:core:compileKotlinWasmJs`, both of which succeeded with warnings citing that file `[verified]`. **No
`jsMain`/`wasmJsMain` actuals are needed.** It works because the stdlib declares `js()` as an `expect`
with return type `Nothing` in its own shared web source set. Gotcha: written *inside* `jsMain` you get the `dynamic`
actual instead, so keep such code in `webMain`.

**`kotlin-browser` 2026.7.2 does publish a wasmJs target** — `kotlin-browser-wasm-js/2026.7.2` exists, with wasm-js
variants throughout the transitive tree, and the js and wasm-js **sources jars are byte-identical**
`[verified via Maven Central module metadata]`. It is already used (in
`app/shared/src/jsMain/.../Platform.js.kt`, declared under `jsMain.dependencies`), and moving that dependency to
`webMain` compiles for both targets `[verified]`. So typed `web.location` / `web.history` /
`web.window.open` / `postMessage` can be shared.

One rough edge: the wrappers' typed event API (`window.messageEvent.addHandler { }`) has a
`where D : E, D : HasTargets<C, T>` constraint that fails inference — a short `js()` snippet is the pragmatic choice for
the `message` listener specifically.

### 6.6 Serving the callback path in dev

webpack-dev-server is **5.2.3** for both targets (`kotlin-js-store/yarn.lock`, and the hoisted toolchain in
`~/.kotlin/kotlin-npm-tooling/`) `[verified]`. `historyApiFallback` defaults to `false`
and is not exposed by Kotlin's `DevServer` DSL, so it goes in `webpack.config.d`, inside the existing guard:

```js
    // Serve index.html for the OAuth callback deep link so /oauth/callback?code=...
    // boots the SPA instead of 404ing. Runs after the /mal proxy, so the relay is unaffected.
    config.devServer.historyApiFallback = {
        index: '/index.html',
        disableDotRule: false,
    };
```

Validated against wds 5.2.3's `options.json` via `schema-utils` `[verified]`. Middleware order is
`host-header-check` → `cross-origin-header-check` → proxy → dev-middleware → static →
`connect-history-api-fallback` → …, so `/mal/*` is claimed by the proxy first and the fallback only catches what nothing
else served — **no conflict** `[verified from Server.js]`.

Non-obvious detail worth a code comment: `connect-history-api-fallback` does
`req.url = options.index`, **discarding the query string**. That is server-side only — the address bar still has
`?code=…`, so `window.location.search` is intact. It also only rewrites `GET`/`HEAD` with an HTML-ish `Accept`, and
skips paths whose last segment contains a dot, so keep the path extension-less (`/oauth/callback`, not`/callback.html`).

The reverse proxy needs the equivalent: `/mal` → `127.0.0.1:18010` declared first, then
`try_files {path} /index.html` for the SPA.

---

## 7. Client ID configuration

A public client's ID **is not a secret** — the browser-based-apps draft §6.3.3.1 says authorization servers "MUST NOT
require client authentication of browser-based applications using a shared secret, as this serves no value beyond client
identification which is already provided by the `client_id`
parameter", and RFC 8252 §8.5 says statically-distributed secrets "should not be treated as confidential" `[docs]`. It
is also visible in the user's own address bar. So the goal is *convenience and not-in-git*, not secrecy. `MalAuthConfig`
's KDoc already gets this right.

**Recommended:** `providers.gradleProperty("mal.clientId")` → a generated `commonMain const val`, with env-var and
`local.properties` fallbacks. Resolution order at runtime: persisted user-entered value → build-time default → prompt.
Prefill the existing text field from the default so it becomes an override rather than a mandatory step.

Precedence, best-to-worst for never-in-git: `~/.gradle/gradle.properties` (outside the repo entirely, and CI gets it
free via `ORG_GRADLE_PROJECT_mal_clientId`) → `local.properties` (already gitignored)
→ `MAL_CLIENT_ID` env var.

**Skip BuildKonfig.** 0.22.0's breaking change was *"remove standalone Kotlin/JS plugin support to unblock Kotlin
2.4.0"* and its README documents no wasmJs support `[verified from CHANGELOG]`; this repo has both js and wasmJs. Not
worth a plugin dependency for one string.

**The configuration-cache trap `CLAUDE.md` warns about is exactly here.** Reading `local.properties`
with `Properties().load(...)` at configuration time fails CC validation. Use lazy `Provider`s throughout and declare the
value as a task input:

```kotlin
val malClientId: Provider<String> =
    providers.gradleProperty("mal.clientId")
        .orElse(providers.environmentVariable("MAL_CLIENT_ID"))
        .orElse(
            providers.fileContents(layout.settingsDirectory.file("local.properties"))
                .asText.map { text ->
                    text.lineSequence()
                        .firstOrNull { it.startsWith("mal.clientId=") }
                        ?.substringAfter('=')?.trim().orEmpty()
                }
        )
        .orElse("")

val generateMalBuildConfig by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/malConfig/kotlin")
    inputs.property("clientId", malClientId)   // CC-safe + correct invalidation
    outputs.dir(outDir)
    doLast { /* write MalBuildConfig.kt with a const val */ }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(generateMalBuildConfig) }
```

A plain `const val` in `commonMain` needs no expect/actual and works on all four targets — the whole advantage over
BuildKonfig. Document `mal.clientId` in `CLAUDE.md`, and add a `local.properties.example`.

---

## 8. Token storage per platform

**No KMP library credibly covers android + jvm + js + wasmJs with encryption** `[verified against
artifact metadata]`, so hand-roll a 3-method facade — ~15 lines per target, and it avoids a stale dependency:

```kotlin
// :core commonMain
interface KeyValueStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
}
```

`MalTokens` is already `@Serializable`, so a `JsonTokenStore` over this composes for free. Version the key
(`mal.session.v1`) so a format change is a clean re-login, not a crash loop.

| Target      | Backing                                                    | Notes                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
|-------------|------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Android     | DataStore or plain `SharedPreferences`                     | `androidx.security.crypto` is **fully deprecated** — 1.1.0 stable shipped 2025-07-30 with every API deprecated, and the Javadoc literally says `@deprecated Use SharedPreferences instead` `[verified]`. Since Android 10 enforces file-based encryption device-wide and the sandbox already blocks other apps, the security delta was ~0 while adding StrictMode violations and Tink keyset-corruption crashes. **Excluding from Auto Backup matters more than encryption.** |
| Desktop JVM | file with `0600` perms under `$XDG_STATE_HOME` / `AppData` | OS keychain integration is over-engineering here: every JVM keychain library found (`microsoft/credential-secure-storage`, `java-keyring`) looks dormant, all pull JNA + native bits that complicate `jpackage`, and headless Linux/CI has no Secret Service anyway so you need the file fallback regardless. Design the interface so it can be swapped later.                                                                                                                |
| js / wasmJs | in-memory, or `sessionStorage` opt-in                      | See [§6.3](#63-token-storage-on-web). One shared impl in `webMain`.                                                                                                                                                                                                                                                                                                                                                                                                           |

Libraries evaluated and rejected: **multiplatform-settings** 1.3.0 does publish wasm-js artifacts
`[verified]` but has no encrypted variant (the maintainer's answer points at the now-deprecated ESP), and its DataStore
module has neither js nor wasm. **KVault** is Android+iOS only. **KStore** covers all four but has no encryption.
**androidx.datastore** publishes a `wasm` variant of
`datastore-preferences-core` but **no `js`** variant before `1.3.0-alpha10` `[verified from module
metadata]` — a blocker while this repo ships both web targets.

---

## 9. Implementation order

Each phase is independently testable and leaves the app working.

**Phase 0 — settle the unknowns. Done, 2026-08-06.** [§1](#1-verified-2026-08-06) is measured, all eight redirect URIs
are registered on one client ID, and the answers are recorded there. Ticket 09 and everything downstream of it are
unblocked. Re-run [`docs/adr/mal-redirect-uri-probes.http`](adr/mal-redirect-uri-probes.http) if the registered set
changes.

**Phase 1 — shared foundation, no UI change.** All in `:core`, all covered by `:core:allTests`.
`KeyValueStore` + `JsonTokenStore` + `StoredSession` + `PendingAuthorization`; `SessionState`;
`MalSessionRepository` with the Ktor `Auth` bearer provider. Platform `KeyValueStore` actuals in
`:app:shared`. Tests: refresh-once-under-concurrent-401s, `invalid_grant` clears the store, transport error does
**not**, `StoredSession` round-trip, corrupt blob treated as absent.

**Phase 2 — rework the ViewModel onto `SessionState`,** still paste-the-code. Persist
`PendingAuthorization` in `beginAuthorization()`. This alone fixes the silent-failure bug and gives persistent sessions.
Also extend `hintFor("invalid_client")` to mention redirect-URI mismatch
([§1.3](#13-two-behaviours-worth-knowing-do-not-need-testing)).

**Phase 3 — desktop redirect.** Smallest platform surface, no new dependencies, and the one platform where an end-to-end
integration test is cheap (hit `127.0.0.1` with a Ktor client). `LoopbackRedirectListener`
in `:app:shared/jvmMain`, `expect`/`actual` capture API, `LOOPBACK_REDIRECT_URI` → `18040`,
`modules("jdk.httpserver")`, port table entry in `CLAUDE.md`.

**Phase 4 — web redirect.** `/oauth/callback` route, `historyApiFallback`, popup + `postMessage` with full-page-redirect
fallback, `clearAuthQuery()`. Shared `webMain` implementation; consider moving
`wrappers-browser` from `jsMain` to `webMain`. Reverse-proxy route.

**Phase 5 — Android redirect.** Most moving parts. `androidx.browser` 1.10.0, `<queries>`,
`launchMode="singleTop"`, `allowBackup="false"` + `dataExtractionRules`, custom-scheme filter, the redirect`SharedFlow`,
`AuthTabIntent` with the raced intent-filter fallback.

**Phase 6 — polish.** Client ID via Gradle property ([§7](#7-client-id-configuration)); update
`errors.md` with the `plain`-PKCE weakening, the `Desktop.Action.BROWSE` finding, and MAL's byte-exact/401-
`invalid_client` matching behaviour.

**Keep paste-the-code throughout.** It is the only mechanism that works everywhere, it is already tested, and it is the
documented fallback for headless desktop, blocked popups, and browsers without Custom Tabs. Model it as
`AuthRedirectResult.Unsupported` rather than deleting it.

---

## 10. Open questions

Carried forward from the research; each is `[unverified]`:

1. **`androidx.browser` 1.10.0 under AGP 9 + compileSdk 36** — no reported problem, not build-tested.
2. **Does `com.android.kotlin.multiplatform.library` support `src/androidMain/AndroidManifest.xml`?**
   Sidestepped by keeping manifest changes in `:app:androidApp`.
3. **Real-world share of Chrome ≥137**, i.e. how often the Auth Tab fallback path actually runs.
4. **multiplatform-settings 1.3.0 consumed from Kotlin 2.4.10** — klib compatibility should hold; not compiled. Only
   matters if §8's recommendation is overridden.

Closed 2026-08-06, both by measurement and both in the permissive direction — kept here so they are not reopened by
accident: **multiple redirect URIs per MAL app** ([§1.1](#11-can-one-app-register-multiple-redirect-urls)) and **which
URI forms apiconfig accepts** ([§1.2](#12-does-the-registration-form-accept-these-uri-forms)). The corollary that *did*
change the code is that omitting `redirect_uri` is no longer legal.

Still unverified but out of MAL's hands, because nothing was listening when the probes ran: that the web dev servers
serve `/oauth/callback` under `historyApiFallback`, that the `/mal` prefix agrees across `:core`, the webpack dev server
and the reverse proxy, and that the desktop loopback listener does not bind `0.0.0.0`. §D and §E of
[the probe file](adr/mal-redirect-uri-probes.http) cover these; they belong to phases 3 and 4, not to phase 0.

Corrections made during research, recorded so they are not re-introduced: `state` **is** already verified
(`MalAuthClient.kt:71`); `MalAuthClient.refresh()`'s KDoc about old refresh tokens staying valid **is** correct per
MAL's docs. Both were claimed otherwise at some point.

---

## Sources

Specs: [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.txt) (native apps) ·
[RFC 7636](https://www.rfc-editor.org/rfc/rfc7636.txt) (PKCE) ·
[RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.txt) (OAuth security BCP) ·
[draft-ietf-oauth-browser-based-apps-27](https://www.ietf.org/archive/id/draft-ietf-oauth-browser-based-apps-27.txt)
([datatracker](https://datatracker.ietf.org/doc/draft-ietf-oauth-browser-based-apps/))

MAL: [authorization docs](https://myanimelist.net/apiconfig/references/authorization) ·
[ZeroCrystal's OAuth2 guide](https://myanimelist.net/blog.php?eid=835707) ·
[sticky API thread](https://myanimelist.net/forum/?topicid=1850649) ·
[AppAuth/`plain` thread](https://myanimelist.net/forum/?topicid=2090281) ·
[redirect_uri mismatch → 401](https://myanimelist.net/forum/?topicid=2241369)

Prior art: [MoeList](https://github.com/axiel7/MoeList) ·
[Aniyomi](https://github.com/aniyomiorg/aniyomi) ·
[Mal4J](https://github.com/KatsuteDev/Mal4J/blob/main/setup.md) ·
[kalinjul/kotlin-multiplatform-oidc](https://github.com/kalinjul/kotlin-multiplatform-oidc) ·
[AppAuth-Android](https://github.com/openid/AppAuth-Android)

Android: [androidx.browser releases](https://developer.android.com/jetpack/androidx/releases/browser) ·
[Auth Tab guide](https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab) ·
[androidx.security releases (deprecation)](https://developer.android.com/jetpack/androidx/releases/security) ·
[App Links troubleshooting](https://developer.android.com/training/app-links/troubleshoot) ·
[AGP 9 KMP migration](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html)

Desktop: [
`HttpServer` javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html) ·
[Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html) ·
[JDK-8214069](https://bugs.openjdk.org/browse/JDK-8214069) (xdg-open / BROWSE)

Ktor / KMP: [Ktor bearer auth](https://ktor.io/docs/client-bearer-auth.html) ·
[Ktor CHANGELOG](https://github.com/ktorio/ktor/blob/main/CHANGELOG.md) ·
[KTOR-8285](https://youtrack.jetbrains.com/issue/KTOR-8285) ·
[Lifecycle releases](https://developer.android.com/jetpack/androidx/releases/lifecycle) ·
[DataStore releases](https://developer.android.com/jetpack/androidx/releases/datastore) ·
[multiplatform-settings](https://github.com/russhwolf/multiplatform-settings) ·
[KStore](https://github.com/xxfast/KStore)

Read directly during research: Ktor 3.5.1 `AuthTokenHolder`/`Auth.kt`; Compose 1.11.1
`ComposeWindowInternal.web.kt`, `Clickable.kt`, `PlatformUriHandler.desktop.kt`,
`JvmApplicationDistributions.kt`; `activity-1.13.0` `ComponentActivity.kt`;
`lifecycle-viewmodel-desktop-2.11.0-beta01`; `kotlin-stdlib-wasm-js-2.4.10` `js/core.kt`;
`browser-1.10.0.aar`; webpack-dev-server 5.2.3 `lib/Server.js` + `options.json`; OpenJDK
`gtk3_interface.c` / `XDesktopPeer.java`.
