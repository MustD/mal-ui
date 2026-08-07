package io.challenge_workshop.mal_ui.di

import android.content.Context
import io.challenge_workshop.mal_ui.session.AndroidKeyValueStore
import io.challenge_workshop.mal_ui.session.KeyValueStore
import io.challenge_workshop.mal_ui.session.MAL_STORE_NAMESPACE
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<KeyValueStore> {
        // Asserted rather than left to NPE at the first read. The `Context` arrives via
        // `androidContext()` from `Application.onCreate`, which runs before any Activity; Koin
        // started from anywhere else leaves this definition with nothing to open prefs on, and the
        // symptom would otherwise be a crash on the first store access rather than at startup.
        val context = getOrNull<Context>() ?: error(
            "No Android Context in the Koin graph. initKoin() must be called from " +
                "MalUiApplication.onCreate with androidContext(this).",
        )
        AndroidKeyValueStore(context, MAL_STORE_NAMESPACE)
    }
}
