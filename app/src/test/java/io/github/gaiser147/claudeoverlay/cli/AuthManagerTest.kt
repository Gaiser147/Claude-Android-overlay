package io.github.gaiser147.claudeoverlay.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuthManagerTest {
    @Test
    fun `finds the sign-in URL in the CLI's login prompt`() {
        val out = "Opening browser to sign in…\nIf the browser didn't open, visit: " +
            "https://claude.com/cai/oauth/authorize?code=true&client_id=abc&state=xyz\n" +
            "Paste code here if prompted > "
        assertEquals(
            "https://claude.com/cai/oauth/authorize?code=true&client_id=abc&state=xyz",
            AuthManager.findLoginUrl(out),
        )
        assertEquals(null, AuthManager.findLoginUrl("Opening browser to sign in…"))
    }

    @Test
    fun `reads login status past leading warnings`() {
        assertTrue(AuthManager.parseLoggedIn("Claude configuration file not found\n{\n \"loggedIn\": true\n}"))
        assertFalse(AuthManager.parseLoggedIn("{\"loggedIn\": false}"))
        assertFalse(AuthManager.parseLoggedIn("Error: something"))
    }

    @Test
    fun `cli environment routes traffic through the proxy and uses the bundled bash`() {
        val env = cliEnvironment(File("/h"), File("/t"), File("/lib/arm64/libbash.so"), "http://u:p@127.0.0.1:1")
        assertEquals("/lib/arm64/libbash.so", env["SHELL"])
        assertEquals("/lib/arm64/libbash.so", env["CLAUDE_CODE_SHELL"])
        assertEquals("http://u:p@127.0.0.1:1", env["HTTPS_PROXY"])
        assertEquals("/h", env["HOME"])
        assertEquals("1", env["DISABLE_AUTOUPDATER"])
    }
}
