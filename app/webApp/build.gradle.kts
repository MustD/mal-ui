import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Dev server ports, from this project's 18010-18090 block:
//   18010  :server (Ktor MAL relay)
//   18020  this module, wasmJs target
//   18030  this module, js target
// The two web targets get separate ports so both can run at once.
//
// Host binding, allowed hosts, and the /mal proxy live in webpack.config.d/devserver.js,
// which both targets pick up. Ports are set here instead so they can differ per target.
//
// Each port is written inline rather than held in a script-level `val`: the webpack config
// block would then capture the enclosing script object, which the configuration cache
// cannot serialize.
kotlin {
    js {
        browser {
            commonWebpackConfig {
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply { port = 18030 }
            }
        }
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply { port = 18020 }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":app:shared"))

            implementation(libs.compose.ui)
        }
    }
}
