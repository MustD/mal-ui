package io.challenge_workshop.mal_ui.animelist

import io.challenge_workshop.mal_ui.session.JsonTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * How the user last chose to read their Anime List, as a `StateFlow` and a way to change it.
 *
 * In `:core` because the Layout is an input to the Screen State, and the value that combines those
 * inputs cannot depend on anything in `:app:shared` — the same reason [AnimeListRepository] lives
 * here. It also means the ordering below is covered by
 * `./gradlew :core:allTests` on all four Targets rather than only wherever a Compose test can run.
 *
 * It owns exactly the piece of state that is *not* the pager's. A Layout change re-draws the entries
 * already loaded and issues no request, which is why the pager has never heard of it.
 *
 * The stored record has not moved: it is still `JsonTokenStore`'s `mal.layout.v1`, still one of the
 * two preferences `clear()` deliberately keeps. What changed is who reads it.
 *
 * @param scope the scope the startup read and every write run in. Process-scoped, like the store it
 * reads: this outlives any one composition, and a read cancelled by a screen going away would leave
 * the shipped default on screen for the rest of the launch. It is [kotlinx.coroutines.Dispatchers.Main]`.immediate`
 * in the app, which is what confines [chosen] to one thread — see it.
 */
class LayoutPreference(
    private val store: JsonTokenStore,
    private val scope: CoroutineScope,
) {
    private val _value = MutableStateFlow(AnimeListLayout.Cards)

    /**
     * The Layout, as the user last left it.
     *
     * Starts at [AnimeListLayout.Cards] and is replaced by the stored record as soon as the read
     * lands. That ordering is not a race the user can see: this is a process-scoped singleton built
     * at the root of the graph, so the read starts while `SessionState.Restoring` is still the screen
     * — the same store is being read for the Session at that moment — and the signed-in screen it
     * applies to does not exist yet. A default that were *not* the shipped one would be visible; this
     * one is the frame the screen would have drawn anyway.
     *
     * **Not durable on the web Targets.** `KeyValueStore`'s browser actual is `sessionStorage`, which
     * is per-tab and goes when the tab does — see
     * `docs/adr/0001-refresh-token-in-web-session-storage.md`. So this survives a reload but not a
     * closed tab there, and survives everything on jvm and android. Deliberately not split: one
     * store, one record, and a preference that lived somewhere the Session does not would be a second
     * persistence rule to keep in step across four Targets.
     */
    val value: StateFlow<AnimeListLayout> = _value.asStateFlow()

    /**
     * Whether the user has picked a Layout, which is what the startup read must not overwrite.
     *
     * The read is a suspending store call and the toggle is a tap, so on a cold start with a slow
     * first read the tap can land first — and an unguarded `_value.value = store.readLayout()` would
     * then put the *stored* Layout back on screen a moment after the user chose another one, leaving
     * the screen and the store disagreeing until the next launch. Plain `Boolean` rather than
     * anything atomic: both sides of it run on the main dispatcher — the startup read on the scope
     * this was built with, and [choose] in the click handler that calls it.
     */
    private var chosen = false

    init {
        scope.launch {
            val stored = store.readLayout()
            if (!chosen) _value.value = stored
        }
    }

    /**
     * Switches the Layout and remembers the choice.
     *
     * The switch happens here, inside the click handler, and the write is launched behind it into
     * this preference's own scope — so a Layout switch needs no ViewModel and no coroutine of the
     * caller's. Persisting first would put a filesystem or a `sessionStorage` round trip between the
     * tap and the redraw for a preference, and a write that failed would leave the user looking at a
     * Layout they did not pick: the failure is worth less than the frame.
     *
     * **Every call writes, including one that picks the Layout already on screen.** Skipping that
     * write looks free and is not: before the startup read lands, the Layout on screen is the shipped
     * default rather than the stored one, so a user tapping "Cards" over a stored "List" is making a
     * choice that changes nothing visible and everything stored. Dropping it would leave the screen
     * and the store disagreeing, and the next launch would undo the tap. One small idempotent write
     * per tap is the cheaper half of that trade.
     */
    fun choose(layout: AnimeListLayout) {
        // Before anything else, so a tap that changes nothing on screen still counts as a choice and
        // still stops a slow startup read replacing it.
        chosen = true
        _value.value = layout
        scope.launch { store.writeLayout(layout) }
    }
}
