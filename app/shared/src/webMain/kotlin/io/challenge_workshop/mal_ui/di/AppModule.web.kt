package io.challenge_workshop.mal_ui.di

import io.challenge_workshop.mal_ui.session.KeyValueStore
import io.challenge_workshop.mal_ui.session.MAL_STORE_NAMESPACE
import io.challenge_workshop.mal_ui.session.SessionStorageKeyValueStore
import org.koin.core.module.Module
import org.koin.dsl.module

/** One actual for both web targets — `webMain` is shared by `js` and `wasmJs`. */
actual val platformModule: Module = module {
    single<KeyValueStore> { SessionStorageKeyValueStore(MAL_STORE_NAMESPACE) }
}
