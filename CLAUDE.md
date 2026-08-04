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
./gradlew :server:run                                # Ktor server on :8080
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun    # Web, Wasm target
./gradlew :app:webApp:jsBrowserDevelopmentRun        # Web, JS target
```

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
