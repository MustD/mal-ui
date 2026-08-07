package io.challenge_workshop.mal_ui.di

import io.challenge_workshop.mal_ui.session.FileKeyValueStore
import io.challenge_workshop.mal_ui.session.KeyValueStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

class PlatformModuleJvmTest {

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun initKoin_wires_the_desktop_file_store() {
        val koin = initKoin().koin

        assertIs<FileKeyValueStore>(koin.get<KeyValueStore>())

        koin.get<MalSessionRepository>().close()
    }
}
