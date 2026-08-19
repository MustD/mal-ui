package io.challenge_workshop.mal_ui.di

import io.challenge_workshop.mal_ui.mal.HttpClientFactory
import io.challenge_workshop.mal_ui.mal.MAL_CLIENT_ID
import io.challenge_workshop.mal_ui.mal.MalUser
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.KeyValueStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.time.Clock

/**
 * Resolves every type [appModule] declares, on all four targets.
 *
 * This stands in for Koin's `checkModules()` / `verify()`, which are JVM-reflective and so would only
 * ever cover one of the four. Resolving each declaration for real catches the same class of mistake —
 * a definition whose dependency nobody provides — and catches it everywhere.
 *
 * `platformModule` is deliberately *not* loaded here: the Android actual needs a real `Context`, so it
 * gets its own per-target test.
 */
class AppGraphTest {

    private val fakeStore = module {
        single<KeyValueStore> {
            object : KeyValueStore {
                override suspend fun read(key: String): String? = null
                override suspend fun write(key: String, value: String) = Unit
                override suspend fun remove(key: String) = Unit
            }
        }
    }

    private fun koin(): Koin = startKoin { modules(appModule, fakeStore) }.koin

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun every_declared_dependency_resolves() {
        val koin = koin()

        assertNotNull(koin.get<Clock>())
        assertNotNull(koin.get<Json>())
        assertNotNull(koin.get<HttpClientFactory>())
        assertNotNull(koin.get<JsonTokenStore>())
        assertNotNull(koin.get<MalSessionRepository>())

        koin.get<MalSessionRepository>().close()
    }

    @Test
    fun the_repository_is_a_singleton() {
        // Two repositories would mean two Ktor AuthTokenHolder caches over one store, and so a
        // refresh race the plugin's own mutex cannot see.
        val koin = koin()

        val first = koin.get<MalSessionRepository>()
        assertSame(first, koin.get<MalSessionRepository>())

        first.close()
    }

    @Test
    fun the_repository_starts_from_the_build_time_client_id() {
        // The graph is the only place that may name the build-time default, and it must not hardcode a
        // literal: a build configured with `mal.clientId` has to reach the repository, and the
        // remembered value then overrides it in `restore()`.
        //
        // Honest about its reach: in a build configured with no Client ID this compares "" to "" and
        // cannot fail. It bites in a build that sets one — `./gradlew :app:shared:jvmTest
        // -Pmal.clientId=...`, or CI with `MAL_CLIENT_ID` set — which is also the only configuration
        // in which the wiring matters.
        val koin = koin()

        val repository = koin.get<MalSessionRepository>()

        assertEquals(MAL_CLIENT_ID, repository.config.value.clientId)
        repository.close()
    }

    @Test
    fun the_token_store_is_a_singleton() {
        val koin = koin()

        assertSame(koin.get<JsonTokenStore>(), koin.get<JsonTokenStore>())

        koin.get<MalSessionRepository>().close()
    }

    @Test
    fun the_json_ignores_unknown_keys() {
        // MAL adds fields. Without this a MAL-side addition turns a stored Session into a corrupt
        // blob, which reads as "signed out" on the next launch.
        val koin = koin()

        val user = koin.get<Json>()
            .decodeFromString<MalUser>("""{"id":1,"name":"x","a_field_mal_added_later":true}""")

        assertEquals("x", user.name)
        koin.get<MalSessionRepository>().close()
    }
}
