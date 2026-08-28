@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui.animelist

import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.KeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Layout preference: the one piece of Anime List state that is not [AnimeListPager]'s.
 *
 * What is worth pinning here is an *ordering* — a tap against a store read still in flight — and a
 * Compose test cannot hold the two apart: `runComposeUiTest` brings its own scheduler, so a coroutine
 * resumed inside it lands whenever that scheduler gets to it and the race the test means to set up
 * never happens. `runTest`'s [StandardTestDispatcher][kotlinx.coroutines.test.StandardTestDispatcher]
 * and [runCurrent] make the ordering the test's to choose. Being in `:core` it also runs on all four
 * Targets under `./gradlew :core:allTests`.
 *
 * Every coroutine here is a child of the test's own scope rather than of `backgroundScope`:
 * [advanceUntilIdle] does not run background work, so a startup read parked on a gate would still be
 * parked after the gate opened and every assertion below would pass for the wrong reason. Nothing
 * here can hang `runTest` for it — each test releases every gate it closes.
 */
class LayoutPreferenceTest {

    @Test
    fun the_stored_layout_replaces_the_default_once_it_is_read() = runTest {
        val kv = GatedKeyValueStore(storedLayout = AnimeListLayout.List)
        val preference = LayoutPreference(JsonTokenStore(kv), this)
        runCurrent()

        // The shipped default is what the screen draws while the read is in flight, which is the
        // frame it would have drawn anyway — see `LayoutPreference.value`.
        assertEquals(AnimeListLayout.Cards, preference.value.value, "before the read lands")

        kv.releaseReads()
        advanceUntilIdle()

        assertEquals(AnimeListLayout.List, preference.value.value)
    }

    /**
     * A tap that beats the startup read wins, and the read does not undo it.
     *
     * The read is a suspending store call and the toggle is a tap, so on a cold start with a slow
     * first read the two can land in that order. An unguarded `_value.value = store.readLayout()`
     * would put the *stored* Layout back a moment after the user chose the other one, leaving the
     * screen showing a Layout nobody picked.
     *
     * The tap's own write is held shut for the length of the test, and that is what makes this a test
     * rather than a coin toss: were it allowed to land first it would overwrite the very record the
     * read is about to return, and an unguarded read would then put back the value the user chose and
     * look correct. Both orderings are real; only one of them is a bug, and this is it.
     */
    @Test
    fun a_layout_chosen_before_the_stored_one_is_read_is_not_overwritten() = runTest {
        val kv = GatedKeyValueStore(storedLayout = AnimeListLayout.List)
        val preference = LayoutPreference(JsonTokenStore(kv), this)
        runCurrent()

        // A tap is a `viewModelScope.launch` on `Dispatchers.Main.immediate`, which runs the body up
        // to its first suspension point without dispatching. `runCurrent` is what stands in for that
        // here: the value has to be on screen before the write it is waiting on.
        launch { preference.choose(AnimeListLayout.Cards) }
        runCurrent()
        assertEquals(AnimeListLayout.Cards, preference.value.value, "the tap must be immediate")

        kv.releaseReads()
        advanceUntilIdle()

        assertEquals(
            AnimeListLayout.Cards,
            preference.value.value,
            "the startup read overwrote a Layout the user had already chosen",
        )

        kv.releaseWrites()
        advanceUntilIdle()
        assertEquals(
            AnimeListLayout.Cards,
            JsonTokenStore(kv).readLayout(),
            "and the user's choice is what reached the store",
        )
    }

    /**
     * Picking the Layout that is already on screen still writes it.
     *
     * It looks like a write worth skipping and is not: until the startup read lands, the Layout on
     * screen is the shipped default rather than the stored one, so this exact gesture — tapping
     * "Cards" over a stored "List" — is a real choice that changes nothing visible. A [choose] that
     * returned early on `value.value == layout` would drop it, and the next launch would undo the tap.
     */
    @Test
    fun picking_the_layout_already_on_screen_still_reaches_the_store() = runTest {
        val kv = GatedKeyValueStore(storedLayout = AnimeListLayout.List)
        val preference = LayoutPreference(JsonTokenStore(kv), this)
        runCurrent()

        // Cards is what the screen shows — the default, the read has not landed — and Cards is what
        // the user picks.
        launch { preference.choose(AnimeListLayout.Cards) }
        kv.releaseReads()
        kv.releaseWrites()
        advanceUntilIdle()

        assertEquals(AnimeListLayout.Cards, preference.value.value)
        assertEquals(
            AnimeListLayout.Cards,
            JsonTokenStore(kv).readLayout(),
            "a tap that changed nothing on screen still changed the stored Layout",
        )
    }
}

/**
 * A [KeyValueStore] whose reads and writes are held until the test lets them through.
 *
 * The two gates are the whole point: they are what put a tap and a startup read in a chosen order
 * rather than in whichever order the dispatcher picks. Two gates and not one, because the tap's own
 * write is the thing that would otherwise hide the bug — it lands on the very record the read is
 * about to return.
 */
private class GatedKeyValueStore(storedLayout: AnimeListLayout? = null) : KeyValueStore {
    // Seeded directly rather than through `JsonTokenStore.writeLayout`, so a stored record exists
    // without a write having happened — which is exactly the state a fresh launch reads.
    private val entries = mutableMapOf<String, String>().apply {
        storedLayout?.let { put(JsonTokenStore.LAYOUT_KEY, "\"${it.name}\"") }
    }
    private val readGate = CompletableDeferred<Unit>()
    private val writeGate = CompletableDeferred<Unit>()

    fun releaseReads() {
        readGate.complete(Unit)
    }

    fun releaseWrites() {
        writeGate.complete(Unit)
    }

    override suspend fun read(key: String): String? {
        readGate.await()
        return entries[key]
    }

    override suspend fun write(key: String, value: String) {
        writeGate.await()
        entries[key] = value
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }
}
