package io.challenge_workshop.mal_ui.mal

import kotlin.random.Random

/**
 * PKCE (RFC 7636) helpers, narrowed to what MAL accepts.
 *
 * MAL documents support for the `plain` challenge method only, so the challenge is the
 * verifier verbatim and no SHA-256 is involved. That is weaker than `S256` but is not
 * our choice to make.
 */
object Pkce {
    /** RFC 7636 §4.1 `unreserved` set: ALPHA / DIGIT / "-" / "." / "_" / "~". */
    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    const val MIN_VERIFIER_LENGTH: Int = 43
    const val MAX_VERIFIER_LENGTH: Int = 128

    /** The only `code_challenge_method` MAL supports. */
    const val CHALLENGE_METHOD: String = "plain"

    /**
     * Generates a fresh code verifier. A new one is required per authorization request.
     *
     * @param length between [MIN_VERIFIER_LENGTH] and [MAX_VERIFIER_LENGTH]; defaults to the maximum.
     */
    fun generateCodeVerifier(
        length: Int = MAX_VERIFIER_LENGTH,
        random: Random = Random.Default,
    ): String {
        require(length in MIN_VERIFIER_LENGTH..MAX_VERIFIER_LENGTH) {
            "code verifier length must be in $MIN_VERIFIER_LENGTH..$MAX_VERIFIER_LENGTH, was $length"
        }
        return buildString(length) {
            repeat(length) { append(UNRESERVED[random.nextInt(UNRESERVED.length)]) }
        }
    }

    /** With the `plain` method the challenge equals the verifier. */
    fun codeChallengeOf(codeVerifier: String): String = codeVerifier

    /** Opaque value echoed back in the redirect, used to detect a mismatched/forged callback. */
    fun generateState(length: Int = 32, random: Random = Random.Default): String =
        buildString(length) {
            repeat(length) { append(UNRESERVED[random.nextInt(UNRESERVED.length)]) }
        }
}
