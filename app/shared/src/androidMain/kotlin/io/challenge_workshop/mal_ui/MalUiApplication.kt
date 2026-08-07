package io.challenge_workshop.mal_ui

import android.app.Application
import io.challenge_workshop.mal_ui.di.initKoin
import org.koin.android.ext.koin.androidContext

/**
 * Exists only to start Koin with an Android `Context` in the graph.
 *
 * `Application.onCreate` runs before any Activity, which is the ordering the token store depends on —
 * see `platformModule` in `AppModule.android.kt`. Referenced from `:app:androidApp`'s manifest via
 * `android:name`; living in this module rather than in the app module keeps `koin-android` off the
 * app module's classpath.
 */
class MalUiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin { androidContext(this@MalUiApplication) }
    }
}
