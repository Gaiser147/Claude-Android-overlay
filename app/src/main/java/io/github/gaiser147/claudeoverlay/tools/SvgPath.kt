package io.github.gaiser147.claudeoverlay.tools

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SVG path data → polylines, so Claude can draw smooth curves and diagrams the way it already
 * knows how to write them. Supports M L H V C S Q T A Z, absolute and relative.
 */
object SvgPath {
    /** Max length of one polyline segment when flattening curves, in path units. */
    private const val STEP = 4f

    fun parse(d: String): List<List<Pt>> {
        val tokens = tokenize(d)
        val contours = mutableListOf<MutableList<Pt>>()
        var current = mutableListOf<Pt>()
        var x = 0f; var y = 0f
        var startX = 0f; var startY = 0f
        var lastCtrlX = 0f; var lastCtrlY = 0f
        var lastCmd = ' '
        var i = 0
        var cmd = ' '

        fun num(): Float {
            val t = tokens.getOrNull(i) ?: throw IllegalArgumentException("path data ends in the middle of a command")
            i++
            return t.toFloatOrNull() ?: throw IllegalArgumentException("expected a number, got '$t'")
        }
        fun flag(): Boolean = num() != 0f
        fun lineTo(nx: Float, ny: Float) { current += Pt(nx, ny); x = nx; y = ny }
        fun moveTo(nx: Float, ny: Float) {
            if (current.size > 1) contours += current
            current = mutableListOf(Pt(nx, ny))
            x = nx; y = ny; startX = nx; startY = ny
        }

        while (i < tokens.size) {
            val t = tokens[i]
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0]
                i++
            } else if (cmd == ' ') {
                throw IllegalArgumentException("expected a command letter before '$t'")
            } else if (cmd == 'M') {
                cmd = 'L'
            } else if (cmd == 'm') {
                cmd = 'l'
            }
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f
            val oy = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> moveTo(ox + num(), oy + num())
                'L' -> lineTo(ox + num(), oy + num())
                'H' -> lineTo(ox + num(), y)
                'V' -> lineTo(x, oy + num())
                'Z' -> {
                    lineTo(startX, startY)
                    if (current.size > 1) contours += current
                    current = mutableListOf(Pt(startX, startY))
                }
                'C' -> {
                    val c1x = ox + num(); val c1y = oy + num()
                    val c2x = ox + num(); val c2y = oy + num()
                    val ex = ox + num(); val ey = oy + num()
                    cubic(x, y, c1x, c1y, c2x, c2y, ex, ey, current)
                    lastCtrlX = c2x; lastCtrlY = c2y; x = ex; y = ey
                }
                'S' -> {
                    val (c1x, c1y) = if (lastCmd.uppercaseChar() in "CS") (2 * x - lastCtrlX) to (2 * y - lastCtrlY) else x to y
                    val c2x = ox + num(); val c2y = oy + num()
                    val ex = ox + num(); val ey = oy + num()
                    cubic(x, y, c1x, c1y, c2x, c2y, ex, ey, current)
                    lastCtrlX = c2x; lastCtrlY = c2y; x = ex; y = ey
                }
                'Q' -> {
                    val cx = ox + num(); val cy = oy + num()
                    val ex = ox + num(); val ey = oy + num()
                    quad(x, y, cx, cy, ex, ey, current)
                    lastCtrlX = cx; lastCtrlY = cy; x = ex; y = ey
                }
                'T' -> {
                    val (cx, cy) = if (lastCmd.uppercaseChar() in "QT") (2 * x - lastCtrlX) to (2 * y - lastCtrlY) else x to y
                    val ex = ox + num(); val ey = oy + num()
                    quad(x, y, cx, cy, ex, ey, current)
                    lastCtrlX = cx; lastCtrlY = cy; x = ex; y = ey
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num()
                    val large = flag(); val sweep = flag()
                    val ex = ox + num(); val ey = oy + num()
                    arc(x, y, rx, ry, rot, large, sweep, ex, ey, current)
                    x = ex; y = ey
                }
                else -> throw IllegalArgumentException("unsupported command '$cmd'")
            }
            lastCmd = cmd
            // Z takes no numbers, so whatever follows must be a new command.
            if (cmd.uppercaseChar() == 'Z') cmd = ' '
        }
        if (current.size > 1) contours += current
        return contours
    }

    private fun tokenize(d: String): List<String> =
        Regex("""[A-Za-z]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").findAll(d).map { it.value }.toList()

    private fun segments(len: Float) = ceil(len / STEP).toInt().coerceIn(2, 200)

    private fun cubic(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, out: MutableList<Pt>) {
        val n = segments(hypot(x1 - x0, y1 - y0) + hypot(x2 - x1, y2 - y1) + hypot(x3 - x2, y3 - y2))
        for (k in 1..n) {
            val t = k.toFloat() / n; val u = 1 - t
            out += Pt(
                u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3,
            )
        }
    }

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, out: MutableList<Pt>) {
        val n = segments(hypot(x1 - x0, y1 - y0) + hypot(x2 - x1, y2 - y1))
        for (k in 1..n) {
            val t = k.toFloat() / n; val u = 1 - t
            out += Pt(u * u * x0 + 2 * u * t * x1 + t * t * x2, u * u * y0 + 2 * u * t * y1 + t * t * y2)
        }
    }

    /** SVG elliptical arc (endpoint parameterisation, per the SVG spec's implementation notes). */
    private fun arc(
        x1: Float, y1: Float, rxIn: Float, ryIn: Float, rotDeg: Float,
        large: Boolean, sweep: Boolean, x2: Float, y2: Float, out: MutableList<Pt>,
    ) {
        var rx = abs(rxIn).toDouble(); var ry = abs(ryIn).toDouble()
        if (rx == 0.0 || ry == 0.0 || (x1 == x2 && y1 == y2)) { out += Pt(x2, y2); return }
        val phi = Math.toRadians(rotDeg.toDouble())
        val cosP = cos(phi); val sinP = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt((num / den).coerceAtLeast(0.0))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2.0
        val cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2.0
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = acos(((ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))).coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0) -a else a
        }
        val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && delta > 0) delta -= 2 * PI else if (sweep && delta < 0) delta += 2 * PI
        val n = segments((abs(delta) * maxOf(rx, ry)).toFloat())
        for (k in 1..n) {
            val t = theta1 + delta * k / n
            val px = rx * cos(t); val py = ry * sin(t)
            out += Pt((cosP * px - sinP * py + cx).toFloat(), (sinP * px + cosP * py + cy).toFloat())
        }
    }
}
