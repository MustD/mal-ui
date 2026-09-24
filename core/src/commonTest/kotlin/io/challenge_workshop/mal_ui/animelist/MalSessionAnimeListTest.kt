package io.challenge_workshop.mal_ui.animelist

import io.challenge_workshop.mal_ui.session.FakeClock
import io.challenge_workshop.mal_ui.session.FakeKeyValueStore
import io.challenge_workshop.mal_ui.session.FakeMal
import io.challenge_workshop.mal_ui.session.JsonTokenStore
import io.challenge_workshop.mal_ui.session.MalSessionRepository
import io.challenge_workshop.mal_ui.session.STALE_TOKENS
import io.challenge_workshop.mal_ui.session.TEST_CONFIG
import io.challenge_workshop.mal_ui.session.TEST_USER
import io.challenge_workshop.mal_ui.session.VALID_TOKENS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Anime List over the repository's **own** authenticated client, rather than over a second one
 * built beside it.
 *
 * Two Ktor `Auth` providers over one token store is a refresh race that does not fail loudly — it
 * occasionally loses a Session and looks like a random sign-out. Nothing about the pager's own
 * behaviour would catch it, so what is asserted here is the observable consequence of getting it
 * right: a page request that meets an expired access token refreshes **once** and then succeeds,
 * carrying the rotated token.
 */
class MalSessionAnimeListTest {

    @Test
    fun a_page_request_meeting_an_expired_access_token_refreshes_once_and_still_loads() = runTest {
        val mal = FakeMal(animeList = { AnimeListResponse.Page(fakeEntries(3), hasMore = true) })
        val store = JsonTokenStore(FakeKeyValueStore(), clock = FakeClock())
        // Deliberately a token this fake MAL has never issued, so the 401 is earned rather than staged.
        store.writeSession(STALE_TOKENS, TEST_USER)
        val repository = MalSessionRepository(
            store = store,
            clock = FakeClock(),
            initialConfig = TEST_CONFIG,
            clientFactory = mal.clientFactory,
        )
        repository.restore()
        val pager = AnimeListPager(repository.animeListClient())

        pager.start()

        assertEquals(3, pager.state.value.entries.size)
        assertNull(pager.state.value.firstPageError)
        assertEquals(1, mal.tokenEndpointHits, "the 401 must have driven exactly one refresh")
        assertEquals(
            listOf("Bearer stale-access", "Bearer fresh-access"),
            mal.animeListAuthorizations,
            "the retry must carry the rotated token, which only the shared client can know about",
        )
        repository.close()
    }

    @Test
    fun the_page_request_goes_to_the_configured_api_base_url() = runTest {
        // On web that base is the Relay's, not MAL's — a client that hardcoded MAL would fail there
        // and nowhere else.
        val mal = FakeMal(acceptedAccessToken = "good-access")
        val store = JsonTokenStore(FakeKeyValueStore(), clock = FakeClock())
        store.writeSession(VALID_TOKENS, TEST_USER)
        val repository = MalSessionRepository(
            store = store,
            clock = FakeClock(),
            initialConfig = TEST_CONFIG,
            clientFactory = mal.clientFactory,
        )
        repository.restore()

        AnimeListPager(repository.animeListClient()).start()

        assertEquals("/v2/users/@me/animelist", mal.animeListRequests.single().encodedPath)
        assertEquals("mal.test", mal.animeListRequests.single().host)
        assertEquals(0, mal.tokenEndpointHits, "a valid token must not have provoked a refresh")
        repository.close()
    }
}
