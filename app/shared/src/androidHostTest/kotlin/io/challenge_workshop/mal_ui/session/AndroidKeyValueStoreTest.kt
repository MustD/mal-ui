package io.challenge_workshop.mal_ui.session

import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real `SharedPreferences`, on the host, via Robolectric. */
@RunWith(RobolectricTestRunner::class)
class AndroidKeyValueStoreTest {

    private fun store(namespace: String = "contract-test") =
        AndroidKeyValueStore(RuntimeEnvironment.getApplication(), namespace)

    @Test
    fun satisfies_the_key_value_store_contract() = runTest {
        assertKeyValueStoreRoundTrip(store())
    }

    @Test
    fun a_value_survives_a_new_store_over_the_same_namespace() = runTest {
        store("survives").write("mal.session.v1", """{"tokens":1}""")

        assertEquals("""{"tokens":1}""", store("survives").read("mal.session.v1"))
    }

    @Test
    fun namespaces_do_not_see_each_others_keys() = runTest {
        store("ns-a").write("shared", "a")
        store("ns-b").write("shared", "b")

        assertEquals("a", store("ns-a").read("shared"))
        assertEquals("b", store("ns-b").read("shared"))
    }
}
