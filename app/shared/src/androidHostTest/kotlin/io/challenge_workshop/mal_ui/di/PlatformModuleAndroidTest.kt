package io.challenge_workshop.mal_ui.di

import io.challenge_workshop.mal_ui.auth.StartupRedirect
import io.challenge_workshop.mal_ui.session.AndroidKeyValueStore
import io.challenge_workshop.mal_ui.session.KeyValueStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

@RunWith(RobolectricTestRunner::class)
class PlatformModuleAndroidTest {

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun initKoin_with_an_android_context_wires_shared_preferences() {
        val koin = initKoin { androidContext(RuntimeEnvironment.getApplication()) }.koin

        assertIs<AndroidKeyValueStore>(koin.get<KeyValueStore>())
        // A redirect reaches Android as an Intent on a running Activity, never as a launch this
        // layer can read — but the binding still has to exist or the ViewModel does not resolve.
        assertSame(StartupRedirect.None, koin.get<StartupRedirect>())

        koin.get<MalSessionRepository>().close()
    }

    @Test
    fun initKoin_without_a_context_fails_with_an_explanation_rather_than_an_npe() {
        // The ordering hazard the ticket names: Koin started from anywhere but Application.onCreate
        // leaves the store with no Context. Failing loudly at resolution beats crashing at the first
        // token read, hours later and nowhere near the cause.
        val koin = initKoin().koin

        try {
            koin.get<KeyValueStore>()
            fail("resolving the store without an Android Context should not have succeeded")
        } catch (e: Exception) {
            val message = e.message.orEmpty() + (e.cause?.message ?: "")
            assertTrue("androidContext" in message, "unhelpful failure: $message")
        }
    }
}
