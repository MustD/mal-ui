package io.challenge_workshop.mal_ui.di

import io.challenge_workshop.mal_ui.session.KeyValueStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.SessionStorageKeyValueStore
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

/** Runs under both `jsTest` and `wasmJsTest` — one `webMain` actual serves both targets. */
class PlatformModuleWebTest {

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun initKoin_wires_the_session_storage_store() {
        val koin = initKoin().koin

        assertIs<SessionStorageKeyValueStore>(koin.get<KeyValueStore>())

        koin.get<MalSessionRepository>().close()
    }
}
