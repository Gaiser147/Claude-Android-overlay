package io.github.gaiser147.claudeoverlay.stylus

import io.github.gaiser147.claudeoverlay.tools.Pt
import io.github.gaiser147.claudeoverlay.tools.Stroke
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sin

/** One pen event: [action] is one of [StylusPlan]'s DOWN, MOVE, UP and HOVER_* constants. */
data class PenEvent(val action: Int, val x: Float, val y: Float, val pressure: Float, val timeMs: Long)

/**
 * Turns strokes into the event stream a real pen produces: it hovers in to the start (pen apps
 * such as Samsung Notes notice the pen while it approaches and may ignore a pen that touches
 * down out of nowhere), touches down, moves every few pixels at the stroke's pace and lifts,
 * then hovers over to the next stroke. Pressure rises as the pen lands, varies a little and
 * eases off as it lifts, which pen apps turn into natural ink.
 */
object StylusPlan {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    const val HOVER_ENTER = 3
    const val HOVER_MOVE = 4
    const val HOVER_EXIT = 5

    /** Max distance between move events, px: dense enough for smooth curves. */
    private const val STEP_PX = 5f
    private const val MIN_DT_MS = 4L
    /** Contact time of a single-point stroke (a dot), so apps register it. */
    private const val DOT_MS = 40L
    /** Pen in the air between strokes. */
    const val LIFT_MS = 45L
    /** Hovering at the start before the first stroke, as a hand brings the pen in. */
    const val APPROACH_MS = 150L
    private const val HOVER_STEP_MS = 10L

    fun plan(strokes: List<Stroke>): List<PenEvent> {
        val out = mutableListOf<PenEvent>()
        var t = 0L
        var from: Pt? = null
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val pts = resample(stroke.points)
            t = hover(out, from ?: pts.first(), pts.first(), t, if (from == null) APPROACH_MS else LIFT_MS)
            val dt = (stroke.durationMs / (pts.size - 1).coerceAtLeast(1)).coerceAtLeast(MIN_DT_MS)
            pts.forEachIndexed { i, p ->
                val along = if (pts.size == 1) 0.5f else i.toFloat() / (pts.size - 1)
                out += PenEvent(if (i == 0) DOWN else MOVE, p.x, p.y, pressure(along), t)
                if (i < pts.lastIndex) t += dt
            }
            if (pts.size == 1) t += DOT_MS
            val last = pts.last()
            out += PenEvent(UP, last.x, last.y, 0f, t)
            from = last
        }
        // The pen leaves after the last stroke.
        from?.let { hover(out, it, it, t, HOVER_STEP_MS) }
        return out
    }

    /** The pen in the air from [a] to [b] over [ms], starting at [t]; returns the time at [b]. */
    private fun hover(out: MutableList<PenEvent>, a: Pt, b: Pt, t: Long, ms: Long): Long {
        val n = (ms / HOVER_STEP_MS).toInt().coerceAtLeast(1)
        out += PenEvent(HOVER_ENTER, a.x, a.y, 0f, t)
        for (k in 1..n) {
            val f = k.toFloat() / n
            val action = if (k == n) HOVER_EXIT else HOVER_MOVE
            out += PenEvent(action, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, 0f, t + ms * k / n)
        }
        return t + ms
    }

    /** Points at most [STEP_PX] apart along the polyline. */
    internal fun resample(points: List<Pt>): List<Pt> {
        if (points.size < 2) return points
        val out = mutableListOf(points.first())
        for ((a, b) in points.zipWithNext()) {
            val n = ceil(hypot(b.x - a.x, b.y - a.y) / STEP_PX).toInt().coerceAtLeast(1)
            for (k in 1..n) {
                val f = k.toFloat() / n
                out += Pt(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            }
        }
        return out
    }

    /** Pen pressure at [along] (0..1) of a stroke's length. */
    internal fun pressure(along: Float): Float {
        val land = (along / 0.12f).coerceAtMost(1f)
        val lift = ((1 - along) / 0.15f).coerceAtMost(1f)
        return (0.25f + 0.45f * minOf(land, lift) + 0.05f * sin(along * 9 * PI.toFloat())).coerceIn(0.1f, 1f)
    }
}
