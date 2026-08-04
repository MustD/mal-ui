package io.challenge_workshop.mal_ui.mal

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {

    @Test
    fun verifierDefaultsToMaximumAllowedLength() {
        assertEquals(Pkce.MAX_VERIFIER_LENGTH, Pkce.generateCodeVerifier().length)
    }

    @Test
    fun verifierUsesOnlyUnreservedCharacters() {
        val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')
        val verifier = Pkce.generateCodeVerifier(random = Random(1))
        assertTrue(verifier.all { it in allowed }, "unexpected characters in $verifier")
    }

    @Test
    fun verifierRejectsLengthsOutsideTheSpec() {
        assertFailsWith<IllegalArgumentException> { Pkce.generateCodeVerifier(42) }
        assertFailsWith<IllegalArgumentException> { Pkce.generateCodeVerifier(129) }
    }

    @Test
    fun verifierAcceptsSpecBoundaries() {
        assertEquals(43, Pkce.generateCodeVerifier(43).length)
        assertEquals(128, Pkce.generateCodeVerifier(128).length)
    }

    @Test
    fun eachVerifierIsFresh() {
        assertNotEquals(Pkce.generateCodeVerifier(), Pkce.generateCodeVerifier())
    }

    @Test
    fun challengeEqualsVerifierBecauseMalOnlySupportsPlain() {
        val verifier = Pkce.generateCodeVerifier()
        assertEquals("plain", Pkce.CHALLENGE_METHOD)
        assertEquals(verifier, Pkce.codeChallengeOf(verifier))
    }
}
