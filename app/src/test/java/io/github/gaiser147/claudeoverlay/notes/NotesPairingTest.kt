package io.github.gaiser147.claudeoverlay.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesPairingTest {
    private val clone = "io.github.gaiser147.notesclaude"
    private val certs = mapOf(clone to "aa11", "evil.app" to "bb22")
    private fun decide(packages: List<String>, pinned: NotesPin?) = NotesPairing.decide(packages, certs::get, pinned)

    @Test
    fun `the first clone is only paired after asking`() {
        assertEquals(NotesPairing.Verdict.Ask(NotesPin(clone, "aa11")), decide(listOf(clone), null))
    }

    @Test
    fun `the paired clone is trusted`() {
        val pin = NotesPin(clone, "aa11")
        assertEquals(NotesPairing.Verdict.Trusted(pin), decide(listOf(clone), pin))
    }

    @Test
    fun `the same package signed with another key is refused`() {
        val v = decide(listOf(clone), NotesPin(clone, "ffff"))
        assertTrue(v is NotesPairing.Verdict.Refused)
    }

    @Test
    fun `shared uids, unknown certificates and the overlay itself are refused`() {
        assertTrue(decide(listOf(clone, "evil.app"), null) is NotesPairing.Verdict.Refused)
        assertTrue(decide(listOf("no.cert"), null) is NotesPairing.Verdict.Refused)
        assertTrue(decide(listOf("io.github.gaiser147.claudeoverlay"), null) is NotesPairing.Verdict.Refused)
    }

    @Test
    fun `another app after pairing has to ask, it never inherits trust`() {
        val v = decide(listOf("evil.app"), NotesPin(clone, "aa11"))
        assertEquals(NotesPairing.Verdict.Ask(NotesPin("evil.app", "bb22")), v)
    }

    @Test
    fun `pins survive storage and garbage doesn't`() {
        val pin = NotesPin(clone, "aa11")
        assertEquals(pin, NotesPin.decode(pin.encode()))
        assertNull(NotesPin.decode("nonsense"))
        assertNull(NotesPin.decode(null))
    }

    @Test
    fun `the fingerprint shown to the user is short`() {
        assertEquals("AA:11:BB:22:CC:33:DD:44…", NotesPairing.shortFingerprint("aa11bb22cc33dd44ee55"))
    }
}
