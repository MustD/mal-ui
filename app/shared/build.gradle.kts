import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    android {
        namespace = "io.challenge_workshop.mal_ui.app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.koin.android)
        }
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            // `api` because the app modules call `initKoin()` and Koin's own APIs from
            // their entry points; `implementation` would hide `Module` from them.
            api(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.clientMock)
            implementation(libs.koin.test)
        }
        // Robolectric so `AndroidKeyValueStore` is exercised against a real `SharedPreferences`
        // on the host. `withHostTest { isIncludeAndroidResources = true }` above is what makes
        // it work; the alternative was a device test, which needs an emulator to mean anything.
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.robolectric)
        }
        // The Compose UI tests run on this target only. The routing they exercise is common code
        // with no expect/actual in it, and the other three targets would each need a second test
        // harness — Robolectric, karma — to prove the same thing.
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            // Skiko's host-native binaries. Without them a UI test has nothing to draw on.
            implementation(compose.desktop.currentOs)
            // `SessionState::class.sealedSubclasses`, so the test's own coverage is checked against
            // the sealed interface rather than against a list someone has to remember to update.
            implementation(libs.kotlin.reflect)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
