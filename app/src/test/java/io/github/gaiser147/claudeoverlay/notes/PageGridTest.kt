package io.github.gaiser147.claudeoverlay.notes

import io.github.gaiser147.claudeoverlay.tools.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGridTest {
    private val w = 600
    private val h = 400
    private val white = 0xFFFFFFFF.toInt()
    private val ink = 0xFF202020.toInt()

    /** A white page with dark rectangles (x0, y0, x1, y1) "written" on it. */
    private fun page(vararg strokes: IntArray): IntArray {
        val px = IntArray(w * h) { white }
        for (s in strokes) for (y in s[1] until s[3]) for (x in s[0] until s[2]) px[y * w + x] = ink
        return px
    }

    @Test
    fun `an unchanged page has no changes, and the first look has nothing to compare`() {
        val a = PageGrid.of(page(), w, h)
        assertTrue(a.changedSince(null).isEmpty())
        assertTrue(PageGrid.of(page(), w, h).changedSince(a).isEmpty())
    }

    @Test
    fun `a new word becomes one region around it`() {
        val before = PageGrid.of(page(), w, h)
        // Three "letters" a little apart, like a handwritten word.
        val after = PageGrid.of(page(intArrayOf(100, 100, 110, 130), intArrayOf(130, 100, 140, 130), intArrayOf(160, 100, 170, 130)), w, h)
        val regions = after.changedSince(before)
        assertEquals(1, regions.size)
        val r = regions.single()
        assertTrue(r.toString(), r.left <= 100 && r.right >= 170 && r.top <= 100 && r.bottom >= 130)
        assertTrue(r.toString(), r.right - r.left < 120)
    }

    @Test
    fun `separate writing makes separate regions, and the overlay's own windows are ignored`() {
        val before = PageGrid.of(page(), w, h)
        val after = PageGrid.of(page(intArrayOf(20, 20, 40, 40), intArrayOf(500, 300, 540, 340)), w, h)
        assertEquals(2, after.changedSince(before).size)
        assertEquals(1, after.changedSince(before, ignore = listOf(Box(480, 280, 600, 400))).size)
    }

    @Test
    fun `a scaled-down screenshot reports screen pixels`() {
        val before = PageGrid.of(page(), w, h, scale = 2f)
        val after = PageGrid.of(page(intArrayOf(100, 100, 120, 120)), w, h, scale = 2f)
        val r = after.changedSince(before).single()
        assertTrue(r.toString(), r.left in 180..200 && r.right in 240..260)
    }
}
