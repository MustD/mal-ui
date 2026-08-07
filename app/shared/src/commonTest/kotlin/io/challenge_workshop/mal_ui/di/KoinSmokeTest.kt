package io.challenge_workshop.mal_ui.di

import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Proves the Koin *runtime* works on all four targets, not just that it compiles.
 *
 * `koin-compose-viewmodel:4.1.0` was built with Kotlin 2.1.20 while this repo is on 2.4.10;
 * a stale wasmJs klib is the specific risk. Running under `jvmTest`, `testAndroidHostTest`,
 * `jsTest` and `wasmJsTest` is what makes that risk visible rather than latent.
 */
class KoinSmokeTest {

    private data class Marker(val value: String)

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun resolves_a_binding_from_a_started_koin() {
        val koin = startKoin {
            modules(module { single { Marker("resolved") } })
        }.koin

        assertEquals("resolved", koin.get<Marker>().value)
    }

    @Test
    fun a_single_is_the_same_instance_every_time() {
        val koin = startKoin {
            modules(module { single { Marker("once") } })
        }.koin

        assertSame(koin.get<Marker>(), koin.get<Marker>())
    }
}
