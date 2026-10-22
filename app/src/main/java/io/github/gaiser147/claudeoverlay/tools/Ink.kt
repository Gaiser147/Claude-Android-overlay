package io.github.gaiser147.claudeoverlay.tools

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A pen or marker stroke: a centreline and the ink width at each of its points (px). */
class InkStroke(val points: List<Pt>, val widths: List<Float>, val color: Int, val alpha: Float, val marker: Boolean = false) {
    val length: Float = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()
}

/** Handwritten text: [at] is its top-left, [size] the font size in px. */
class InkText(val at: Pt, val text: String, val color: Int, val size: Float, val rotationDeg: Float)

/** Everything one annotation turns into, drawn in order: strokes, then its text. */
class InkMark(val strokes: List<InkStroke>, val texts: List<InkText>) {
    /** How much "writing" this is, for pacing the draw-on animation (px of pen travel). */
    val effort: Float = strokes.sumOf { it.length.toDouble() }.toFloat() + texts.sumOf { it.text.length * it.size.toDouble() * 0.6 }.toFloat()
}

/**
 * Turns annotations into ink that looks drawn by hand: a pen that lands and lifts (tapered
 * ends), varies a little in pressure, and never draws a perfectly straight line or closes a
 * circle exactly. The wobble is seeded from the annotation, so a mark looks the same every
 * frame of its animation and every time it's redrawn.
 *
 * Sizes scale with [unit], the screen's px per dp.
 */
class Ink(private val unit: Float) {
    private val pen = 3.4f * unit
    private val step = 3f * unit

    fun render(a: Annotation): InkMark {
        val rnd = Random(a.hashCode().toLong())
        val color = a.color ?: if (a is Annotation.Highlight) HIGHLIGHTER else PEN
        val strokes = mutableListOf<InkStroke>()
        val texts = mutableListOf<InkText>()
        fun stroke(points: List<Pt>, width: Float = pen, alpha: Float = INK_ALPHA) {
            if (points.size >= 2) strokes += InkStroke(points, widths(points, width, rnd), color, alpha)
        }
        when (a) {
            is Annotation.Arrow -> {
                val body = arrowBody(a, rnd)
                stroke(body)
                val tip = body.last()
                val back = body[max(0, body.size - 1 - (8 * unit / step).toInt().coerceAtLeast(1))]
                val angle = atan2(tip.y - back.y, tip.x - back.x)
                val headLen = (hypot(a.to.x - a.from.x, a.to.y - a.from.y) * 0.14f).coerceIn(10 * unit, 18 * unit)
                for (side in listOf(-1, 1)) {
                    val ang = angle + PI.toFloat() + side * (0.45f + rnd.nextFloat() * 0.12f)
                    val end = Pt(tip.x + headLen * cos(ang), tip.y + headLen * sin(ang))
                    stroke(wobbly(tip, end, rnd, amp = 0.6f * unit), width = pen * 0.95f)
                }
                a.label?.let { texts += labelNear(it, a.from, awayFrom = a.to, color = color, rnd = rnd) }
            }
            is Annotation.Box -> {
                val tl = a.topLeft; val br = a.bottomRight
                val corners = listOf(tl, Pt(br.x, tl.y), br, Pt(tl.x, br.y)).map { jitter(it, rnd, 1.5f * unit) }
                for (k in 0 until 4) {
                    val p = corners[k]; val q = corners[(k + 1) % 4]
                    // Sides overshoot the corners a little, the way a quick hand draws a box.
                    stroke(wobbly(extend(q, p, overshoot(rnd)), extend(p, q, overshoot(rnd)), rnd))
                }
                a.label?.let { texts += labelAbove(it, tl, color, rnd) }
            }
            is Annotation.Ellipse -> {
                stroke(loop(a.center, a.rx, a.ry, rnd))
                a.label?.let { texts += labelAbove(it, Pt(a.center.x + a.rx * 0.6f, a.center.y - a.ry), color, rnd) }
            }
            is Annotation.Highlight -> {
                val h = abs(a.bottomRight.y - a.topLeft.y)
                val midY = (a.topLeft.y + a.bottomRight.y) / 2
                // A marker swipe: a touch past both ends, slightly tilted, nearly even width.
                val from = Pt(a.topLeft.x - 2 * unit, midY + (rnd.nextFloat() - 0.5f) * h * 0.08f)
                val to = Pt(a.bottomRight.x + 2 * unit, midY + (rnd.nextFloat() - 0.5f) * h * 0.08f)
                val pts = wobbly(from, to, rnd, amp = 0.5f * unit)
                strokes += InkStroke(pts, pts.indices.map { h * (0.96f + 0.08f * rnd.nextFloat()) }, color, HIGHLIGHT_ALPHA, marker = true)
            }
            is Annotation.Underline -> {
                stroke(if (a.wavy) wavy(a.from, a.to, rnd) else wobbly(a.from, a.to, rnd, bowFraction = 0.02f))
                a.label?.let { texts += labelNear(it, a.to, awayFrom = a.from, color = color, rnd = rnd) }
            }
            is Annotation.Strike -> stroke(wobbly(a.from, Pt(a.to.x, a.to.y - 2 * unit), rnd))
            is Annotation.Check -> {
                val s = a.size
                val c = a.center
                // One continuous movement: short stroke down, long flick up.
                val pts = polyline(
                    listOf(Pt(c.x - 0.45f * s, c.y), Pt(c.x - 0.12f * s, c.y + 0.38f * s), Pt(c.x + 0.5f * s, c.y - 0.5f * s)), rnd,
                )
                stroke(pts, width = pen * 1.15f)
                a.label?.let { texts += labelNear(it, Pt(c.x + 0.5f * s, c.y - 0.5f * s), awayFrom = c, color = color, rnd = rnd) }
            }
            is Annotation.Cross -> {
                val s = a.size / 2
                val c = a.center
                stroke(wobbly(Pt(c.x - s, c.y - s), Pt(c.x + s, c.y + s), rnd), width = pen * 1.1f)
                stroke(wobbly(Pt(c.x + s * 0.95f, c.y - s), Pt(c.x - s * 0.9f, c.y + s * 1.05f), rnd), width = pen * 1.1f)
                a.label?.let { texts += labelNear(it, Pt(c.x + s, c.y - s), awayFrom = c, color = color, rnd = rnd) }
            }
            is Annotation.Dot -> {
                val r = 3.2f * unit
                val pts = (0..10).map { k ->
                    val t = 2 * PI.toFloat() * k / 8
                    Pt(a.at.x + r * 0.5f * cos(t), a.at.y + r * 0.5f * sin(t))
                }
                strokes += InkStroke(pts, pts.map { r * 1.3f }, color, INK_ALPHA)
                a.label?.let { texts += labelNear(it, a.at, awayFrom = Pt(a.at.x - 1, a.at.y + 1), color = color, rnd = rnd) }
            }
            is Annotation.Label -> texts += InkText(a.at, a.text, color, a.size?.let { it * 1.3f } ?: TEXT_SIZE * unit, tilt(rnd))
            is Annotation.Path -> {
                stroke(smooth(a.points, rnd))
                a.label?.let { texts += labelNear(it, a.points.last(), awayFrom = a.points.first(), color = color, rnd = rnd) }
            }
            is Annotation.Sketch -> {
                a.contours.forEach { stroke(polyline(it, rnd, amp = 0.5f * unit)) }
                a.label?.let { l -> a.contours.first().first().let { texts += labelAbove(l, it, color, rnd) } }
            }
        }
        return InkMark(strokes, texts)
    }

    // ── stroke shapes ────────────────────────────────────────────────────────────────────

    /** A gently bowed line from [from] to [to] (the body of an arrow). */
    private fun arrowBody(a: Annotation.Arrow, rnd: Random): List<Pt> {
        val dx = a.to.x - a.from.x; val dy = a.to.y - a.from.y
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val bend = a.bend ?: ((0.10f + rnd.nextFloat() * 0.08f) * if (rnd.nextBoolean()) 1 else -1)
        val bow = (len * bend).coerceIn(-70 * unit, 70 * unit)
        val ctrl = Pt((a.from.x + a.to.x) / 2 - dy / len * bow, (a.from.y + a.to.y) / 2 + dx / len * bow)
        val n = ceil(len / step).toInt().coerceAtLeast(4)
        val pts = (0..n).map { k ->
            val t = k.toFloat() / n; val u = 1 - t
            Pt(u * u * a.from.x + 2 * u * t * ctrl.x + t * t * a.to.x, u * u * a.from.y + 2 * u * t * ctrl.y + t * t * a.to.y)
        }
        return addWobble(pts, rnd, amp = 0.8f * unit)
    }

    /** A hand-drawn straight line: slightly bowed and never quite steady. */
    private fun wobbly(from: Pt, to: Pt, rnd: Random, amp: Float = 1.1f * unit, bowFraction: Float = 0.012f): List<Pt> {
        val len = hypot(to.x - from.x, to.y - from.y)
        val n = ceil(len / step).toInt().coerceAtLeast(3)
        val nx = -(to.y - from.y) / len.coerceAtLeast(1f); val ny = (to.x - from.x) / len.coerceAtLeast(1f)
        val bow = len * bowFraction * (rnd.nextFloat() * 2 - 1)
        val pts = (0..n).map { k ->
            val t = k.toFloat() / n
            val b = bow * 4 * t * (1 - t)
            Pt(from.x + (to.x - from.x) * t + nx * b, from.y + (to.y - from.y) * t + ny * b)
        }
        return addWobble(pts, rnd, amp)
    }

    /** A wavy underline, like the one under a word a teacher queries. */
    private fun wavy(from: Pt, to: Pt, rnd: Random): List<Pt> {
        val len = hypot(to.x - from.x, to.y - from.y)
        val nx = -(to.y - from.y) / len.coerceAtLeast(1f); val ny = (to.x - from.x) / len.coerceAtLeast(1f)
        val wave = 13 * unit
        val amp = 2.6f * unit
        val n = ceil(len / (step * 0.6f)).toInt().coerceAtLeast(8)
        val phase = rnd.nextFloat() * PI.toFloat()
        val pts = (0..n).map { k ->
            val t = k.toFloat() / n
            val o = amp * sin(2 * PI.toFloat() * t * len / wave + phase)
            Pt(from.x + (to.x - from.x) * t + nx * o, from.y + (to.y - from.y) * t + ny * o)
        }
        return addWobble(pts, rnd, 0.4f * unit)
    }

    /** A circle drawn in one go: it starts somewhere, goes round once and overshoots its start. */
    private fun loop(c: Pt, rx: Float, ry: Float, rnd: Random): List<Pt> {
        val start = rnd.nextFloat() * 2 * PI.toFloat()
        val sweep = 2 * PI.toFloat() * (1.08f + rnd.nextFloat() * 0.07f)
        val tiltAngle = (rnd.nextFloat() - 0.5f) * 0.12f
        val n = ceil(sweep * max(rx, ry) / step).toInt().coerceIn(24, 400)
        val pRx = rx + 4 * unit; val pRy = ry + 3 * unit
        val pts = (0..n).map { k ->
            val t = k.toFloat() / n
            val ang = start + sweep * t
            // The hand drifts outward a little, so the overshoot passes beside the start.
            val grow = 1 + 0.07f * t + 0.025f * sin(ang * 2 + start)
            val x = pRx * grow * cos(ang); val y = pRy * grow * sin(ang)
            Pt(c.x + x * cos(tiltAngle) - y * sin(tiltAngle), c.y + x * sin(tiltAngle) + y * cos(tiltAngle))
        }
        return addWobble(pts, rnd, 0.6f * unit)
    }

    /** Straight segments through [points], each drawn by hand. */
    private fun polyline(points: List<Pt>, rnd: Random, amp: Float = 0.9f * unit): List<Pt> =
        points.zipWithNext().flatMapIndexed { i, (p, q) ->
            val seg = wobbly(p, q, rnd, amp, bowFraction = 0.01f)
            if (i == 0) seg else seg.drop(1)
        }

    /** A Catmull-Rom curve through [points]: freehand paths come out smooth, not angular. */
    private fun smooth(points: List<Pt>, rnd: Random): List<Pt> {
        if (points.size < 3) return polyline(points, rnd)
        val out = mutableListOf(points.first())
        for (i in 0 until points.size - 1) {
            val p0 = points[max(0, i - 1)]; val p1 = points[i]; val p2 = points[i + 1]; val p3 = points[min(points.size - 1, i + 2)]
            val n = ceil(hypot(p2.x - p1.x, p2.y - p1.y) / step).toInt().coerceAtLeast(2)
            for (k in 1..n) {
                val t = k.toFloat() / n; val t2 = t * t; val t3 = t2 * t
                fun cr(a: Float, b: Float, c: Float, d: Float) =
                    0.5f * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
                out += Pt(cr(p0.x, p1.x, p2.x, p3.x), cr(p0.y, p1.y, p2.y, p3.y))
            }
        }
        return addWobble(out, rnd, 0.5f * unit)
    }

    /** Low-frequency sideways tremor, fading to zero at both ends so marks still hit their targets. */
    private fun addWobble(pts: List<Pt>, rnd: Random, amp: Float): List<Pt> {
        if (pts.size < 3 || amp <= 0f) return pts
        val f1 = 0.8f + rnd.nextFloat() * 1.2f; val f2 = 2.2f + rnd.nextFloat() * 1.8f
        val p1 = rnd.nextFloat() * 6.3f; val p2 = rnd.nextFloat() * 6.3f
        return pts.mapIndexed { i, p ->
            val prev = pts[max(0, i - 1)]; val next = pts[min(pts.size - 1, i + 1)]
            val dx = next.x - prev.x; val dy = next.y - prev.y
            val l = hypot(dx, dy).coerceAtLeast(0.001f)
            val t = i.toFloat() / (pts.size - 1)
            val env = sin(PI.toFloat() * t)
            val o = amp * env * (0.65f * sin(2 * PI.toFloat() * f1 * t + p1) + 0.35f * sin(2 * PI.toFloat() * f2 * t + p2))
            Pt(p.x - dy / l * o, p.y + dx / l * o)
        }
    }

    /**
     * Pen pressure along a stroke: lands thin, swells, lifts thin — with a little unevenness,
     * like a felt-tip or fineliner rather than a vector line.
     */
    private fun widths(points: List<Pt>, base: Float, rnd: Random): List<Float> {
        val n = points.size
        val f = 1.5f + rnd.nextFloat() * 1.5f; val p = rnd.nextFloat() * 6.3f
        val len = InkStroke(points, emptyList(), 0, 0f).length.coerceAtLeast(1f)
        // Tapers cover a fixed length of pen travel, so short strokes don't become needles.
        val taper = (10 * unit / len).coerceAtMost(0.35f)
        var acc = 0f
        return points.mapIndexed { i, pt ->
            if (i > 0) acc += hypot(pt.x - points[i - 1].x, pt.y - points[i - 1].y)
            val t = acc / len
            val inT = smoothstep(0f, taper, t)
            val outT = smoothstep(0f, taper * 1.4f, 1 - t)
            val pressure = 0.92f + 0.12f * sin(2 * PI.toFloat() * f * t + p)
            base * (0.32f + 0.68f * min(inT, outT)) * pressure
        }.let { w -> if (n == 1) listOf(base) else w }
    }

    // ── labels ───────────────────────────────────────────────────────────────────────────

    private fun labelNear(text: String, at: Pt, awayFrom: Pt, color: Int, rnd: Random): InkText {
        val size = TEXT_SIZE * unit
        val dx = at.x - awayFrom.x; val dy = at.y - awayFrom.y
        val l = hypot(dx, dy).coerceAtLeast(1f)
        val gap = 6 * unit
        val width = text.length * size * 0.42f
        // Beyond the anchor, on the side away from the shape; text grows rightward from its left.
        val x = if (dx / l >= -0.3f) at.x + dx / l * gap else at.x + dx / l * gap - width
        val y = at.y + dy / l * gap - size * if (dy / l > 0.3f) 0f else 0.9f
        return InkText(Pt(x, y), text, color, size, tilt(rnd))
    }

    private fun labelAbove(text: String, at: Pt, color: Int, rnd: Random): InkText {
        val size = TEXT_SIZE * unit
        return InkText(Pt(at.x + 2 * unit, at.y - size * 1.25f), text, color, size, tilt(rnd))
    }

    private fun tilt(rnd: Random) = (rnd.nextFloat() - 0.6f) * 5f

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private fun jitter(p: Pt, rnd: Random, amount: Float) =
        Pt(p.x + (rnd.nextFloat() * 2 - 1) * amount, p.y + (rnd.nextFloat() * 2 - 1) * amount)

    private fun overshoot(rnd: Random) = (2 + rnd.nextFloat() * 5) * unit

    /** [to] moved further along the direction from [from] by [by]. */
    private fun extend(from: Pt, to: Pt, by: Float): Pt {
        val dx = to.x - from.x; val dy = to.y - from.y
        val l = hypot(dx, dy).coerceAtLeast(1f)
        return Pt(to.x + dx / l * by, to.y + dy / l * by)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    companion object {
        /** Default ink: a warm red-orange that reads on white pages and dark screens alike. */
        val PEN = 0xFFD9472B.toInt()
        val HIGHLIGHTER = 0xFFFFD84D.toInt()
        const val INK_ALPHA = 0.93f
        const val HIGHLIGHT_ALPHA = 0.38f
        /** Handwritten label size, dp. */
        const val TEXT_SIZE = 22f
    }
}
