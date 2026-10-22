package io.github.gaiser147.claudeoverlay.tools

import kotlin.math.roundToInt

/** A point in screen pixels. */
data class Pt(val x: Float, val y: Float)

/**
 * Maps between the latest screenshot Claude saw (scaled down, its size varies) and the real screen.
 * Every tool takes coordinates in screenshot pixels, the space Claude can actually measure in.
 */
data class ShotGeometry(val imageWidth: Int, val imageHeight: Int, val screenWidth: Int, val screenHeight: Int) {
    private val sx get() = screenWidth.toFloat() / imageWidth
    private val sy get() = screenHeight.toFloat() / imageHeight

    fun toScreen(x: Float, y: Float) = Pt(
        (x * sx).coerceIn(0f, screenWidth - 1f),
        (y * sy).coerceIn(0f, screenHeight - 1f),
    )

    companion object {
        /** The space a screenshot with long edge [maxEdge] would have. */
        fun forScreen(screenWidth: Int, screenHeight: Int, maxEdge: Int): ShotGeometry {
            val scale = minOf(1f, maxEdge.toFloat() / maxOf(screenWidth, screenHeight))
            return ShotGeometry(
                (screenWidth * scale).roundToInt(), (screenHeight * scale).roundToInt(), screenWidth, screenHeight,
            )
        }
    }

    /** Screen pixels → screenshot pixels. */
    fun toImage(p: Pt) = Pt(p.x / sx, p.y / sy)

    /** A length (radius, text height) in screenshot pixels → screen pixels. */
    fun toScreenLength(v: Float) = v * (sx + sy) / 2

    fun contains(x: Float, y: Float) = x in 0f..imageWidth.toFloat() && y in 0f..imageHeight.toFloat()
}

/** One continuous finger/pen movement, in screen pixels. */
data class Stroke(val points: List<Pt>, val durationMs: Long)

object Strokes {
    /** Finger speed for drawn strokes: slow enough for apps to sample smoothly, px per second. */
    const val DRAW_SPEED = 1200f
    const val MIN_STROKE_MS = 40L

    fun length(points: List<Pt>): Float = points.zipWithNext { a, b ->
        kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
    }.sum()

    /** A drawing stroke whose duration follows its length, so long lines aren't flung. */
    fun drawn(points: List<Pt>, speed: Float = DRAW_SPEED): Stroke =
        Stroke(points, maxOf(MIN_STROKE_MS, (length(points) / speed * 1000).toLong()))
}
