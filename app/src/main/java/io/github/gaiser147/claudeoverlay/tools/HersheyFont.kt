package io.github.gaiser147.claudeoverlay.tools

import java.text.Normalizer

/**
 * Text → pen strokes, for writing into a notes app the way a hand would. Uses the Hershey
 * single-stroke fonts (public domain, see THIRD_PARTY_NOTICES.md): each glyph is a few
 * polylines, exactly what a finger or pen gesture can trace.
 *
 * Glyph data is the Hershey vertex string: a left/right bearing pair, then points encoded as
 * (char - 'R'); " R" lifts the pen. Capitals span y = -12..9, the baseline is 9.
 */
class HersheyFont(lines: List<String>) {
    private class Glyph(val left: Int, val right: Int, val strokes: List<List<Pair<Int, Int>>>)

    private val glyphs: Map<Char, Glyph> = lines.mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val code = line.substring(0, tab).toIntOrNull() ?: return@mapNotNull null
        code.toChar() to parse(line.substring(tab + 1))
    }.toMap()

    /**
     * Lays [text] out with its top-left at ([x], [y]) and capitals [capHeight] tall, wrapping
     * at [maxWidth] (all in the caller's units). Returns one point list per pen stroke.
     */
    fun layout(text: String, x: Float, y: Float, capHeight: Float, maxWidth: Float? = null): List<List<Pt>> {
        val scale = capHeight / CAP_UNITS
        val lineAdvance = capHeight * LINE_SPACING
        val out = mutableListOf<List<Pt>>()
        var lineTop = y
        for (paragraph in text.split('\n')) {
            var penX = x
            for (word in paragraph.split(' ')) {
                val chars = expand(word)
                val wordWidth = chars.sumOf { advance(it.base) * it.size.toDouble() }.toFloat() * scale
                if (maxWidth != null && penX > x && penX + wordWidth - x > maxWidth) {
                    penX = x
                    lineTop += lineAdvance
                }
                for (ch in chars) {
                    val g = glyphs[ch.base] ?: glyphs['?'] ?: continue
                    val s = scale * ch.size
                    val originX = penX - g.left * s
                    val originY = lineTop + (TOP_UNITS + ch.rise) * scale
                    g.strokes.forEach { stroke -> out += stroke.map { (gx, gy) -> Pt(originX + gx * s, originY + gy * s) } }
                    ch.mark?.let { mark ->
                        val top = if (ch.base.isUpperCase()) CAP_MARK_Y else LOWER_MARK_Y
                        val cx = originX + (g.left + g.right) / 2f * s
                        out += markStrokes(mark, cx, originY + top * s, s)
                    }
                    penX += (g.right - g.left) * s
                }
                penX += advance(' ') * scale
            }
            lineTop += lineAdvance
        }
        return out
    }

    /** The characters of [text] this font can't write; they come out as '?'. */
    fun missing(text: String): Set<Char> =
        text.filter { it != '\n' && it != ' ' && expand(it.toString()).any { d -> d.base == '?' && it != '?' } }.toSet()

    private fun advance(c: Char): Int = (glyphs[c] ?: glyphs['?'])?.let { it.right - it.left } ?: 0

    /**
     * A character to draw: a glyph at [size] times the normal size, its origin [rise] units
     * lower (negative: higher), plus an accent the font lacks drawn on top.
     */
    private class Drawn(val base: Char, val mark: Char? = null, val size: Float = 1f, val rise: Float = 0f)

    /**
     * Umlauts and accents as base letter + mark; ß as "ss"; ² and ₂ as small raised or lowered
     * characters; other compatibility forms (ﬁ, ½…) by their plain spelling; anything else as '?'.
     */
    private fun expand(word: String): List<Drawn> = word.flatMap { c ->
        val sup = SUPERSCRIPTS.indexOf(c)
        val sub = SUBSCRIPTS.indexOf(c)
        when {
            glyphs.containsKey(c) -> listOf(Drawn(c))
            c == 'ß' -> listOf(Drawn('s'), Drawn('s'))
            sup >= 0 -> listOf(Drawn(SCRIPT_BASES[sup], size = SCRIPT_SIZE, rise = SUPERSCRIPT_RISE))
            sub >= 0 -> listOf(Drawn(SCRIPT_BASES[sub], size = SCRIPT_SIZE, rise = SUBSCRIPT_RISE))
            else -> {
                val nfd = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                val base = nfd.first()
                val mark = nfd.drop(1).firstOrNull()
                val compat = Normalizer.normalize(c.toString(), Normalizer.Form.NFKC)
                when {
                    glyphs.containsKey(base) -> listOf(Drawn(base, mark?.takeIf { it in MARKS }))
                    compat != c.toString() && compat.all { glyphs.containsKey(it) } -> compat.map { Drawn(it) }
                    else -> listOf(Drawn('?'))
                }
            }
        }
    }

    private fun markStrokes(mark: Char, cx: Float, y: Float, scale: Float): List<List<Pt>> {
        val dot = { dx: Float -> listOf(Pt(cx + dx * scale, y), Pt(cx + dx * scale, y + 1.2f * scale)) }
        return when (mark) {
            DIAERESIS -> listOf(dot(-2.5f), dot(2.5f))
            ACUTE -> listOf(listOf(Pt(cx - 1 * scale, y + 2 * scale), Pt(cx + 2 * scale, y - 1 * scale)))
            GRAVE -> listOf(listOf(Pt(cx - 2 * scale, y - 1 * scale), Pt(cx + 1 * scale, y + 2 * scale)))
            CIRCUMFLEX -> listOf(listOf(Pt(cx - 2.5f * scale, y + 2 * scale), Pt(cx, y - 1 * scale), Pt(cx + 2.5f * scale, y + 2 * scale)))
            TILDE -> listOf(listOf(Pt(cx - 3 * scale, y + 1 * scale), Pt(cx - 1 * scale, y - 0.5f * scale), Pt(cx + 1 * scale, y + 1 * scale), Pt(cx + 3 * scale, y - 0.5f * scale)))
            else -> emptyList()
        }
    }

    companion object {
        private const val CAP_UNITS = 21f
        private const val TOP_UNITS = 12f
        private const val LINE_SPACING = 1.7f
        private const val CAP_MARK_Y = -16f
        private const val LOWER_MARK_Y = -9f
        private const val DIAERESIS = '̈'
        private const val ACUTE = '́'
        private const val GRAVE = '̀'
        private const val CIRCUMFLEX = '̂'
        private const val TILDE = '̃'
        private val MARKS = setOf(DIAERESIS, ACUTE, GRAVE, CIRCUMFLEX, TILDE)

        // Superscripts and subscripts: same position in each string as their plain character.
        private const val SCRIPT_BASES = "0123456789+-=()nix"
        private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱˣ"
        private const val SUBSCRIPTS = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₙᵢₓ"
        private const val SCRIPT_SIZE = 0.6f
        // Origin shifts, in units of the normal size, that put the small baseline (9 × size
        // below its origin) at y = -2, just under the x-height (-5), or at 13, below the baseline (9).
        private const val SUPERSCRIPT_RISE = -2f - 9f * SCRIPT_SIZE
        private const val SUBSCRIPT_RISE = 13f - 9f * SCRIPT_SIZE

        private fun parse(data: String): Glyph {
            val left = data[0] - 'R'
            val right = data[1] - 'R'
            val strokes = mutableListOf<List<Pair<Int, Int>>>()
            var current = mutableListOf<Pair<Int, Int>>()
            var i = 2
            while (i + 1 < data.length) {
                if (data[i] == ' ' && data[i + 1] == 'R') {
                    if (current.isNotEmpty()) strokes += current
                    current = mutableListOf()
                } else {
                    current += (data[i] - 'R') to (data[i + 1] - 'R')
                }
                i += 2
            }
            if (current.isNotEmpty()) strokes += current
            return Glyph(left, right, strokes)
        }
    }
}
