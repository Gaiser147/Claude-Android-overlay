package io.github.gaiser147.claudeoverlay.notes

import io.github.gaiser147.claudeoverlay.tools.Box

/**
 * Where the page changed between two screenshots, cheaply: both are reduced to a coarse grid
 * of average brightness, and cells that differ by more than a pen stroke's worth are grouped
 * into rectangles. Only those rectangles go to text recognition.
 */
class PageGrid private constructor(val cols: Int, val rows: Int, val cellW: Float, val cellH: Float, private val luma: IntArray) {

    /**
     * Rectangles (screen pixels) around the cells that changed since [before], skipping cells
     * inside [ignore] (the overlay's own windows). Touching cells form one rectangle.
     */
    fun changedSince(before: PageGrid?, ignore: List<Box> = emptyList()): List<Box> {
        if (before == null || before.cols != cols || before.rows != rows) return emptyList()
        val changed = BooleanArray(cols * rows) { i ->
            kotlin.math.abs(luma[i] - before.luma[i]) >= THRESHOLD && ignore.none { it.containsCell(i) }
        }
        val seen = BooleanArray(changed.size)
        val out = mutableListOf<Box>()
        for (start in changed.indices) {
            if (!changed[start] || seen[start]) continue
            var minC = cols; var minR = rows; var maxC = -1; var maxR = -1
            val stack = ArrayDeque<Int>().apply { add(start) }
            seen[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val c = i % cols; val r = i / cols
                minC = minOf(minC, c); maxC = maxOf(maxC, c); minR = minOf(minR, r); maxR = maxOf(maxR, r)
                // 8-neighbourhood plus one cell of slack: the letters of a word are a few cells apart.
                for (dr in -2..2) for (dc in -2..2) {
                    val nr = r + dr; val nc = c + dc
                    if (nr !in 0 until rows || nc !in 0 until cols) continue
                    val j = nr * cols + nc
                    if (changed[j] && !seen[j]) { seen[j] = true; stack.add(j) }
                }
            }
            out += Box(
                (minC * cellW).toInt(), (minR * cellH).toInt(),
                ((maxC + 1) * cellW).toInt(), ((maxR + 1) * cellH).toInt(),
            )
        }
        return out
    }

    private fun Box.containsCell(i: Int): Boolean {
        val cx = (i % cols + 0.5f) * cellW
        val cy = (i / cols + 0.5f) * cellH
        return cx >= left && cx < right && cy >= top && cy < bottom
    }

    companion object {
        /** About 10 screen pixels per cell on a tablet: a word of handwriting spans several. */
        const val CELL_PX = 12
        /** Brightness change (0–255) of a cell that counts: ink in it, not compression noise. */
        const val THRESHOLD = 6

        /**
         * @param argb row-major pixels of a [width]×[height] image of the screen.
         * @param scale screen pixels per image pixel (the image may be scaled down).
         */
        fun of(argb: IntArray, width: Int, height: Int, scale: Float = 1f): PageGrid {
            val cols = (width * scale / CELL_PX).toInt().coerceAtLeast(1)
            val rows = (height * scale / CELL_PX).toInt().coerceAtLeast(1)
            val sum = LongArray(cols * rows)
            val count = IntArray(cols * rows)
            for (y in 0 until height) {
                val r = minOf(rows - 1, y * rows / height)
                val row = y * width
                for (x in 0 until width) {
                    val p = argb[row + x]
                    val l = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
                    val cell = r * cols + minOf(cols - 1, x * cols / width)
                    sum[cell] += l.toLong()
                    count[cell]++
                }
            }
            val luma = IntArray(cols * rows) { if (count[it] == 0) 0 else (sum[it] / count[it]).toInt() }
            return PageGrid(cols, rows, width * scale / cols, height * scale / rows, luma)
        }
    }
}

/** One line of recognized text, in screen pixels. */
data class OcrLine(val text: String, val box: Box, val block: Int)

/**
 * Finds a question the user marked for Claude on the page: a line starting with the trigger
 * ("@c", also as read by text recognition from handwriting: "@ c", "©", "(@c"), followed by
 * the question, which may continue on the next lines of the same block.
 */
object LiveTrigger {
    private val MARK = Regex("""^\W{0,2}(?:@\s?[cC](?:laude)?|©)(?![a-zA-Z])\s*[:\-–]?\s*""")

    data class Question(val text: String, val box: Box)

    fun find(lines: List<OcrLine>): Question? {
        val i = lines.indexOfFirst { MARK.containsMatchIn(it.text.trim()) }
        if (i < 0) return null
        val first = lines[i]
        val rest = lines.drop(i + 1).takeWhile { it.block == first.block }
        val text = (listOf(MARK.replace(first.text.trim(), "")) + rest.map { it.text.trim() })
            .filter { it.isNotBlank() }.joinToString(" ")
        val all = listOf(first) + rest
        val box = Box(all.minOf { it.box.left }, all.minOf { it.box.top }, all.maxOf { it.box.right }, all.maxOf { it.box.bottom })
        return Question(text, box)
    }

    /** The same question read again (after a scroll, a redraw) must not be sent twice. */
    fun key(noteId: String?, q: Question): String =
        (noteId ?: "") + "|" + q.text.lowercase().filter(Char::isLetterOrDigit)
}
