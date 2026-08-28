package io.challenge_workshop.mal_ui.screen

import io.challenge_workshop.mal_ui.animelist.AnimeListLayout
import io.challenge_workshop.mal_ui.animelist.AnimeListState
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.mal.MalEndpoints
import io.challenge_workshop.mal_ui.mal.authorizationUrl
import io.challenge_workshop.mal_ui.mal.platformMalEndpoints
import io.challenge_workshop.mal_ui.session.SessionDiagnostics
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
 *  - `animeList` is `AnimeListPager.state` rather than the pager, because the pager stays
 *    ViewModel-scoped: loaded pages must not outlive a sign-out.
 *  - `layout` is `LayoutPreference.value` for the same reason in reverse — the preference is
 *    process-scoped, and a Layout change issues no request, so the pager has never heard of it.
 *
 * @param scope where the combine runs. `SharingStarted.Eagerly`, because [state] is read by a
 * composable that must have an answer before it first draws — and [state]`.value` is right even
 * before that scope has dispatched anything, since the initial value is the same mapping applied to
 * the six current values.
 * @param endpoints where this build sends token and API traffic. A parameter only so a test can pin
 * the relay copy without being on a browser Target; the app takes the default.
 */
class ScreenStateSource(
    session: StateFlow<SessionState>,
    config: StateFlow<MalAuthConfig>,
    animeList: StateFlow<AnimeListState>,
    layout: StateFlow<AnimeListLayout>,
    form: StateFlow<SignInForm>,
    diagnostics: StateFlow<SessionDiagnostics?>,
    scope: CoroutineScope,
    private val endpoints: MalEndpoints = platformMalEndpoints(),
) {
    val state: StateFlow<ScreenState> =
        combine(session, config, animeList, layout, form, diagnostics) { values ->
            @Suppress("UNCHECKED_CAST")
            screenState(
                session = values[0] as SessionState,
                config = values[1] as MalAuthConfig,
                animeList = values[2] as AnimeListState,
                layout = values[3] as AnimeListLayout,
                form = values[4] as SignInForm,
                diagnostics = values[5] as SessionDiagnostics?,
            )
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
     * [io.challenge_workshop.mal_ui.mal.authorizationUrl], which has no `require` in it, rather than
     * through the minting path that does.
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
                // The Pending Authorization's own Client ID and Redirect URI, not the config's: it
                // may have been minted before either was changed, and MAL matches `redirect_uri`
                // byte-exactly against the one the authorization started with.
                authorizationUrl = authorizationUrl(
                    config = config.copy(
                        clientId = session.pending.clientId,
                        redirectUri = session.pending.redirectUri,
                    ),
                    codeVerifier = session.pending.codeVerifier,
                    state = session.pending.state,
                ),
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
