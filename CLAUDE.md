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

This project owns the **18010–18090** block. Ports are assigned in decade slots; 18040 onwards is unallocated.

| Port  | Service                                    |
|-------|--------------------------------------------|
| 18010 | `:server` — Ktor, MAL relay, loopback only |
| 18020 | `:app:webApp` dev server, wasmJs target    |
| 18030 | `:app:webApp` dev server, js target        |

The two web targets have separate ports so both can run at once. Ports are set per target in
`app/webApp/build.gradle.kts`; everything shared by both (host binding, `allowedHosts`, the `/mal` proxy) is in
`app/webApp/webpack.config.d/devserver.js`.

Optionally reachable as `https://mal-ui.localhost` (wasmJs) and `https://js.mal-ui.localhost`
(js) through a local reverse proxy that routes `/mal` to 18010 and everything else to the dev server. Same-origin
routing is required, not cosmetic — see below.

Tests — there is no single aggregate target that covers everything; each platform has its own task:

```bash
./gradlew :app:shared:testAndroidHostTest   # androidHostTest + commonTest
./gradlew :app:shared:jvmTest               # jvmTest + commonTest
./gradlew :app:shared:wasmJsTest            # webTest + commonTest
./gradlew :app:shared:jsTest                # webTest + commonTest
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

The login screen (`:app:shared`, `auth/`) uses a paste-the-code flow: it opens MAL in a browser, the user approves, and
pastes the resulting redirect URL back in. Credentials are entered at runtime so no real Client ID is committed.

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

`app/webApp/src/webMain/resources/index.html` hardcodes `<script src="webApp.js">`. Both the JS and Wasm builds emit that filename, so the same page serves both targets.

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
