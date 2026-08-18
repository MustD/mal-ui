@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.challenge_workshop.mal_ui.auth

import android.content.Intent
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hand-off from an `Intent` to whatever is waiting for one.
 *
 * Robolectric because `Intent` and `Uri` are the input — everything else here is plain coroutines.
 */
@RunWith(RobolectricTestRunner::class)
class AuthRedirectInboxTest {

    private val inbox = AuthRedirectInbox()

    @Test
    fun a_redirect_that_arrives_before_anything_collects_is_still_delivered() = runTest {
        // `replay = 1` is the whole point: the Intent reaches the Activity before the Compose layer
        // is collecting, and without a replay it would be dropped with nothing to show for it.
        inbox.deliver(redirectIntent("a-state"))

        assertEquals(androidRedirect("a-state"), inbox.claim { true })
    }

    @Test
    fun claiming_a_redirect_takes_it_out_of_the_inbox() = runTest {
        inbox.deliver(redirectIntent("a-state"))
        inbox.claim { true }

        // An authorization code is single-use. A recreated composition collecting again must not be
        // handed a spent one.
        assertFalse(inbox.holds { true })
    }

    @Test
    fun a_redirect_nobody_claims_stays_where_it_is() = runTest {
        val refusing = async { inbox.claim { it.contains("state=ours") } }
        runCurrent()

        inbox.deliver(redirectIntent("someone-elses"))
        runCurrent()

        assertTrue(refusing.isActive, "a refused redirect must not complete somebody else's capture")
        assertTrue(inbox.holds { true }, "and it must not be consumed by the collector that refused it")
        refusing.cancel()
    }

    @Test
    fun delivering_an_intent_empties_it() = runTest {
        val intent = redirectIntent("a-state")

        inbox.deliver(intent)

        // The Activity keeps this Intent — `setIntent` stores it and a recreation is handed it
        // again. Left populated, the single-use code would be exchanged a second time.
        assertNull(intent.data)
    }

    @Test
    fun an_intent_carrying_no_redirect_delivers_nothing() = runTest {
        // The launcher Intent, which is what `onCreate` sees on every ordinary start.
        assertFalse(inbox.deliver(Intent(Intent.ACTION_MAIN)))

        assertFalse(inbox.holds { true })
    }

    @Test
    fun the_redirect_this_process_was_launched_with_is_taken_exactly_once() = runTest {
        inbox.deliver(redirectIntent("a-state"))

        assertEquals(androidRedirect("a-state"), inbox.claimLaunchRedirect())
        assertNull(inbox.claimLaunchRedirect(), "consuming, not peeking — see StartupRedirect")
    }
}
