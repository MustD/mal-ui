package io.challenge_workshop.mal_ui.di

import io.challenge_workshop.mal_ui.auth.StartupRedirect
import io.challenge_workshop.mal_ui.auth.WebStartupRedirect
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

    @Test
    fun initKoin_wires_the_startup_redirect_web_alone_can_have() {
        // Web is the only target that can be *launched* holding a redirect. Wired to
        // `StartupRedirect.None` by mistake, a blocked popup would send the user out to MyAnimeList
        // and bring them back to a sign-in screen that had forgotten the whole thing.
        val koin = initKoin().koin

        assertIs<WebStartupRedirect>(koin.get<StartupRedirect>())

        koin.get<MalSessionRepository>().close()
    }
}
