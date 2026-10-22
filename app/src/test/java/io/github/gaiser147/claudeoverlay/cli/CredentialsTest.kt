package io.github.gaiser147.claudeoverlay.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(json: String?) = Credentials.file(tmp.root).apply {
        parentFile!!.mkdirs()
        if (json != null) writeText(json) else delete()
    }

    @Test
    fun `stored tokens mean signed in`() {
        val f = file("""{"claudeAiOauth":{"accessToken":"a","refreshToken":"r","expiresAt":1}}""")
        assertEquals(Credentials.State.LoggedIn, Credentials.state(f))
    }

    @Test
    fun `an expired access token alone is not a dead login`() {
        val f = file("""{"claudeAiOauth":{"accessToken":"","refreshToken":"r","expiresAt":0}}""")
        assertEquals(Credentials.State.LoggedIn, Credentials.state(f))
    }

    @Test
    fun `only the CLI's blanked marker counts as dead`() {
        val f = file("""{"claudeAiOauth":{"accessToken":"","refreshToken":"","expiresAt":0}}""")
        assertEquals(Credentials.State.Dead, Credentials.state(f))
    }

    @Test
    fun `missing and unreadable files are told apart`() {
        assertEquals(Credentials.State.Missing, Credentials.state(file(null)))
        assertEquals(Credentials.State.Missing, Credentials.state(file("""{"mcpOAuth":{}}""")))
        assertEquals(Credentials.State.Unknown, Credentials.state(file("{not json")))
    }

    @Test
    fun `any rewrite changes the signature`() {
        val f = file("""{"claudeAiOauth":{"refreshToken":"r1"}}""")
        val before = Credentials.signature(f)
        f.writeText("""{"claudeAiOauth":{"refreshToken":"r2-longer"}}""")
        assertNotEquals(before, Credentials.signature(f))
    }

    @Test
    fun `auth failures are recognised in CLI error text only by their own wording`() {
        assertTrue(Credentials.isAuthError("Failed to authenticate: OAuth session expired and could not be refreshed. Run /login"))
        assertTrue(Credentials.isAuthError("Please run `claude auth login`"))
        assertFalse(Credentials.isAuthError("API Error: 529 overloaded"))
        assertFalse(Credentials.isAuthError(null))
    }
}
