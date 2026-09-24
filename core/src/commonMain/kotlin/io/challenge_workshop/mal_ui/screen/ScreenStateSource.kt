package io.challenge_workshop.mal_ui.screen

import io.challenge_workshop.mal_ui.animelist.AnimeListLayout
import io.challenge_workshop.mal_ui.animelist.AnimeListState
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.mal.MalEndpoints
import io.challenge_workshop.mal_ui.mal.platformMalEndpoints
import io.challenge_workshop.mal_ui.session.SessionDiagnostics
import io.challenge_workshop.mal_ui.session.authorizationUrlFor
import io.challenge_workshop.mal_ui.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The one place a [ScreenState] is decided: six flows in, one flow out.
 *
 * No Compose and no Koin, so `./gradlew :core:allTests` covers the mapping on all four Targets and
 * the alternative — the same `when` written inside a composable — cannot be tested at all without a
 * rendering harness per Target.
 *
 * **Six inputs is the known cost.** The *caller* sees one flow; this constructor is wide. It is
 * defensible because each input has exactly one owner and the combine is total, but it is the thing a
 * review will push on, and the answer is not "it is fine" — it is that the alternative puts the
 * mapping back in a composable. See `docs/adr/0004-screen-state-in-core.md`.
 *
 * Two of those inputs are deliberately *not* the objects that own them:
 *  - `animeList` is `AnimeListRepository.state` rather than the repository, which also holds the
 *    operations — and those are the actions records' business, not a value's. Loaded pages not
 *    outliving a Session is the repository's rule, not this source's.
 *  - `layout` is `LayoutPreference.value`, and a Layout change issues no request, so the list has
 *    never heard of it.
 *
 * The six flows are the whole constructor. Where this build sends MAL traffic is *not* a seventh
 * parameter: [platformMalEndpoints] is an `expect fun` and therefore already this Target's answer, and
 * injecting it would be a seam with one production implementation — the same objection that kept a
 * `(PendingAuthorization) -> String` adapter out. A test asserts it against `platformMalEndpoints()`
 * on whichever of the four Targets it is running on, which is a stronger claim than a pinned value.
 *
 * @param scope where the combine runs. `SharingStarted.Eagerly`, because [state] is read by a
 * composable that must have an answer before it first draws — and [state]`.value` is right even
 * before that scope has dispatched anything, since the initial value is the same mapping applied to
 * the six current values.
 */
class ScreenStateSource(
    session: StateFlow<SessionState>,
    config: StateFlow<MalAuthConfig>,
    animeList: StateFlow<AnimeListState>,
    layout: StateFlow<AnimeListLayout>,
    form: StateFlow<SignInForm>,
    diagnostics: StateFlow<SessionDiagnostics?>,
    scope: CoroutineScope,
) {
    private val endpoints: MalEndpoints = platformMalEndpoints()

    val state: StateFlow<ScreenState> =
        // Six flows through the *five*-argument overload, with the two the ViewModel owns paired
        // first. `combine` is only typed up to five: the six-argument form hands the lambda an
        // `Array<Any?>` to index and cast, which compiles just as happily when two inputs of the
        // same type are swapped. This keeps every input checked by the compiler.
        combine(
            session,
            config,
            animeList,
            layout,
            combine(form, diagnostics) { form, diagnostics -> form to diagnostics },
        ) { session, config, animeList, layout, (form, diagnostics) ->
            screenState(session, config, animeList, layout, form, diagnostics)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = screenState(
                session = session.value,
                config = config.value,
                animeList = animeList.value,
                layout = layout.value,
                form = form.value,
                diagnostics = diagnostics.value,
            ),
        )

    /**
     * The mapping itself, total over [SessionState] by the compiler's own exhaustiveness check.
     *
     * Nothing here can throw. A combine that threw would take the collecting scope with it and leave
     * the app on whichever frame it last drew, which is the failure mode a screen-state seam exists to
     * make impossible — so the authorization URL is rebuilt through
     * [io.challenge_workshop.mal_ui.session.authorizationUrlFor], which has no `require` in it, rather
     * than through the minting path that does.
     */
    private fun screenState(
        session: SessionState,
        config: MalAuthConfig,
        animeList: AnimeListState,
        layout: AnimeListLayout,
        form: SignInForm,
        diagnostics: SessionDiagnostics?,
    ): ScreenState {
        val routing = MalRouting(endpoints = endpoints, redirectUri = config.redirectUri)
        return when (session) {
            SessionState.Restoring -> ScreenState.Restoring

            is SessionState.SignedOut -> ScreenState.SignedOut(
                explanation = explain(session.reason),
                error = session.error,
                form = form,
                routing = routing,
            )

            is SessionState.Authorizing -> ScreenState.Authorizing(
                authorizationUrl = authorizationUrlFor(config, session.pending),
                form = form,
            )

            is SessionState.SignedIn -> ScreenState.SignedIn(
                user = session.user,
                refreshing = session.refreshing,
                list = animeList,
                layout = layout,
                busy = form.busy,
                error = form.error,
                diagnostics = diagnostics,
                routing = routing,
            )
        }
    }
}
