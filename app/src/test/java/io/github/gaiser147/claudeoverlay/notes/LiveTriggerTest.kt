package io.github.gaiser147.claudeoverlay.notes

import io.github.gaiser147.claudeoverlay.tools.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTriggerTest {
    private fun line(text: String, top: Int, block: Int = 0) = OcrLine(text, Box(100, top, 600, top + 40), block)

    @Test
    fun `a marked question is found, with its continuation in the same block`() {
        val q = LiveTrigger.find(
            listOf(
                line("Hausaufgabe 3", 0, block = 0),
                line("@c Was ist die Ableitung", 100, block = 1),
                line("von sin(x²)?", 150, block = 1),
                line("Nächste Aufgabe", 300, block = 2),
            )
        )!!
        assertEquals("Was ist die Ableitung von sin(x²)?", q.text)
        assertEquals(Box(100, 100, 600, 190), q.box)
    }

    @Test
    fun `handwriting read loosely still triggers`() {
        for (mark in listOf("@c: Frage", "@ C Frage", "(@c Frage", "© Frage", "@claude Frage")) {
            val q = LiveTrigger.find(listOf(line(mark, 0)))
            assertEquals(mark, "Frage", q?.text)
        }
    }

    @Test
    fun `ordinary writing never triggers`() {
        for (text in listOf("Mail an anna@cloud.de", "@cat ist kein Auslöser", "c'est la vie", "Kosten: 5 €")) {
            assertNull(text, LiveTrigger.find(listOf(line(text, 0))))
        }
    }

    @Test
    fun `the same question read again has the same key, another note's does not`() {
        val a = LiveTrigger.Question("Was ist 17·23?", Box(0, 0, 1, 1))
        val b = LiveTrigger.Question("was ist 17 23", Box(5, 5, 9, 9))
        assertEquals(LiveTrigger.key("n1", a), LiveTrigger.key("n1", b))
        assertNotEquals(LiveTrigger.key("n1", a), LiveTrigger.key("n2", a))
    }

    @Test
    fun `Claude is told where the question is in its screenshot`() {
        val q = LiveTrigger.Question("Was ist 17·23?", Box(200, 400, 1000, 480))
        val m = NotesWatcher.message(q, 1000 to 1600, 2000 to 3200)
        assertTrue(m, m.contains("[100, 200, 500, 240]"))
        assertTrue(m, m.contains("notes_suggest"))
        assertTrue(NotesWatcher.message(q, null, 2000 to 3200).contains("Was ist 17·23?"))
    }
}
