@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui.auth

import androidx.lifecycle.Lifecycle
import io.challenge_workshop.mal_ui.mal.ANDROID_REDIRECT_URI
import io.challenge_workshop.mal_ui.mal.MalAuthConfig
import io.challenge_workshop.mal_ui.session.FakeKeyValueStore
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.PendingAuthorization
import io.challenge_workshop.mal_ui.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A whole Android sign-in, from an `Intent` to a Session.
 *
 * `IntentRedirectChannelTest` covers which redirect the channel accepts; these cover what the app
 * does with the ones it accepts — including the two cases where the answer is "nothing", and where
 * "nothing" has to mean the Pending Authorization is still there afterwards.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidRedirectSignInTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun a_redirect_delivered_while_the_user_is_away_completes_the_login() = androidSignInTest {
        signIn()
        val pending = assertNotNull(store.readPending())

        inbox.deliver(redirectIntent(pending.state))

        assertEquals(SessionState.SignedIn(FAKE_MAL_USER), awaitSettledSession())
        // The other half of the rule below: a redirect that *is* this attempt's spends the record.
        assertNull(store.readPending(), "a completed authorization leaves nothing to resume")
    }

    /**
     * The ticket's rule, and the only thing standing between a hostile Intent and a destroyed
     * verifier: the filter is exported, any app on the device can fire that Intent, and
     * `MalSessionRepository.completeAuthorization` treats a `state` mismatch as fatal — it clears
     * the Pending Authorization. So the mismatch must be refused *before* it reaches the store.
     */
    @Test
    fun a_redirect_from_another_sign_in_never_reaches_the_store() = androidSignInTest {
        signIn()
        val pending = assertNotNull(store.readPending())

        inbox.deliver(redirectIntent("someone-elses-state"))
        settle()

        assertEquals(pending, store.readPending(), "a redirect that is not ours must cost nothing")
        assertTrue(repository.state.value is SessionState.Authorizing, "${repository.state.value}")
        assertEquals(0, exchanges(), "nothing should have been sent to MyAnimeList")
        assertNull(viewModel.error)

        // And the capture is still live, so the real redirect still completes it.
        inbox.deliver(redirectIntent(pending.state))

        assertEquals(SessionState.SignedIn(FAKE_MAL_USER), awaitSettledSession())
    }

    /**
     * An Intent is re-delivered for ordinary reasons — reopening from recents, an Activity
     * recreation, a browser that retries — and an authorization code is single-use, so a second
     * exchange would fail and take the fresh Session down with it.
     */
    @Test
    fun the_same_redirect_twice_completes_exactly_one_exchange() = androidSignInTest {
        signIn()
        val pending = assertNotNull(store.readPending())

        inbox.deliver(redirectIntent(pending.state))
        inbox.deliver(redirectIntent(pending.state))

        assertEquals(SessionState.SignedIn(FAKE_MAL_USER), awaitSettledSession())
        assertEquals(1, exchanges(), "a re-delivered redirect must not be exchanged twice")
        assertNull(viewModel.error)
    }

    /**
     * The ticket's rule, and the reason cancellation may be a heuristic at all: a call, a
     * notification, a biometric prompt or a configuration change each resume the app mid-login and
     * are indistinguishable from backing out. Destroying the code verifier on any of them would
     * break logins that were about to succeed.
     */
    @Test
    fun suspected_cancellation_leaves_the_pending_authorization_in_the_store() = androidSignInTest {
        signIn()
        val pending = assertNotNull(store.readPending())

        lifecycle.value = Lifecycle.State.CREATED
        settle()
        lifecycle.value = Lifecycle.State.RESUMED
        settle()

        assertEquals(pending, store.readPending(), "a suspected cancellation must keep the verifier")
        assertTrue(repository.state.value is SessionState.SignedOut, "${repository.state.value}")
        assertTrue(viewModel.canStart, "and the sign-in button has to come back")
    }

    /**
     * Being parked behind a browser on a low-RAM device is an ordinary way to be killed, and the
     * redirect is what relaunches the app. There is no armed channel in the new process and no
     * `state` in memory — only the persisted Pending Authorization, which is why it is persisted.
     */
    @Test
    fun a_redirect_that_relaunches_the_process_still_completes_the_login() = androidSignInTest {
        // No `signIn()`: the process that started this one is gone, and with it the armed channel
        // that would otherwise have taken the redirect. All that is left is what was written down.
        val pending = mintPendingAuthorization()

        // `MainActivity.onCreate` forwarding the launch Intent, before anything above it exists.
        inbox.deliver(redirectIntent(pending.state))
        val relaunched = relaunched()

        assertEquals(SessionState.SignedIn(FAKE_MAL_USER), relaunched.awaitSettledSession())
    }

    /**
     * A launch Intent is not a user action. It stays on the `ActivityRecord` of an activity that a
     * redirect started, so the system re-delivers it on every later relaunch of that task — long
     * after the login it belongs to finished, and with nobody having done anything.
     */
    @Test
    fun a_launch_redirect_with_nothing_left_to_complete_is_ignored() = androidSignInTest {
        val pending = mintPendingAuthorization()
        inbox.deliver(redirectIntent(pending.state))
        val relaunched = relaunched()
        assertEquals(SessionState.SignedIn(FAKE_MAL_USER), relaunched.awaitSettledSession())

        // Reopening from recents after the process was killed, days later.
        relaunched.inbox.deliver(redirectIntent(pending.state))
        val reopened = relaunched.relaunched()
        reopened.settle()

        assertEquals(SessionState.SignedIn(FAKE_MAL_USER), reopened.repository.state.value)
        assertNull(reopened.viewModel.error, "a spent launch Intent must not be reported as a failure")
    }

    /**
     * One process, one launch redirect. A second `StartupRedirect` consumption would hand a spent
     * code to a repository with no Pending Authorization left, and put an error card over a Session
     * that is working perfectly.
     */
    @Test
    fun a_stale_redirect_fired_after_a_completed_login_is_ignored() = androidSignInTest {
        signIn()
        val pending = assertNotNull(store.readPending())
        inbox.deliver(redirectIntent(pending.state))
        assertEquals(SessionState.SignedIn(FAKE_MAL_USER), awaitSettledSession())

        // `adb shell am start` with an old code, or any app on the device deciding to try one.
        inbox.deliver(redirectIntent(pending.state))
        settle()

        assertEquals(SessionState.SignedIn(FAKE_MAL_USER), repository.state.value)
        assertNull(viewModel.error)
    }

    /**
     * One per test, built inside `runTest` against the scheduler [settle] drives — which is what
     * makes `viewModelScope`, and therefore every assertion here, deterministic.
     */
    private class Fixture(
        private val scope: TestScope,
        /** Survives a [relaunched] process, exactly as `SharedPreferences` does. */
        private val kv: FakeKeyValueStore = FakeKeyValueStore(),
        /** So does the inbox: it is process-scoped, and a relaunch *is* the redirect arriving. */
        val inbox: AuthRedirectInbox = AuthRedirectInbox(),
    ) {
        val lifecycle = MutableStateFlow(Lifecycle.State.RESUMED)

        /** Every request the fake MyAnimeList answered, so an exchange can be counted and not inferred. */
        val requested = mutableListOf<String>()
        val store = JsonTokenStore(kv)
        val repository = MalSessionRepository(
            store = store,
            initialConfig = MalAuthConfig(
                clientId = "a-client-id",
                redirectUri = ANDROID_REDIRECT_URI,
                tokenEndpoint = FAKE_MAL_TOKEN_ENDPOINT,
                apiBaseUrl = FAKE_MAL_API_BASE_URL,
            ),
            clientFactory = fakeMal { requested += it.url.toString() },
        )
        val viewModel = MalSessionViewModel(repository, AndroidStartupRedirect(store, inbox))
        private val channel = IntentRedirectChannel(
            inbox = inbox,
            lifecycleStates = lifecycle,
            launchBrowser = { },
        )

        /** How many times the token endpoint was asked to exchange a code. */
        fun exchanges(): Int = requested.count { it.startsWith(FAKE_MAL_TOKEN_ENDPOINT) }

        fun signIn() {
            viewModel.signIn(channel, openUri = {})
            settle()
        }

        /** How far a process that dies behind the browser gets: a record in the store, and no more. */
        suspend fun mintPendingAuthorization(): PendingAuthorization {
            repository.beginAuthorization()
            return checkNotNull(store.readPending())
        }

        /** Runs everything `viewModelScope` has outstanding. */
        fun settle() = scope.advanceUntilIdle()

        /** See `MalSessionViewModelRedirectTest` for why `settle()` alone is not enough here. */
        suspend fun awaitSettledSession(): SessionState = repository.state.first {
            it is SessionState.SignedOut || (it is SessionState.SignedIn && it.user != null)
        }

        /** A fresh process over the same store and the same inbox: process death, then a redirect. */
        fun relaunched() = Fixture(scope, kv, inbox).also { children += it }

        private val children = mutableListOf<Fixture>()

        fun close() {
            children.forEach { it.close() }
            repository.close()
        }
    }

    private fun androidSignInTest(block: suspend Fixture.() -> Unit) = runTest {
        // viewModelScope runs on Dispatchers.Main, which no test platform provides by default.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        try {
            fixture.block()
        } finally {
            fixture.close()
        }
    }
}
