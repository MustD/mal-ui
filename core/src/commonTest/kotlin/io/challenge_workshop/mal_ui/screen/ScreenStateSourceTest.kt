package io.challenge_workshop.mal_ui.screen

import io.challenge_workshop.mal_ui.animelist.AnimeListContent
import io.challenge_workshop.mal_ui.animelist.AnimeListLayout
import io.challenge_workshop.mal_ui.animelist.AnimeListState
import io.challenge_workshop.mal_ui.animelist.AnimeListTail
import io.challenge_workshop.mal_ui.animelist.WatchStatus
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.mal.MalEndpoints
import io.challenge_workshop.mal_ui.mal.MalUser
import io.challenge_workshop.mal_ui.mal.platformMalEndpoints
import io.challenge_workshop.mal_ui.session.PendingAuthorization
import io.challenge_workshop.mal_ui.session.SessionDiagnostics
import io.challenge_workshop.mal_ui.session.SessionState
import io.challenge_workshop.mal_ui.session.SignedOutReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The mapping from six flows to one [ScreenState], on all four Targets.
 *
 * Driven by six [MutableStateFlow]s and no fakes at all: there is no repository here, no store, no
 * HTTP engine and no port to bind, which is the entire point of the seam. Everything a screen can be
 * is a literal, and every assertion below used to need a rendered Compose tree on jvm.
 *
 * [Dispatchers.Unconfined] rather than a test dispatcher, so an assignment to one of the six inputs
 * has reached [ScreenStateSource.state] by the time the next line runs. The combine is pure, so
 * running it inline on the caller's thread is exactly what it does in the app on
 * `Dispatchers.Main.immediate`.
 */
class ScreenStateSourceTest {

    private val session = MutableStateFlow<SessionState>(SessionState.Restoring)
    private val config = MutableStateFlow(MalAuthConfig(clientId = "a-client-id", redirectUri = REDIRECT_URI))
    private val animeList = MutableStateFlow(AnimeListState())
    private val layout = MutableStateFlow(AnimeListLayout.Cards)
    private val form = MutableStateFlow(SignInForm())
    private val diagnostics = MutableStateFlow<SessionDiagnostics?>(null)

    private val source = ScreenStateSource(
        session = session,
        config = config,
        animeList = animeList,
        layout = layout,
        form = form,
        diagnostics = diagnostics,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private val state: ScreenState get() = source.state.value

    /**
     * Every `SessionState` subtype maps to a [ScreenState], and no two map to the same one.
     *
     * The cases are [SESSION_STATE_CASES], and `ScreenStateCoverageTest` in `jvmTest` is what holds
     * that list to the sealed interface — `KClass.sealedSubclasses` exists only on jvm, so the
     * *enumeration* is one Target's and the *mapping* asserted here is all four's.
     */
    @Test
    fun every_session_state_produces_a_screen_state() {
        val produced = SESSION_STATE_CASES.associate { given ->
            session.value = given
            given::class.simpleName to state::class.simpleName
        }

        assertEquals(
            SESSION_STATE_CASES.size,
            produced.values.toSet().size,
            "Two SessionStates produced the same ScreenState: $produced",
        )
    }

    /**
     * The four explanations differ from each other — the whole reason [SignedOutReason] is carried
     * rather than collapsed into a bare "signed out" — and the expired one says so.
     *
     * This is the assertion that could not run off jvm before: it was four Compose renderings and a
     * `testTag`, and it is a `Map` here.
     */
    @Test
    fun each_signed_out_reason_explains_itself_differently() {
        val explanations = SignedOutReason.entries.associateWith { reason ->
            session.value = SessionState.SignedOut(reason)
            assertIs<ScreenState.SignedOut>(state).explanation
        }

        assertEquals(
            SignedOutReason.entries.size,
            explanations.values.toSet().size,
            "Two reasons share the same copy, which is the bare 'signed out' this enum exists to " +
                "avoid: $explanations",
        )
        assertTrue(
            "expired" in explanations.getValue(SignedOutReason.RefreshRejected).lowercase(),
            explanations.getValue(SignedOutReason.RefreshRejected),
        )
    }

    /**
     * The authorization URL Paste-the-code offers, rebuilt from the Pending Authorization rather than
     * stored — which works at all only because MAL supports `plain` PKCE, so the challenge *is* the
     * verifier.
     *
     * The verifier being visible in the string is asserted on purpose: it is what makes this URL a
     * thing that must never be logged, and a future change that started hashing the challenge would
     * break the sign-in and this line together.
     */
    @Test
    fun authorizing_carries_the_url_paste_the_code_offers() {
        session.value = SessionState.Authorizing(pendingAuthorization())

        val url = assertIs<ScreenState.Authorizing>(state).authorizationUrl

        assertTrue(url.startsWith(MalAuthConfig.DEFAULT_AUTHORIZE_ENDPOINT), url)
        for (expected in listOf(
            "client_id=a-client-id",
            "code_challenge=a-verifier",
            "code_challenge_method=plain",
            "state=a-state",
        )) {
            assertTrue(expected in url, "missing `$expected` in $url")
        }
        assertTrue("oauth%2Fcallback" in url || "oauth/callback" in url, url)
    }

    /**
     * The Pending Authorization's own Client ID wins over whatever is in the config now.
     *
     * A user who mistyped a Client ID, went to MAL and came back to correct it would otherwise be
     * offered a URL for the *new* one — which MAL would reject against a code minted under the old.
     */
    @Test
    fun the_authorization_url_uses_the_client_id_the_sign_in_started_with() {
        session.value = SessionState.Authorizing(pendingAuthorization())
        config.value = config.value.copy(clientId = "a-client-id-typed-later")

        assertTrue(
            "client_id=a-client-id&" in assertIs<ScreenState.Authorizing>(state).authorizationUrl,
        )
    }

    /**
     * Which Anime List screen the signed-in screen shows is entirely the list's: every variant reaches
     * the screen verbatim, with the query beside it. *Deciding* the variant is the pager's, and
     * `AnimeListPagerTest` covers that; what is asserted here is that nothing on the way re-decides it.
     */
    @Test
    fun every_anime_list_screen_reaches_the_signed_in_screen_as_it_is() {
        session.value = SessionState.SignedIn(MalUser(1, "someone"))
        val variants = listOf(
            AnimeListContent.NotRequested,
            AnimeListContent.FirstPageLoading,
            AnimeListContent.FirstPageFailed("boom"),
            AnimeListContent.Empty,
            AnimeListContent.Entries(emptyList(), AnimeListTail.MoreFailed("boom"), replacing = false),
            AnimeListContent.Entries(emptyList(), AnimeListTail.Idle, replacing = true),
        )

        for (content in variants) {
            // Under a filter, because an empty slice must not read as an empty account: the Watch
            // Status is what separates them, and it travels beside the variant rather than inferred.
            val list = AnimeListState(content = content, watchStatus = WatchStatus.OnHold)
            animeList.value = list
            assertEquals(list, signedIn().list, "for $content")
        }
    }

    /** A Layout change reaches the screen and touches nothing else. */
    @Test
    fun the_layout_reaches_the_signed_in_screen_and_changes_nothing_else() {
        session.value = SessionState.SignedIn(MalUser(1, "someone"))
        animeList.value = AnimeListState(content = AnimeListContent.Empty)
        val before = signedIn()

        layout.value = AnimeListLayout.List

        assertEquals(AnimeListLayout.List, signedIn().layout)
        assertEquals(before, signedIn().copy(layout = AnimeListLayout.Cards))
    }

    /**
     * The refresh flag is the Session's and does not unmount the screen: the list survives it, which
     * is what "a refresh must not blank the signed-in screen" means as a value.
     */
    @Test
    fun a_session_refresh_keeps_the_anime_list() {
        animeList.value = AnimeListState(content = AnimeListContent.Empty, revision = 3)
        session.value = SessionState.SignedIn(MalUser(1, "someone"), refreshing = true)

        assertTrue(signedIn().refreshing)
        assertEquals(3, signedIn().list.revision)
    }

    /**
     * Diagnostics stay out of everything but the signed-in screen, and are absent until something
     * asks for them — the dialog is the only reader, and it only opens deliberately.
     */
    @Test
    fun diagnostics_reach_only_the_signed_in_screen() {
        diagnostics.value = SessionDiagnostics(
            obtainedAtEpochMs = 1,
            ageMillis = 2,
            accessTokenLength = 3,
            hasRefreshToken = true,
            accessTokenIsDeliberatelyInvalid = false,
        )

        session.value = SessionState.SignedOut(SignedOutReason.NeverSignedIn)
        assertIs<ScreenState.SignedOut>(state)

        session.value = SessionState.SignedIn(MalUser(1, "someone"))
        assertEquals(3, signedIn().diagnostics?.accessTokenLength)
    }

    /**
     * `busy` and `error` are read by three screens, which is why they are on the form and not on one
     * of them.
     */
    @Test
    fun the_form_reaches_every_screen_that_reads_it() {
        form.value = SignInForm(clientId = "typed", busy = true, error = "boom")

        session.value = SessionState.SignedOut(SignedOutReason.NeverSignedIn)
        assertEquals(form.value, assertIs<ScreenState.SignedOut>(state).form)

        session.value = SessionState.Authorizing(pendingAuthorization())
        assertEquals(form.value, assertIs<ScreenState.Authorizing>(state).form)

        session.value = SessionState.SignedIn(MalUser(1, "someone"))
        assertEquals(true to "boom", signedIn().let { it.busy to it.error })
    }

    /**
     * The two things a form can be asked to do, and what blocks each.
     *
     * Both are derived rather than stored because a screen that could disagree with the form about
     * whether its own button is enabled is the bug this replaces.
     */
    @Test
    fun a_blank_client_id_blocks_signing_in_and_a_blank_paste_blocks_completing_it() {
        assertEquals(false, SignInForm(clientId = "  ").canStart)
        assertEquals(false, SignInForm(clientId = "a", busy = true).canStart)
        assertEquals(true, SignInForm(clientId = "a").canStart)

        assertEquals(false, SignInForm(pastedRedirect = " ").canComplete)
        assertEquals(false, SignInForm(pastedRedirect = "x", busy = true).canComplete)
        assertEquals(true, SignInForm(pastedRedirect = "x").canComplete)
    }

    /**
     * Where this build sends MAL traffic, which the sign-in screen says up front and the debug panel
     * repeats: a browser with no relay running fails at its first request with a bare "Failed to
     * fetch", and nothing else in the app can tell the user why.
     *
     * Asserted against [platformMalEndpoints] rather than against a value pinned by the test, which is
     * what makes this worth running on four Targets: it says the browsers are relayed and jvm and
     * android are not, which is the actual claim. A pinned value would have said the same thing four
     * times.
     */
    @Test
    fun the_routing_is_this_targets_own_and_says_whether_it_goes_through_the_relay() {
        session.value = SessionState.SignedOut(SignedOutReason.NeverSignedIn)
        val routing = assertIs<ScreenState.SignedOut>(state).routing

        assertEquals(platformMalEndpoints(), routing.endpoints)
        assertEquals(REDIRECT_URI, routing.redirectUri)
        assertEquals(
            !platformMalEndpoints().tokenEndpoint.startsWith(MalAuthConfig.DEFAULT_TOKEN_ENDPOINT),
            routing.usesRelay,
            "a Target that does not call MAL's own token endpoint is going through the relay",
        )
    }

    /**
     * The relay origin the sign-in screen's warning turns on, from both sides, on whichever Target
     * this is — so the branch is covered even where [platformMalEndpoints] only ever gives one answer.
     */
    @Test
    fun a_relayed_token_endpoint_is_what_makes_a_build_relayed() {
        fun routingFor(tokenEndpoint: String) = MalRouting(
            endpoints = MalEndpoints(tokenEndpoint = tokenEndpoint, apiBaseUrl = "unused"),
            redirectUri = REDIRECT_URI,
        )

        assertEquals(false, routingFor(MalAuthConfig.DEFAULT_TOKEN_ENDPOINT).usesRelay)
        assertEquals(true, routingFor("https://mal-ui.localhost/mal/oauth2/token").usesRelay)
    }

    private fun signedIn(): ScreenState.SignedIn = assertIs<ScreenState.SignedIn>(state)
}

/** Where this Target's tests pretend the desktop listener is. Any registered URI would do. */
internal const val REDIRECT_URI: String = "http://127.0.0.1:18040/oauth/callback"

/**
 * One [SessionState] per subtype, shared between the mapping test and the jvm-only coverage test that
 * holds this list to `SessionState::class.sealedSubclasses`.
 *
 * A `val` outside both, because the thing worth guarding is that the two never diverge: a list the
 * coverage test could not see would be a list nothing holds to the sealed interface.
 */
internal val SESSION_STATE_CASES: List<SessionState> = listOf(
    SessionState.Restoring,
    SessionState.SignedOut(SignedOutReason.NeverSignedIn),
    SessionState.Authorizing(pendingAuthorization()),
    SessionState.SignedIn(MalUser(1, "someone")),
)

internal fun pendingAuthorization() = PendingAuthorization(
    codeVerifier = "a-verifier",
    state = "a-state",
    redirectUri = REDIRECT_URI,
    clientId = "a-client-id",
    startedAtEpochMs = 0L,
)
