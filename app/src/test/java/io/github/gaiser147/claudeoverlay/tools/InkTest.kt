package io.github.gaiser147.claudeoverlay.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class InkTest {
    private val ink = Ink(unit = 2f)

    @Test
    fun `strokes taper at both ends and swell in the middle`() {
        val mark = ink.render(Annotation.Underline(Pt(0f, 100f), Pt(300f, 100f), wavy = false, color = null, label = null))
        val w = mark.strokes.single().widths
        assertTrue(w.first() < w[w.size / 2] * 0.6f)
        assertTrue(w.last() < w[w.size / 2] * 0.6f)
    }

    @Test
    fun `a hand-drawn line wobbles but still starts and ends on target`() {
        val mark = ink.render(Annotation.Strike(Pt(0f, 50f), Pt(400f, 50f), color = null))
        val pts = mark.strokes.single().points
        assertEquals(0f, pts.first().x, 0.5f)
        assertEquals(400f, pts.last().x, 0.5f)
        assertTrue("not perfectly straight", pts.any { kotlin.math.abs(it.y - pts.first().y) > 0.3f })
    }

    @Test
    fun `the same annotation always looks the same`() {
        val a = Annotation.Ellipse(Pt(200f, 200f), 80f, 40f, null, "hier")
        val p1 = ink.render(a).strokes.single().points
        val p2 = ink.render(a).strokes.single().points
        assertEquals(p1, p2)
    }

    @Test
    fun `circles loop past their start like a pen and surround the target`() {
        val c = Pt(200f, 200f)
        val mark = ink.render(Annotation.Ellipse(c, 60f, 60f, null, null))
        val pts = mark.strokes.single().points
        assertTrue(pts.all { hypot(it.x - c.x, it.y - c.y) > 60f })
        val start = pts.first(); val end = pts.last()
        assertTrue("ends near but not exactly on its start", hypot(end.x - start.x, end.y - start.y) in 1f..80f)
    }

    @Test
    fun `arrows get a curved body and a two-stroke head at the target`() {
        val mark = ink.render(Annotation.Arrow(Pt(0f, 0f), Pt(300f, 0f), bend = 0.2f, color = null, label = "Aufgabe 1"))
        assertEquals(3, mark.strokes.size)
        val body = mark.strokes[0].points
        assertTrue("bowed", body.maxOf { kotlin.math.abs(it.y) } > 20f)
        assertEquals(300f, body.last().x, 1f)
        assertEquals("Aufgabe 1", mark.texts.single().text)
    }

    @Test
    fun `highlights are a translucent marker band as tall as the text`() {
        val mark = ink.render(Annotation.Highlight(Pt(10f, 100f), Pt(210f, 130f), color = null))
        val s = mark.strokes.single()
        assertTrue(s.marker)
        assertTrue(s.alpha < 0.5f)
        assertTrue(s.widths.all { it in 27f..34f })
    }
}

class SvgPathTest {
    @Test
    fun `lines, relative moves and close`() {
        val c = SvgPath.parse("M10 10 L20 10 l0 10 h-10 Z m 100 100 v 5")
        assertEquals(2, c.size)
        assertEquals(listOf(Pt(10f, 10f), Pt(20f, 10f), Pt(20f, 20f), Pt(10f, 20f), Pt(10f, 10f)), c[0])
        assertEquals(Pt(110f, 115f), c[1].last())
    }

    @Test
    fun `curves and arcs end where they should`() {
        assertEquals(Pt(100f, 0f), SvgPath.parse("M0 0 C 30 -40 70 -40 100 0").single().last())
        assertEquals(Pt(100f, 0f), SvgPath.parse("M0 0 Q50 50 100 0").single().last())
        val arc = SvgPath.parse("M0 0 A 50 50 0 0 1 100 0").single()
        val last = arc.last()
        assertEquals(100f, last.x, 0.01f)
        assertEquals(0f, last.y, 0.01f)
        assertTrue("semicircle bulges by its radius", arc.minOf { it.y } < -49f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `garbage is refused`() {
        SvgPath.parse("10 10 L")
    }
}

class AnnotationParserTest {
    private val g = ShotGeometry(500, 1000, 1000, 2000)
    private fun parse(json: String) = AnnotationParser.parse(Json.parseToJsonElement(json).jsonArray, g)

    @Test
    fun `pen marks parse into screen space`() {
        val (u, c, k, d) = parse(
            """[{"type":"underline","x":10,"y":20,"width":100,"wavy":true},
                {"type":"circle","x":10,"y":10,"width":40,"height":20},
                {"type":"check","x":50,"y":50},
                {"type":"dot","x":5,"y":5,"color":"blue"}]"""
        )
        assertEquals(Annotation.Underline(Pt(20f, 40f), Pt(220f, 40f), true, null, null), u)
        assertEquals(Annotation.Ellipse(Pt(60f, 40f), 40f, 20f, null, null), c)
        assertEquals(56f, (k as Annotation.Check).size, 0.01f)
        assertEquals(0xFF2F6FDB.toInt(), (d as Annotation.Dot).color)
    }

    @Test
    fun `svg paths are offset, scaled and mapped`() {
        val s = parse("""[{"type":"svg","d":"M0 0 L10 0","x":5,"y":5,"scale":2}]""").single() as Annotation.Sketch
        assertEquals(listOf(Pt(10f, 10f), Pt(50f, 10f)), s.contours.single())
    }
}
