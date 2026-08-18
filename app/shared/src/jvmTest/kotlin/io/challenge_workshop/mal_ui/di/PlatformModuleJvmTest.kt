package io.challenge_workshop.mal_ui.di

import io.challenge_workshop.mal_ui.auth.StartupRedirect
import io.challenge_workshop.mal_ui.session.FileKeyValueStore
import io.challenge_workshop.mal_ui.session.KeyValueStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class PlatformModuleJvmTest {

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun initKoin_wires_the_desktop_file_store() {
        val koin = initKoin().koin

        assertIs<FileKeyValueStore>(koin.get<KeyValueStore>())
        // Declared rather than left out: without it the ViewModel does not resolve at all, and the
        // symptom is a crash on launch rather than a missing feature.
        assertSame(StartupRedirect.None, koin.get<StartupRedirect>())

        koin.get<MalSessionRepository>().close()
    }
}
