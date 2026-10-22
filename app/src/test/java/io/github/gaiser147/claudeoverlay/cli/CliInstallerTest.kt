package io.github.gaiser147.claudeoverlay.cli

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

class CliInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `parses the registry's latest manifest`() {
        val release = CliInstaller.parseRelease(
            """{"name":"@anthropic-ai/claude-code-linux-arm64-musl","version":"2.1.308",
               "dist":{"tarball":"https://registry.npmjs.org/x/-/x-2.1.308.tgz","integrity":"sha512-AAAA","shasum":"ff"}}"""
        )
        assertEquals(CliRelease("2.1.308", "https://registry.npmjs.org/x/-/x-2.1.308.tgz", "sha512-AAAA"), release)
    }

    @Test
    fun `integrity strings map to a digest and its bytes`() {
        val digest = MessageDigest.getInstance("SHA-512").digest("hi".toByteArray())
        val (alg, bytes) = CliInstaller.parseIntegrity("sha512-" + Base64.getEncoder().encodeToString(digest))
        assertEquals("SHA-512", alg)
        assertArrayEquals(digest, bytes)
    }

    @Test(expected = IOException::class)
    fun `weak integrity algorithms are refused`() {
        CliInstaller.parseIntegrity("sha1-AAAA")
    }

    @Test
    fun `extracts the wanted entry and skips the rest`() {
        val payload = ByteArray(1500) { (it % 251).toByte() }
        val tar = tarOf("package/README.md" to "hello".toByteArray(), "package/claude" to payload)
        val dest = tmp.newFile()
        assertTrue(TarReader(ByteArrayInputStream(tar)).extract("package/claude", dest))
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun `honours PAX path records`() {
        val pax = "path=package/claude\n".let { "${it.length + 3} $it" }
        val tar = tarOf("PaxHeader" to pax.toByteArray(), "package/ignored-short-name" to "x".toByteArray(), paxFirst = true)
        val dest = tmp.newFile()
        assertTrue(TarReader(ByteArrayInputStream(tar)).extract("package/claude", dest))
        assertEquals("x", dest.readText())
    }

    @Test
    fun `reports a missing entry`() {
        val tar = tarOf("package/README.md" to "hello".toByteArray())
        assertFalse(TarReader(ByteArrayInputStream(tar)).extract("package/claude", tmp.newFile()))
    }

    /** A minimal ustar archive; with [paxFirst] the first entry is typed as a PAX header. */
    private fun tarOf(vararg entries: Pair<String, ByteArray>, paxFirst: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        entries.forEachIndexed { i, (name, data) ->
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "0000644\u0000".toByteArray().copyInto(h, 100)
            String.format("%011o\u0000", data.size).toByteArray().copyInto(h, 124)
            h[156] = if (paxFirst && i == 0) 'x'.code.toByte() else '0'.code.toByte()
            "ustar\u000000".toByteArray().copyInto(h, 257)
            "        ".toByteArray().copyInto(h, 148)
            val sum = h.sumOf { it.toInt() and 0xFF }
            String.format("%06o\u0000 ", sum).toByteArray().copyInto(h, 148)
            out.write(h)
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }
}
