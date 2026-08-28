@file:OptIn(ExperimentalTestApi::class)

package io.challenge_workshop.mal_ui

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelStore
import io.challenge_workshop.mal_ui.animelist.AiringStatus
import io.challenge_workshop.mal_ui.animelist.AnimeListViewModel
import io.challenge_workshop.mal_ui.auth.SESSION_MENU_BUTTON_TAG
import io.challenge_workshop.mal_ui.animelist.AnimeListEntry
import io.challenge_workshop.mal_ui.animelist.AnimeListLayout
import io.challenge_workshop.mal_ui.animelist.AnimeListSortOrder
import io.challenge_workshop.mal_ui.animelist.AnimeListState
import io.challenge_workshop.mal_ui.animelist.WatchStatus
import io.challenge_workshop.mal_ui.auth.AuthorizingActions
import io.challenge_workshop.mal_ui.auth.DiagnosticsActions
import io.challenge_workshop.mal_ui.auth.ScreenActions
import io.challenge_workshop.mal_ui.auth.SignInActions
import io.challenge_workshop.mal_ui.auth.SignedInActions
import io.challenge_workshop.mal_ui.mal.DESKTOP_REDIRECT_URI
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.mal.MalEndpoints
import io.challenge_workshop.mal_ui.mal.MalUser
import io.challenge_workshop.mal_ui.screen.MalRouting
import io.challenge_workshop.mal_ui.screen.ScreenState
import io.challenge_workshop.mal_ui.screen.SignInForm
import io.challenge_workshop.mal_ui.session.SessionDiagnostics
import io.challenge_workshop.mal_ui.session.SignedOutReason
import io.challenge_workshop.mal_ui.screen.explain

/**
 * Every screen this app has, as a literal.
 *
 * This file is what the Screen State seam bought. Rendering a screen used to mean standing up a
 * `MalSessionRepository`, a `JsonTokenStore`, a fake MAL engine and `Dispatchers.setMain` — and
 * binding port 18040 — per case, which is why the test that did it was the largest file in the
 * repository. A screen takes a value now, so a case is a `copy()`.
 *
 * The one test that still builds the real stack is
 * `SignInScreenTest.signing_in_arms_this_targets_capture_and_still_offers_paste_the_code`, which is
 * about that stack: it is the seam between a click and this Target's Redirect Capture, and there is
 * nothing to assert about it that a literal could stand in for.
 *
 * **What is *not* here is as deliberate.** Nothing in this file decides anything. Which screen a
 * Session produces, what a filter or a Sort Order change produces, and what the four Signed Out
 * Reasons say are all `ScreenStateSourceTest`'s, in `:core`, on four Targets. These fixtures only
 * say what a screen is handed.
 */
internal val TEST_ROUTING = MalRouting(
    endpoints = MalEndpoints(
        tokenEndpoint = MalAuthConfig.DEFAULT_TOKEN_ENDPOINT,
        apiBaseUrl = MalAuthConfig.DEFAULT_API_BASE_URL,
    ),
    redirectUri = DESKTOP_REDIRECT_URI,
)

internal fun signedOut(
    reason: SignedOutReason = SignedOutReason.NeverSignedIn,
    error: String? = null,
    form: SignInForm = SignInForm(clientId = "a-client-id"),
) = ScreenState.SignedOut(
    explanation = explain(reason),
    error = error,
    form = form,
    routing = TEST_ROUTING,
)

internal fun authorizing(
    authorizationUrl: String = TEST_AUTHORIZATION_URL,
    form: SignInForm = SignInForm(clientId = "a-client-id"),
) = ScreenState.Authorizing(authorizationUrl = authorizationUrl, form = form)

internal fun signedIn(
    user: MalUser? = MalUser(1, "someone"),
    refreshing: Boolean = false,
    list: AnimeListState = loadedList(),
    layout: AnimeListLayout = AnimeListLayout.Cards,
    busy: Boolean = false,
    error: String? = null,
    diagnostics: SessionDiagnostics? = null,
) = ScreenState.SignedIn(
    user = user,
    refreshing = refreshing,
    list = list,
    layout = layout,
    busy = busy,
    error = error,
    diagnostics = diagnostics,
    routing = TEST_ROUTING,
)

/**
 * A first page that has landed and exhausted the pager, which is the resting state of the signed-in
 * screen.
 *
 * `exhausted` defaults to true because it is load-bearing rather than decoration: an empty first page
 * that still carries a `paging.next` is a hole the pager is paging past, not an empty list, so it is
 * part of the condition both empty states are drawn under.
 */
internal fun loadedList(
    titles: List<String> = FIXTURE_TITLES,
    watchStatus: WatchStatus? = null,
    sortOrder: AnimeListSortOrder = AnimeListSortOrder.LastUpdated,
    exhausted: Boolean = true,
    revision: Int = 0,
    loadingMore: Boolean = false,
    moreError: String? = null,
) = AnimeListState(
    entries = titles.mapIndexed { index, title -> listEntry(id = index + 1L, title = title) },
    watchStatus = watchStatus,
    sortOrder = sortOrder,
    exhausted = exhausted,
    loaded = true,
    revision = revision,
    loadingMore = loadingMore,
    moreError = moreError,
)

/**
 * One List Entry carrying every fact the spec asks it to.
 *
 * No `picture`, deliberately: fetching cover art is Coil's job over a real network, which a unit test
 * must not do. What that leaves on screen is the placeholder path, which is the case the spec cares
 * most about anyway — MAL omits the field for entries whose art it has none of.
 */
internal fun listEntry(id: Long, title: String) = AnimeListEntry(
    animeId = id,
    title = title,
    picture = null,
    totalEpisodes = 26,
    mediaType = "tv",
    airingStatus = AiringStatus.FinishedAiring,
    watchStatus = WatchStatus.Watching,
    score = 8,
    // Varied per entry so "3 / 26" names one card rather than every card. The rest is shared
    // deliberately: a metadata line that were unique per entry would be testing the fixture.
    episodesWatched = ((id.toInt() - 1) % 3) + 3,
    updatedAt = "2026-08-01T12:00:00+00:00",
)

internal val FIXTURE_TITLES: List<String> = listOf("Cowboy Bebop", "Mushishi")

/**
 * Shaped like the real thing so the copy button's assertion is about a URL rather than a word, and
 * carrying a `code_challenge` because under `plain` PKCE the code verifier is inside this string —
 * which is why nothing ever logs it. What goes *into* the URL is `ScreenStateSourceTest`'s.
 */
internal const val TEST_AUTHORIZATION_URL: String =
    "https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=a-client-id" +
        "&code_challenge=a-verifier&code_challenge_method=plain&state=a-state"

/**
 * A [ScreenActions] that writes down what it was asked to do.
 *
 * What a control *causes* is a ViewModel's or the pager's, and both are tested where they live. What
 * is left for a rendering test is that the control the user can see is wired to the right one of
 * them — a Layout toggle bound to `onReload` still renders perfectly.
 */
internal class RecordedActions {
    val calls: MutableList<String> = mutableListOf()
    val clientIds: MutableList<String> = mutableListOf()
    val pastes: MutableList<String> = mutableListOf()
    val watchStatuses: MutableList<WatchStatus?> = mutableListOf()
    val sortOrders: MutableList<AnimeListSortOrder> = mutableListOf()
    val layouts: MutableList<AnimeListLayout> = mutableListOf()

    val actions: ScreenActions = ScreenActions(
        signIn = SignInActions(
            onClientIdChange = { clientIds += it; calls += "clientIdChange" },
            onSignIn = { calls += "signIn" },
        ),
        authorizing = AuthorizingActions(
            onPastedRedirectChange = { pastes += it; calls += "pastedRedirectChange" },
            onCompleteSignIn = { calls += "completeSignIn" },
            onCancelSignIn = { calls += "cancelSignIn" },
        ),
        signedIn = SignedInActions(
            onLoadFirstPage = { calls += "loadFirstPage" },
            onLoadMore = { calls += "loadMore" },
            onRetry = { calls += "retry" },
            onReload = { calls += "reload" },
            onSelectWatchStatus = { watchStatuses += it; calls += "selectWatchStatus" },
            onSelectSortOrder = { sortOrders += it; calls += "selectSortOrder" },
            onSelectLayout = { layouts += it; calls += "selectLayout" },
            onSignOut = { calls += "signOut" },
            diagnostics = DiagnosticsActions(
                onReloadDiagnostics = { calls += "reloadDiagnostics" },
                onRefreshUser = { calls += "refreshUser" },
                onForceExpireAccessToken = { calls += "forceExpireAccessToken" },
            ),
        ),
    )

    /**
     * Everything but the first page load, which every signed-in screen asks for on composition and
     * which is therefore never the thing a click assertion is about.
     */
    fun clicks(): List<String> = calls.filterNot { it == "loadFirstPage" }
}

// --- The rendering harness, shared by the five per-screen tests --------------------------------

/**
 * Generous, because the one test that waits is waiting for a real socket and a real dispatcher rather
 * than for a frame. A wait that is too short reads as a flaky test; one that is too long only costs
 * time when something is already broken.
 */
internal const val WAIT_MS: Long = 5_000

internal val TEST_DIAGNOSTICS = SessionDiagnostics(
    obtainedAtEpochMs = 1_754_000_000_000,
    ageMillis = 60_000,
    accessTokenLength = 512,
    hasRefreshToken = true,
    accessTokenIsDeliberatelyInvalid = true,
)

/** The concatenated text a node draws, for assertions about copy rather than about structure. */
internal fun SemanticsNodeInteraction.textContent(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("") { it.text }
        .orEmpty()

/**
 * Opens the diagnostics dialog the way a person does: the overflow menu, then its last entry.
 *
 * The menu closes on the way, which is what keeps "Session diagnostics" unambiguous — the menu entry
 * and the dialog's own title share those words and are never on screen at once.
 */
internal fun ComposeUiTest.openDiagnostics() {
    onNodeWithTag(SESSION_MENU_BUTTON_TAG).performClick()
    onNodeWithText("Session diagnostics").performClick()
    waitForIdle()
}

/**
 * Ends a hand-built ViewModel's `viewModelScope`, which is otherwise never ended.
 *
 * A ViewModel built by a test rather than by a `ViewModelStore` has nothing that will ever clear it,
 * so its scope outlives the test with a page request still in it — to land after the repository
 * underneath it has been closed and the Main dispatcher reset. Through a `ViewModelStore` because
 * `ViewModel.clear()` is `internal` and this is the public door to it.
 */
internal fun clear(viewModel: AnimeListViewModel) {
    ViewModelStore().apply { put("animeList", viewModel) }.clear()
}

/** Stands in for the desktop clipboard, which a unit test must not actually write to. */
@Suppress("DEPRECATION")
internal class RecordingClipboard : ClipboardManager {
    private var copied: AnnotatedString? = null

    override fun setText(annotatedString: AnnotatedString) {
        copied = annotatedString
    }

    override fun getText(): AnnotatedString? = copied
}

/** Stands in for the platform's browser, which a unit test must not actually start. */
internal class RecordingUriHandler(private val opened: MutableList<String>) : UriHandler {
    override fun openUri(uri: String) {
        opened += uri
    }
}
