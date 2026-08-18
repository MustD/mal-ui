# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Kotlin Multiplatform / Compose Multiplatform project targeting Android, Web (JS + Wasm), Desktop (JVM), and a Ktor server. Package namespace throughout: `io.challenge_workshop.mal_ui`.

## Commands

Build / run:

```bash
./gradlew :app:androidApp:assembleDebug              # Android APK
./gradlew :app:desktopApp:run                        # Desktop
./gradlew :app:desktopApp:hotRun --auto              # Desktop with Compose Hot Reload
./gradlew :server:run                                            # Ktor server on :18010
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun --continuous   # Web, Wasm target, on :18020
./gradlew :app:webApp:jsBrowserDevelopmentRun --continuous       # Web, JS target, on :18030
```

`--continuous` is required for the web targets, not just for hot reload: the run task starts webpack-dev-server without
blocking, so without it Gradle finishes (`BUILD SUCCESSFUL` in well under a second) and takes the dev server down with
it, leaving nothing on the port.

The web targets need `:server` running as well — see [MAL authentication](#mal-authentication).

### Ports

This project owns the **18010–18090** block. Ports are assigned in decade slots; 18050 onwards is unallocated.

| Port  | Service                                              |
|-------|------------------------------------------------------|
| 18010 | `:server` — Ktor, MAL relay, loopback only           |
| 18020 | `:app:webApp` dev server, wasmJs target              |
| 18030 | `:app:webApp` dev server, js target                  |
| 18040 | Desktop OAuth callback on `127.0.0.1` — `LoopbackRedirectListener`, bound only while signing in |

18040 cannot be made ephemeral: MAL does no RFC 8252 §7.3 port-lenient matching, so the port is part of the byte-exact
Redirect URI registered on the app (`DESKTOP_LOOPBACK_PORT` in `:core`). `LoopbackRedirectListener` in
`:app:shared/jvmMain` binds it for the length of one sign-in and gives it back on success, timeout or cancellation, so
a `BindException` there means either a sign-in is already in progress or something else has taken the port. The
listener uses `com.sun.net.httpserver`, which is why `:app:desktopApp` declares
`nativeDistributions { modules("jdk.httpserver") }` — Compose's default runtime modules do not include it, and the gap
only shows up in a packaged build.

The two web targets have separate ports so both can run at once. Ports are set per target in
`app/webApp/build.gradle.kts`; everything shared by both (host binding, `allowedHosts`, the `/mal` proxy, and the
`historyApiFallback` that serves the app on `/oauth/callback`) is in `app/webApp/webpack.config.d/devserver.js`.

Optionally reachable as `https://mal-ui.localhost` (wasmJs) and `https://js.mal-ui.localhost`
(js) through a local reverse proxy that routes `/mal` to 18010 and everything else to the dev server. Same-origin
routing is required, not cosmetic — see below. That config lives outside this repo; in Caddy terms it is:

```caddyfile
mal-ui.localhost {
	handle /mal /mal/* {       # first, so the relay is never swallowed by the SPA fallback below
		reverse_proxy 127.0.0.1:18010
	}
	handle {
		reverse_proxy 127.0.0.1:18020   # 18030 for js.mal-ui.localhost
	}
}
```

The dev server's own `historyApiFallback` covers the SPA deep link on both paths, so the proxy needs no `try_files` of
its own — but it does need `/mal` matched **first**, or `/mal/...` reaches the dev server and comes back as
`index.html`. If the app is ever served from a static bundle instead of the dev server, that side needs
`try_files {path} /index.html` added.

Tests — there is no single aggregate target that covers everything; each platform has its own task:

```bash
./gradlew :app:shared:testAndroidHostTest   # androidHostTest + commonTest
./gradlew :app:shared:jvmTest               # jvmTest + commonTest
./gradlew :app:shared:wasmJsTest            # webTest + commonTest
./gradlew :app:shared:jsTest                # webTest + commonTest
./gradlew :app:androidApp:testDebugUnitTest # the manifest drift guard, and nothing else
./gradlew :server:test
./gradlew build                             # everything
```

Single test (works for JVM-hosted test tasks):

```bash
./gradlew :app:shared:jvmTest --tests "io.challenge_workshop.mal_ui.SharedLogicDesktopTest"
./gradlew :app:shared:jvmTest --tests "*.SharedCommonTest.example"
```

`:core` has the same per-target split; `./gradlew :core:allTests` covers all four at once.

## MAL authentication

MAL supports **only** the OAuth2 authorization code grant with PKCE. There is no password grant — `grant_type=password`
returns `unsupported_grant_type`, and the legacy basic-auth
`/api/account/verify_credentials.xml` endpoint is retired (403). A username/password form therefore cannot work; the
password is only ever typed on myanimelist.net.

MAL supports `code_challenge_method=plain` only, so the PKCE challenge equals the verifier and no SHA-256 is involved
(`Pkce` in `:core`).

Register the app at myanimelist.net/apiconfig with **App Type `other`** — that issues a Client ID and no secret, which
is correct for a public client.

The Client ID is entered at runtime so no real one is committed.

Each target captures the redirect itself (`AuthRedirectChannel` in `:app:shared/auth`): desktop on a loopback listener,
web in a popup. **Paste-the-code is not dead code** — it is the modelled fallback for a headless desktop, a blocked
popup or a missing Custom-Tabs browser, and it is the path every capture funnels into, so there is one parser and one
set of error messages. Android is still on it pending ticket 16.

**Web needs the relay, on the same origin.** MAL sends no CORS headers on its token or API endpoints and answers
preflight `OPTIONS` with 405, so a browser cannot call them at all.
`:server` relays them (`MalRelay.kt`) under the `/mal` prefix, and `:core`'s
`expect fun platformMalEndpoints()` routes the browser targets there while jvm/android call MAL directly.

The browser actuals derive the relay URL from `window.location.origin`, so the same build works behind the reverse proxy
and on a direct dev-server port. Both paths keep the relay same-origin — the reverse proxy routes `/mal`, and the
webpack dev server proxies it — which is what makes CORS a non-issue rather than something to work around. Moving the
relay to its own hostname would reintroduce the original failure.

Web therefore needs two processes:

```bash
./gradlew :server:run                                            # relay on :18010
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun --continuous   # app on :18020
```

Three things must agree on the `/mal` prefix: `MAL_RELAY_PATH_PREFIX` in `:core`, the reverse-proxy route, and the
dev-server proxy in `webpack.config.d/devserver.js`.

Details and the full diagnosis are in `docs/errors.md`.

## Architecture

Two tiers of shared code, deliberately separated:

- **`:core`** — pure Kotlin Multiplatform, **no Compose dependency**. Logic that must also be usable by the Ktor server (`:server` depends on it as a plain JVM library). Targets jvm, js, wasmJs, android.
- **`:app:shared`** — Compose Multiplatform UI plus platform abstractions. Exposes `:core` via `api(project(":core"))`, so app modules get `:core`'s API transitively.

The three client modules (`:app:androidApp`, `:app:desktopApp`, `:app:webApp`) are thin entry points only: each has a `main`/`Activity` that sets up its platform's window and calls the single `App()` composable from `:app:shared`. Put UI in `:app:shared`, not in the app modules.

`:server` is a standalone Ktor/Netty app (`Application.kt`) that shares only `:core`.

### expect/actual

`Platform.kt` in `commonMain` declares `expect fun getPlatform(): Platform`. Actuals live in `Platform.android.kt`, `Platform.jvm.kt`, `Platform.js.kt`, `Platform.wasmJs.kt`. Adding a target means adding an actual in that source set or the build breaks at compile time.

### Source-set layout quirks

- `webMain` / `webTest` are the intermediate source sets shared by **both** the `js` and `wasmJs` targets (from Kotlin's default hierarchy template). `:app:webApp` has only `webMain` — one source tree, two executables. Code in `webTest` runs under both `jsTest` and `wasmJsTest`.
- `:core` and `:app:shared` use the **`com.android.kotlin.multiplatform.library`** plugin (AGP 9), not the classic library plugin. Its unit tests live in **`androidHostTest`**, not `androidUnitTest`/`test`. The task is `testAndroidHostTest`.
- `:app:androidApp` is the only classic Android module (`com.android.application`) and holds all Android resources and the manifest.

### Compose resources

Assets go in `app/shared/src/commonMain/composeResources/<qualifier>/` and are reached through the generated accessor package `mal_ui.app.shared.generated.resources.Res` (e.g. `Res.drawable.compose_multiplatform`). The generated package is derived from the module path, so it changes if the module is moved or renamed.

### Web hosting page

`app/webApp/src/webMain/resources/index.html` hardcodes `<script src="/webApp.js">`. Both the JS and Wasm builds emit that filename, so the same page serves both targets.

The leading slash is load-bearing: the same page is served for `/oauth/callback` by the dev server's
`historyApiFallback`, and a relative `webApp.js` there resolves to `/oauth/webApp.js`, which 404s — the page renders its
loading spinner and the app never boots. `curl` cannot see this; it gets a 200 and the right HTML.

### Web sign-in: the popup, and two things that must not change

The web Redirect Capture is a popup that `postMessage`s the redirect back to `window.opener`
(`PopupRedirectChannel`), falling back to a full-page redirect when the browser blocks it and then to Paste-the-code.

- **Never set `Cross-Origin-Opener-Policy: same-origin`** on the dev server, `:server`, or the reverse proxy. It severs
  `window.opener` when the popup navigates to myanimelist.net, and the login then hangs with no error anywhere. Nothing
  sets COOP today, which is what makes the popup possible at all; `same-origin-allow-popups` is the value to use if
  cross-origin isolation is ever needed.
- **The popup must not boot the app.** `main()` calls `relaySignInRedirectToOpener()` *before* `initKoin()` and returns
  if it answers true. A `window.open`ed document gets its **own copy** of `sessionStorage`, not a shared view, so a
  popup that started the app would exchange the code against its own copy and clear a Pending Authorization the opener
  would never see cleared.

### Android sign-in: the manifest is load-bearing

Four things in `app/androidApp/src/main/AndroidManifest.xml` are each a silent failure if lost, so
`AndroidManifestTest` — the only test in that module — asserts all four:

- **`launchMode="singleTop"`.** Under `standard` the redirect Intent starts a *second* `MainActivity` with its own
  `ViewModelStore`, so the instance that receives the code is not the one holding the Pending Authorization. Nothing
  throws; the redirect simply does nothing. `singleTask`/`singleInstance` avoid that too, at the price of
  task-management surprises.
- **The custom-scheme intent filter** must stay byte-identical to `ANDROID_REDIRECT_URI` in `:core`, which MAL compares
  byte-exactly. It is spread over three attributes (`scheme` / `host` / `path`) there and one string in Kotlin, which is
  exactly how the two drift.
- **`<queries>`.** Without it, on API 30+ `CustomTabsClient.getPackageName()` and `isAuthTabSupported()` see no browsers
  at all — the `androidx.browser` AAR ships none of its own — and sign-in quietly degrades to Paste-the-code.
- **`allowBackup="false"`, plus the two rules files.** Auto Backup would copy the refresh token to the cloud.
  `allowBackup` covers cloud backup; device-to-device transfer on API 31+ is governed by
  `res/xml/data_extraction_rules.xml` alone and ignores that flag; and below API 31 those rules are ignored in turn and
  `res/xml/backup_rules.xml` (`android:fullBackupContent`) is what would count. So the `SharedPreferences` file
  (`MAL_STORE_NAMESPACE` + `.xml`) is named in all three places. The app therefore does not participate in backup at all
  — deliberate, since the only thing worth backing up is the one thing that must not be.

## Conventions

- **All dependency and plugin versions live in `gradle/libs.versions.toml`.** Build scripts reference `libs.*` aliases only — never inline a version string in a `build.gradle.kts`.
- Java toolchain is 21 (auto-provisioned via the foojay resolver / `gradle/gradle-daemon-jvm.properties`); Android and Android-KMP modules compile to **JVM target 11**.
- Gradle configuration cache and build cache are enabled in `gradle.properties`. Build logic that reads state at execution time will fail configuration-cache validation.

## Agent skills

### Issue tracker

Issues live as markdown files under `.scratch/<feature>/` — this repo has no git remote. See
`docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles, used verbatim. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context — one root `CONTEXT.md` plus `docs/adr/`. See `docs/agents/domain.md`.
