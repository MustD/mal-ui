package io.challenge_workshop.mal_ui.mal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MalAuthorizationTest {

    @Test
    fun parsesCodeAndStateFromFullRedirectUrl() {
        val result = parseRedirect("http://127.0.0.1:18040/oauth/callback?code=abc123&state=xyz")
        assertEquals("abc123", result.code)
        assertEquals("xyz", result.state)
    }

    @Test
    fun parsesRedirectWithFragmentDelimiter() {
        assertEquals("abc123", parseRedirect("http://localhost/cb#code=abc123&state=s").code)
    }

    @Test
    fun toleratesSurroundingWhitespaceFromCopyPaste() {
        assertEquals("abc123", parseRedirect("  http://localhost/cb?code=abc123  ").code)
    }

    @Test
    fun acceptsBareCode() {
        val result = parseRedirect("just-the-code-value")
        assertEquals("just-the-code-value", result.code)
        assertNull(result.state, "a bare code carries no state to verify")
    }

    @Test
    fun surfacesMalReportedErrors() {
        val error = assertFailsWith<MalAuthException> {
            parseRedirect("http://localhost/cb?error=access_denied&error_description=User+said+no")
        }
        assertEquals("access_denied", error.errorCode)
        assertTrue(error.message!!.contains("User said no"), "lost the description: ${error.message}")
    }

    @Test
    fun rejectsRedirectWithoutCode() {
        assertFailsWith<MalAuthException> { parseRedirect("http://localhost/cb?state=xyz") }
    }

    @Test
    fun rejectsUrlWithNoQueryString() {
        assertFailsWith<MalAuthException> { parseRedirect("http://127.0.0.1:18040/oauth/callback") }
    }

    @Test
    fun rejectsBlankInput() {
        assertFailsWith<MalAuthException> { parseRedirect("   ") }
    }

    @Test
    fun rejectsFreeTextThatIsNeitherUrlNorCode() {
        assertFailsWith<MalAuthException> { parseRedirect("I could not find the code") }
    }
}
