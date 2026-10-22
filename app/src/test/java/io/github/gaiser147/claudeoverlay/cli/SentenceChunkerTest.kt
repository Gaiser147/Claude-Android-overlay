package io.github.gaiser147.claudeoverlay.cli

import io.github.gaiser147.claudeoverlay.voice.SentenceChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceChunkerTest {
    private fun speakAll(vararg deltas: String): List<String> {
        val c = SentenceChunker()
        return deltas.flatMap { c.feed(it) } + c.flush()
    }

    @Test
    fun `sentences are released as soon as they end`() {
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.feed("Hallo, das ist"))
        assertEquals(listOf("Hallo, das ist ein Test."), c.feed(" ein Test. Und noch"))
        assertEquals(listOf("Und noch einer!"), c.feed(" einer! "))
        assertEquals(emptyList<String>(), c.flush())
    }

    @Test
    fun `numbers and abbreviations don't split sentences`() {
        assertEquals(listOf("Opus 5.5 ist z.B. schnell."), speakAll("Opus 5.5 ist z.B. schnell."))
    }

    @Test
    fun `code blocks are skipped even when the fence arrives in pieces`() {
        assertEquals(
            listOf("Hier der Code:", "Fertig."),
            speakAll("Hier der Code:\n``", "`kotlin\nval x = 1. y\n", "`", "``\nFertig."),
        )
    }

    @Test
    fun `markdown punctuation is not read out`() {
        assertEquals(
            listOf("Wichtig: das ist fett und kursiv.", "Ein Link und code."),
            speakAll("## Wichtig: **das** ist *fett* und _kursiv_.\n- Ein [Link](https://x.y) und `code`."),
        )
    }

    @Test
    fun `nothing speakable yields nothing`() {
        assertNull(SentenceChunker.speakable("---"))
        assertEquals(emptyList<String>(), speakAll("```\nonly code\n```"))
    }
}
