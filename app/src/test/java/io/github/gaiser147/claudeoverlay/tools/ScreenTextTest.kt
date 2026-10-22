package io.github.gaiser147.claudeoverlay.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {
    private val screen = Box(0, 0, 1000, 2000)
    private fun snap(vararg nodes: UiNode, keyboardTop: Int? = null) =
        ScreenSnapshot("App", listOf(UiNode(bounds = screen, children = nodes.toList())), screen, keyboardTop)

    @Test
    fun `a tappable row takes the words of its plain children`() {
        val row = UiNode(
            clickable = true, bounds = Box(0, 0, 1000, 100),
            children = listOf(UiNode(text = "Mathe", bounds = Box(0, 0, 500, 50)), UiNode(text = "3 neu", bounds = Box(0, 50, 500, 100))),
        )
        assertEquals(listOf(ScreenElement("button", "Mathe · 3 neu", Pt(500f, 50f))), ScreenText.elements(snap(row)))
    }

    @Test
    fun `a clickable page is a container, not one huge button`() {
        val page = UiNode(
            clickable = true, bounds = screen,
            children = listOf(UiNode(text = "Absatz eins", bounds = Box(0, 0, 1000, 100)), UiNode(text = "Absatz zwei", bounds = Box(0, 100, 1000, 200))),
        )
        assertEquals(listOf("Absatz eins", "Absatz zwei"), ScreenText.elements(snap(page)).map { it.label })
    }

    @Test
    fun `state shows as flags, and what's off screen or empty is left out`() {
        val elements = ScreenText.elements(
            snap(
                UiNode(role = "Switch", text = "WLAN", checkable = true, checked = true, bounds = Box(0, 0, 1000, 100)),
                UiNode(role = "EditText", editable = true, hint = "Name", focused = true, bounds = Box(0, 100, 1000, 200)),
                UiNode(role = "Button", text = "Senden", clickable = true, bounds = Box(0, 1500, 1000, 1600)),
                UiNode(text = "weg", bounds = Box(0, 2100, 1000, 2200)),
                UiNode(clickable = true, bounds = Box(0, 300, 100, 400)),
                keyboardTop = 1200,
            )
        )
        assertEquals(listOf("switch", "field", "button"), elements.map { it.kind })
        assertEquals(listOf("on"), elements[0].flags)
        assertEquals(listOf("(empty, hint \"Name\")", "focused"), elements[1].flags)
        assertEquals(listOf("under keyboard"), elements[2].flags)
    }

    @Test
    fun `rendering numbers the lines in screenshot pixels`() {
        val s = snap(UiNode(text = "Hallo", bounds = Box(100, 200, 300, 400)), keyboardTop = 1000)
        val text = ScreenText.render(s, ScreenText.elements(s), ShotGeometry(500, 1000, 1000, 2000))
        assertEquals("App: App · keyboard open, covers y ≥ 500 · coordinates in a 500×1000 screenshot space\n[1] \"Hallo\" @100,150\n", text)
    }

    @Test
    fun `long labels are clipped and sparse screens are recognised`() {
        val long = UiNode(text = "x".repeat(200), bounds = Box(0, 0, 1000, 100))
        assertTrue(ScreenText.elements(snap(long)).single().label.length <= 70)
        assertTrue(ScreenText.isSparse(ScreenText.elements(snap(long))))
        assertFalse(
            ScreenText.isSparse(
                ScreenText.elements(
                    snap(*Array(3) { UiNode(text = "t$it", bounds = Box(0, it * 100, 1000, it * 100 + 100)) })
                )
            )
        )
    }
}
