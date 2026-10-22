package io.github.gaiser147.claudeoverlay.tools

import io.github.gaiser147.claudeoverlay.stylus.PenEvent
import io.github.gaiser147.claudeoverlay.stylus.Stylus
import io.github.gaiser147.claudeoverlay.stylus.StylusPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StylusPlanTest {
    private val line = Stroke(listOf(Pt(0f, 0f), Pt(100f, 0f)), durationMs = 200)
    private val dot = Stroke(listOf(Pt(50f, 50f)), durationMs = 40)
    private val hovers = setOf(StylusPlan.HOVER_ENTER, StylusPlan.HOVER_MOVE, StylusPlan.HOVER_EXIT)

    private fun List<PenEvent>.touching() = filter { it.action !in hovers }

    @Test
    fun `each stroke is one down, moves at most a few pixels apart, one up`() {
        val events = StylusPlan.plan(listOf(line)).touching()
        assertEquals(StylusPlan.DOWN, events.first().action)
        assertEquals(StylusPlan.UP, events.last().action)
        assertTrue(events.drop(1).dropLast(1).all { it.action == StylusPlan.MOVE })
        val moves = events.dropLast(1)
        assertTrue(moves.zipWithNext().all { (a, b) -> hypot(b.x - a.x, b.y - a.y) <= 5.01f })
        assertEquals(100f, events.last().x, 0.01f)
    }

    @Test
    fun `the pen hovers in before touching down, between strokes and when it leaves`() {
        val actions = StylusPlan.plan(listOf(line, line)).map { it.action }
        assertEquals(StylusPlan.HOVER_ENTER, actions.first())
        assertEquals(StylusPlan.HOVER_EXIT, actions.last())
        // Every touch-down comes right after the hover ends, every lift is followed by a hover.
        actions.forEachIndexed { i, a ->
            if (a == StylusPlan.DOWN) assertEquals(StylusPlan.HOVER_EXIT, actions[i - 1])
            if (a == StylusPlan.UP) assertEquals(StylusPlan.HOVER_ENTER, actions[i + 1])
        }
        assertEquals(3, actions.count { it == StylusPlan.HOVER_ENTER })
    }

    @Test
    fun `time runs forward at the stroke's pace, with an approach and a lift between strokes`() {
        val events = StylusPlan.plan(listOf(line, line))
        assertTrue(events.zipWithNext().all { (a, b) -> b.timeMs >= a.timeMs })
        val downs = events.filter { it.action == StylusPlan.DOWN }
        val ups = events.filter { it.action == StylusPlan.UP }
        assertEquals(StylusPlan.APPROACH_MS, downs[0].timeMs)
        assertEquals(200f, (ups[0].timeMs - downs[0].timeMs).toFloat(), 10f)
        assertEquals(StylusPlan.LIFT_MS, downs[1].timeMs - ups[0].timeMs)
    }

    @Test
    fun `the hover glides from one stroke's end to the next stroke's start`() {
        val second = Stroke(listOf(Pt(0f, 50f), Pt(10f, 50f)), durationMs = 100)
        val events = StylusPlan.plan(listOf(line, second))
        val up = events.indexOfFirst { it.action == StylusPlan.UP }
        val glide = events.drop(up + 1).takeWhile { it.action in hovers }
        assertEquals(Pt(100f, 0f), Pt(glide.first().x, glide.first().y))
        assertEquals(Pt(0f, 50f), Pt(glide.last().x, glide.last().y))
        assertTrue(glide.all { it.pressure == 0f })
    }

    @Test
    fun `pressure lands light, swells and lifts`() {
        val p = StylusPlan.plan(listOf(line)).touching().filter { it.action != StylusPlan.UP }.map { it.pressure }
        assertTrue(p.all { it in 0.1f..1f })
        assertTrue(p.first() < p[p.size / 2])
        assertTrue(p.last() < p[p.size / 2])
    }

    @Test
    fun `a dot still touches down for a moment`() {
        val events = StylusPlan.plan(listOf(dot)).touching()
        assertEquals(listOf(StylusPlan.DOWN, StylusPlan.UP), events.map { it.action })
        assertTrue(events[1].timeMs - events[0].timeMs >= 30)
    }

    @Test
    fun `binder chunks only split while the pen is in the air`() {
        val events = StylusPlan.plan(List(10) { line })
        val chunks = Stylus.chunks(events, max = 30)
        assertTrue(chunks.size > 1)
        assertEquals(events, chunks.flatten())
        assertTrue(chunks.all { it.first().action == StylusPlan.HOVER_ENTER })
        assertTrue(chunks.dropLast(1).all { it.last().action == StylusPlan.UP })
    }
}
