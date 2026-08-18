package io.challenge_workshop.mal_ui.di

import io.challenge_workshop.mal_ui.auth.StartupRedirect
import io.challenge_workshop.mal_ui.session.FileKeyValueStore
import io.challenge_workshop.mal_ui.session.KeyValueStore
import io.challenge_workshop.mal_ui.session.MAL_STORE_NAMESPACE
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<KeyValueStore> { FileKeyValueStore.defaultFor(MAL_STORE_NAMESPACE) }

    // A desktop redirect always arrives at a running process, on the loopback listener.
    single<StartupRedirect> { StartupRedirect.None }
}
