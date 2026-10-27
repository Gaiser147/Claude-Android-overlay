package io.github.gaiser147.claudeoverlay.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun font(name: String) = HersheyFont(File("src/main/assets/hershey/$name.txt").readLines())

/** A phone with a Claude Notes clone in a given state. */
private class NotesHost(var notesStatus: NotesStatus?) : PhoneHost by FakeHost() {
    val inserted = mutableListOf<String>()
    val written = mutableListOf<InkLine>()
    val shown = mutableListOf<Suggestion>()

    override fun notes(): NotesAccess? = notesStatus?.let { status ->
        object : NotesAccess {
            override fun status() = status
            override suspend fun insert(text: String): String? = null.also { inserted += text }
            override suspend fun read() = Result.success(NoteText("n1", "Mathe", "Aufgabe 3"))
            override suspend fun addStrokes(strokes: List<InkLine>): String? = null.also { written += strokes }
        }
    }

    override suspend fun showSuggestion(suggestion: Suggestion) {
        shown += suggestion
    }
}

class NotesToolsTest {
    private val ready = NotesStatus(connected = true, editorOpen = true, noteId = "n1")

    private fun box(host: PhoneHost) = PhoneToolbox(host, font("romans"), font("scripts"), settleMs = 0, sleep = {})

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun ToolResult.text() = content[0]["text"]!!.jsonPrimitive.content

    @Test
    fun `the notes tools are offered`() {
        val names = box(NotesHost(null)).definitions().map { it["name"]!!.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("notes_status", "notes_suggest", "notes_insert", "notes_read")))
    }

    @Test
    fun `status explains each state in words the model can pass on`() = runTest {
        assertTrue(box(NotesHost(null)).call("notes_status", args("{}")).text().contains("isn't set up"))
        assertTrue(box(NotesHost(NotesStatus(connected = false, awaitingPairing = true))).call("notes_status", args("{}")).text().contains("confirm"))
        assertTrue(box(NotesHost(NotesStatus(connected = false))).call("notes_status", args("{}")).text().contains("not running"))
        assertTrue(box(NotesHost(ready)).call("notes_status", args("{}")).text().contains("n1"))
    }

    @Test
    fun `insert and read need a connected clone`() = runTest {
        val host = NotesHost(NotesStatus(connected = false))
        val r = box(host).call("notes_insert", args("""{"text":"Hallo"}"""))
        assertTrue(r.isError)
        assertTrue(host.inserted.isEmpty())
    }

    @Test
    fun `insert writes the text and read returns the note`() = runTest {
        val host = NotesHost(ready)
        val tools = box(host)
        assertFalse(tools.call("notes_insert", args("""{"text":"# Liste\n- Milch"}""")).isError)
        assertEquals(listOf("# Liste\n- Milch"), host.inserted)
        val read = tools.call("notes_read", args("{}")).text()
        assertTrue(read, read.contains("Mathe") && read.contains("Aufgabe 3"))
    }

    @Test
    fun `a suggestion is shown below the question, in screen pixels, and nothing is inserted yet`() = runTest {
        val host = NotesHost(ready)
        val tools = box(host)
        tools.call("screenshot", args("{}")) // 500×1000 shot of a 1000×2000 screen
        val r = tools.call(
            "notes_suggest",
            args("""{"text":"**391**","around":[100,200,300,240],"marks":[{"type":"highlight","around":[100,200,300,240]}]}"""),
        )
        assertFalse(r.text(), r.isError)
        val s = host.shown.single()
        assertEquals(1, s.id)
        assertEquals("**391**", s.text)
        assertEquals(listOf("391"), s.lines)
        assertEquals(200f, s.at.x)
        assertTrue(s.at.y > 480f)
        assertTrue(s.preview.first() is Annotation.Highlight)
        // The preview is the very handwriting ✓ writes into the note.
        assertTrue(s.preview.last() is Annotation.Sketch)
        val ink = s.ink!!
        assertTrue(ink.all { it.color == Suggestion.PREVIEW_COLOR })
        assertTrue(ink.flatMap { it.points }.all { it.x >= 200f && it.y >= s.at.y - 1f })
        assertTrue(host.inserted.isEmpty())
    }

    @Test
    fun `a suggestion needs a place`() = runTest {
        val tools = box(NotesHost(ready))
        tools.call("screenshot", args("{}"))
        val r = tools.call("notes_suggest", args("""{"text":"x"}"""))
        assertTrue(r.isError)
    }

    @Test
    fun `preview lines read like paper and wrap`() {
        val lines = Suggestion.previewLines("## Lösung\n- erst kürzen\n- dann `x` ausrechnen, und zwar sorgfältig Schritt für Schritt", 20f, 200f)
        assertEquals("Lösung", lines[0])
        assertEquals("• erst kürzen", lines[1])
        assertTrue(lines.size > 3)
        assertTrue(lines.none { it.contains("`") || it.startsWith("#") })
    }

    @Test
    fun `notes_write writes handwriting strokes at the place, in screen pixels`() = runTest {
        val host = NotesHost(ready.copy(ink = true))
        val tools = box(host)
        tools.call("screenshot", args("{}"))
        val r = tools.call("notes_write", args("""{"text":"x = 391","at":[100,300],"size":20}"""))
        assertFalse(r.text(), r.isError)
        assertTrue(host.written.isNotEmpty())
        val pts = host.written.flatMap { it.points }
        assertTrue(pts.minOf { it.x } >= 199f && pts.minOf { it.y } >= 599f)
        assertTrue(host.inserted.isEmpty())
    }

    @Test
    fun `notes_write needs a bridge that can write strokes`() = runTest {
        val host = NotesHost(ready)
        val tools = box(host)
        tools.call("screenshot", args("{}"))
        val r = tools.call("notes_write", args("""{"text":"x","at":[1,1]}"""))
        assertTrue(r.isError)
        assertTrue(host.written.isEmpty())
    }

    @Test
    fun `marks become ink lines and their labels handwriting`() {
        val marks = listOf(Annotation.Arrow(Pt(10f, 10f), Pt(200f, 50f), null, null, "hier"))
        val lines = NotesInk.marks(marks.map(Ink(2f)::render), font("scripts"))
        assertTrue(lines.size > 3)
        assertTrue(lines.all { it.points.size >= 2 && it.width > 0f })
    }

    @Test
    fun `without a size, handwriting is about as tall as the question`() = runTest {
        val host = NotesHost(ready)
        val tools = box(host)
        tools.call("screenshot", args("{}")) // 500×1000 shot of a 1000×2000 screen: ×2
        tools.call("notes_suggest", args("""{"text":"x","around":[100,200,300,250]}"""))
        // 50 px tall question → letters 40 shot px = 80 screen px.
        assertEquals(80f, host.shown.single().size, 0.01f)
        tools.call("notes_suggest", args("""{"text":"x","at":[1,1],"size":5}"""))
        assertEquals(48f, host.shown.last().size, 0.01f)
    }

    @Test
    fun `the preview draws the exact strokes that get written, without wobble`() = runTest {
        val host = NotesHost(ready)
        val tools = box(host)
        tools.call("screenshot", args("{}"))
        tools.call("notes_suggest", args("""{"text":"z = 3","at":[10,10]}"""))
        val s = host.shown.single()
        val sketch = s.preview.last() as Annotation.Sketch
        assertEquals(s.ink!!.map { it.points }, sketch.contours)
        val rendered = Ink(2f).render(sketch)
        assertTrue(rendered.strokes.all { !it.halo })
        assertEquals(s.ink!!.filter { it.points.size >= 2 }.map { it.points }, rendered.strokes.map { it.points })
    }
}
