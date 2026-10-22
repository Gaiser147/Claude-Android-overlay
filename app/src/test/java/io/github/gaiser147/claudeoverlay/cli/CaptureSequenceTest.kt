package io.github.gaiser147.claudeoverlay.cli

import io.github.gaiser147.claudeoverlay.capture.CaptureSequence
import io.github.gaiser147.claudeoverlay.capture.CaptureWindow
import io.github.gaiser147.claudeoverlay.capture.ScreenshotSource
import io.github.gaiser147.claudeoverlay.capture.Shot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSequenceTest {
    private val log = mutableListOf<String>()

    private val window = object : CaptureWindow {
        override suspend fun dismissKeyboard() { log += "keyboard away" }
        override suspend fun setHidden(hidden: Boolean, keepDrawings: Boolean) {
            log += (if (hidden) "hide" else "show") + if (keepDrawings) " (keep drawings)" else ""
        }
    }

    @Test
    fun `keyboard goes first, then the overlay, and the capture is told when it vanished`() = runTest {
        val source = object : ScreenshotSource {
            override suspend fun capture(notBeforeNanos: Long): Shot? {
                log += "capture after $notBeforeNanos"
                return Shot(Attachment("s.jpg", "image/jpeg", byteArrayOf(1)), null, 10, 20)
            }
        }
        val shot = CaptureSequence(window, clock = { 42L }).run(source)
        assertEquals(listOf("keyboard away", "hide", "capture after 42", "show"), log)
        assertEquals(10 to 20, shot!!.width to shot.height)
    }

    @Test
    fun `a check shot can leave Claude's drawings in the picture`() = runTest {
        val source = object : ScreenshotSource {
            override suspend fun capture(notBeforeNanos: Long): Shot? = null
        }
        CaptureSequence(window).run(source, keepDrawings = true)
        assertEquals(listOf("keyboard away", "hide (keep drawings)", "show (keep drawings)"), log)
    }

    @Test
    fun `the overlay comes back even when the capture fails`() = runTest {
        val source = object : ScreenshotSource {
            override suspend fun capture(notBeforeNanos: Long): Shot? = error("boom")
        }
        runCatching { CaptureSequence(window).run(source) }
        assertEquals(listOf("keyboard away", "hide", "show"), log)
    }
}
