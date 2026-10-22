package io.github.gaiser147.claudeoverlay.tools

import io.github.gaiser147.claudeoverlay.stylus.Stylus
import io.github.gaiser147.claudeoverlay.stylus.StylusPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StylusPlanTest {
    private val line = Stroke(listOf(Pt(0f, 0f), Pt(100f, 0f)), durationMs = 200)
    private val dot = Stroke(listOf(Pt(50f, 50f)), durationMs = 40)

    @Test
    fun `each stroke is one down, moves at most a few pixels apart, one up`() {
        val events = StylusPlan.plan(listOf(line))
        assertEquals(StylusPlan.DOWN, events.first().action)
        assertEquals(StylusPlan.UP, events.last().action)
        assertTrue(events.drop(1).dropLast(1).all { it.action == StylusPlan.MOVE })
        val moves = events.dropLast(1)
        assertTrue(moves.zipWithNext().all { (a, b) -> hypot(b.x - a.x, b.y - a.y) <= 5.01f })
        assertEquals(100f, events.last().x, 0.01f)
    }

    @Test
    fun `time runs forward at the stroke's pace, with a lift between strokes`() {
        val events = StylusPlan.plan(listOf(line, line))
        assertTrue(events.zipWithNext().all { (a, b) -> b.timeMs >= a.timeMs })
        val firstUp = events.indexOfFirst { it.action == StylusPlan.UP }
        assertEquals(200f, events[firstUp].timeMs.toFloat(), 10f)
        assertEquals(StylusPlan.LIFT_MS, events[firstUp + 1].timeMs - events[firstUp].timeMs)
    }

    @Test
    fun `pressure lands light, swells and lifts`() {
        val p = StylusPlan.plan(listOf(line)).filter { it.action != StylusPlan.UP }.map { it.pressure }
        assertTrue(p.all { it in 0.1f..1f })
        assertTrue(p.first() < p[p.size / 2])
        assertTrue(p.last() < p[p.size / 2])
    }

    @Test
    fun `a dot still touches down for a moment`() {
        val events = StylusPlan.plan(listOf(dot))
        assertEquals(listOf(StylusPlan.DOWN, StylusPlan.UP), events.map { it.action })
        assertTrue(events[1].timeMs - events[0].timeMs >= 30)
    }

    @Test
    fun `binder chunks only split between strokes`() {
        val events = StylusPlan.plan(List(10) { line })
        val chunks = Stylus.chunks(events, max = 30)
        assertTrue(chunks.size > 1)
        assertEquals(events, chunks.flatten())
        assertTrue(chunks.all { it.first().action == StylusPlan.DOWN && it.last().action == StylusPlan.UP })
    }
}
