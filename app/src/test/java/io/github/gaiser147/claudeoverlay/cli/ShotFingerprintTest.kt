package io.github.gaiser147.claudeoverlay.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotFingerprintTest {
    private val w = 330
    private val h = 640

    /** A horizontal gradient with a few bands, like a busy screen. */
    private fun screen(): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val v = ((x * 255 / w) + (if ((y / 80) % 2 == 0) 40 else 0)).coerceAtMost(255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun `identical screens match`() {
        val a = ShotFingerprint.of(screen(), w, h)
        assertEquals(0, ShotFingerprint.distance(a, ShotFingerprint.of(screen(), w, h)))
        assertTrue(ShotFingerprint.sameScreen(a, a.copyOf()))
    }

    @Test
    fun `a blinking caret is noise, a new dialog is not`() {
        val base = ShotFingerprint.of(screen(), w, h)
        val caret = screen().also { px -> for (y in 300..315) px[y * w + 100] = -1 }
        assertTrue(ShotFingerprint.sameScreen(base, ShotFingerprint.of(caret, w, h)))
        val dialog = screen().also { px -> for (y in 200..440) for (x in 30..300) px[y * w + x] = 0xFF202020.toInt() }
        assertFalse(ShotFingerprint.sameScreen(base, ShotFingerprint.of(dialog, w, h)))
    }

    @Test
    fun `no fingerprint means no opinion, never unchanged`() {
        assertFalse(ShotFingerprint.sameScreen(null, LongArray(16)))
    }
}
