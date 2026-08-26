@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui.animelist

import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.KeyValueStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Layout, which is the only state this ViewModel owns rather than reads off [AnimeListPager].
 *
 * Everything about paging is tested against the pager itself in `:core`; what is left here is the
 * one thing the pager has never heard of. Here rather than in `SessionRouteTest` because what is
 * worth pinning is an *ordering* — a tap against a store read still in flight — and a Compose test
 * cannot hold the two apart: `runComposeUiTest` brings its own scheduler, so a coroutine resumed
 * inside it lands whenever that scheduler gets to it and the race the test means to set up never
 * happens. A [StandardTestDispatcher] and [runCurrent] make the ordering the test's to choose. It
 * also means this runs on all four Targets rather than only on jvm.
 */
class AnimeListViewModelTest {

    private lateinit var repository: MalSessionRepository

    @BeforeTest
    fun setUp() {
        // Standard rather than Unconfined: this test is about *when* a coroutine runs, and an
        // Unconfined dispatcher answers that question itself by running everything eagerly. `runTest`
        // adopts this dispatcher's scheduler, so `advanceUntilIdle` drives `viewModelScope` too.
        Dispatchers.setMain(StandardTestDispatcher())
        repository = MalSessionRepository(
            JsonTokenStore(GatedKeyValueStore()),
            initialConfig = MalAuthConfig(clientId = "a-client-id"),
        )
    }

    @AfterTest
    fun tearDown() {
        repository.close()
        Dispatchers.resetMain()
    }

    @Test
    fun the_stored_layout_replaces_the_default_once_it_is_read() = runTest {
        val kv = GatedKeyValueStore(storedLayout = AnimeListLayout.List)
        val viewModel = AnimeListViewModel(repository, JsonTokenStore(kv))
        runCurrent()

        // The shipped default is what the screen draws while the read is in flight, which is the
        // frame it would have drawn anyway — see `AnimeListViewModel.layout`.
        assertEquals(AnimeListLayout.Cards, viewModel.layout.value, "before the read lands")

        kv.releaseReads()
        advanceUntilIdle()

        assertEquals(AnimeListLayout.List, viewModel.layout.value)
    }

    /**
     * A tap that beats the startup read wins, and the read does not undo it.
     *
     * The read is a suspending store call and the toggle is a tap, so on a cold start with a slow
     * first read the two can land in that order. An unguarded `_layout.value = store.readLayout()`
     * would put the *stored* Layout back a moment after the user chose the other one, leaving the
     * screen showing a Layout nobody picked.
     *
     * The tap's own write is held shut for the length of the test, and that is what makes this a
     * test rather than a coin toss: were it allowed to land first it would overwrite the very record
     * the read is about to return, and an unguarded read would then put back the value the user
     * chose and look correct. Both orderings are real; only one of them is a bug, and this is it.
     */
    @Test
    fun a_layout_chosen_before_the_stored_one_is_read_is_not_overwritten() = runTest {
        val kv = GatedKeyValueStore(storedLayout = AnimeListLayout.List)
        val viewModel = AnimeListViewModel(repository, JsonTokenStore(kv))
        runCurrent()

        viewModel.setLayout(AnimeListLayout.Cards)
        assertEquals(AnimeListLayout.Cards, viewModel.layout.value, "the tap must be immediate")

        kv.releaseReads()
        advanceUntilIdle()

        assertEquals(
            AnimeListLayout.Cards,
            viewModel.layout.value,
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
     * "Cards" over a stored "List" — is a real choice that changes nothing visible. A `setLayout`
     * that returned early on `_layout.value == layout` would drop it, and the next launch would
     * undo the tap.
     */
    @Test
    fun picking_the_layout_already_on_screen_still_reaches_the_store() = runTest {
        val kv = GatedKeyValueStore(storedLayout = AnimeListLayout.List)
        val viewModel = AnimeListViewModel(repository, JsonTokenStore(kv))
        runCurrent()

        // Cards is what the screen shows — the default, the read has not landed — and Cards is what
        // the user picks.
        viewModel.setLayout(AnimeListLayout.Cards)
        kv.releaseReads()
        kv.releaseWrites()
        advanceUntilIdle()

        assertEquals(AnimeListLayout.Cards, viewModel.layout.value)
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
