import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
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
        namespace = "io.challenge_workshop.mal_ui.core"
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
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(libs.ktor.clientCore)
            implementation(libs.ktor.clientAuth)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.clientMock)
        }
        // A Ktor engine must be on each target's runtime classpath for the
        // engine-less `HttpClient { }` factory in commonMain to resolve one.
        androidMain.dependencies {
            implementation(libs.ktor.clientOkhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.clientCio)
        }
        webMain.dependencies {
            implementation(libs.ktor.clientJs)
        }
    }
}
