package io.github.gaiser147.claudeoverlay.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/**
 * The Claude Notes clone (a patched Samsung Notes with the bridge from :notesbridge) as the
 * tools see it. Implemented by the notes link; null in [PhoneHost.notes] while no clone has
 * ever been paired.
 */
interface NotesAccess {
    fun status(): NotesStatus
    /** Adds [text] (Markdown) below the open note's content, live; returns an error or null. */
    suspend fun insert(text: String): String?
    /** The open note's typed text as last saved, or an error. */
    suspend fun read(): Result<NoteText>
    /** Writes pen strokes into the open note (one undo step); returns an error or null. */
    suspend fun addStrokes(strokes: List<InkLine>): String? = "This Claude Notes bridge can't write strokes; rebuild the clone."
    /** The strokes the user drew since the last call, in screen pixels. */
    suspend fun newStrokes(): Result<List<PenStroke>> = Result.failure(UnsupportedOperationException("old bridge"))
}

/** A stroke to write into the note (screen pixels; [width] too). */
data class InkLine(val points: List<Pt>, val color: Int, val width: Float, val marker: Boolean = false)

/** A stroke the user drew: points in screen pixels with their times (ms). */
data class PenStroke(val id: Int, val points: List<Pt>, val times: List<Long>)

/**
 * Handwriting as real pen strokes for the note: text laid out in the Hershey script font, and
 * Claude's marks (arrows, circles, highlights) as the ink lines they're drawn with.
 */
object NotesInk {
    /** Strokes for [lines] starting at [at] with letters [size] tall, or null if the font lacks a character. */
    fun text(font: HersheyFont, lines: List<String>, at: Pt, size: Float, color: Int): List<InkLine>? {
        val joined = lines.joinToString("\n")
        if (font.missing(joined).isNotEmpty()) return null
        val width = (size * PEN_WIDTH).coerceAtLeast(1.5f)
        return font.layout(joined, at.x, at.y, size * CAP_HEIGHT).filter { it.size >= 2 }
            .map { InkLine(it, color, width) }
    }

    /** The strokes of rendered marks; their labels written in [font]. */
    fun marks(marks: List<InkMark>, font: HersheyFont): List<InkLine> = marks.flatMap { mark ->
        mark.strokes.map { s ->
            InkLine(s.points, (s.color and 0x00FFFFFF) or 0xFF000000.toInt(), s.widths.average().toFloat(), s.marker)
        } + mark.texts.flatMap { t ->
            text(font, listOf(t.text), t.at, t.size, t.color).orEmpty()
        }
    }

    /** Capital height relative to the preview's letter size, so handwriting matches it. */
    const val CAP_HEIGHT = 0.62f
    const val PEN_WIDTH = 0.085f
}

data class NotesStatus(
    /** The bridge in the clone is connected right now (the clone runs). */
    val connected: Boolean,
    /** A note editor is in front. */
    val editorOpen: Boolean = false,
    val noteId: String? = null,
    /** A clone asked to pair and waits for the user's confirmation. */
    val awaitingPairing: Boolean = false,
    /** The bridge reads and writes pen strokes (protocol 2). */
    val ink: Boolean = false,
)

data class NoteText(val noteId: String, val title: String, val text: String)

/**
 * Text Claude proposes for the note, shown over it first: [preview] is drawn on the screen
 * (handwriting at [at], plus any marks), and only the user's ✓ inserts [text] into the note.
 */
data class Suggestion(
    val id: Int,
    /** What goes into the note on ✓ (Markdown). */
    val text: String,
    /** Where the preview text starts (screen pixels, top-left). */
    val at: Pt,
    /** Letter height of the preview, screen pixels. */
    val size: Float,
    /** The preview text, wrapped into lines. */
    val lines: List<String>,
    /** Marks that go with it (a highlight on the question, an arrow…), screen pixels. */
    val marks: List<Annotation>,
    /** The text as pen strokes, what ✓ writes into the note; null if the font can't write it. */
    val ink: List<InkLine>? = null,
) {
    /** The handwritten text and the marks, as the drawing layer takes them. */
    val preview: List<Annotation>
        get() = marks + (ink?.let { Annotation.Sketch(it.map(InkLine::points), PREVIEW_COLOR, null, 0.6f) }
            ?: Annotation.Label(at, lines.joinToString("\n"), size, PREVIEW_COLOR))

    /** Bottom edge of the preview text (screen pixels), where the ✓/✗ buttons go. */
    val bottom: Float
        get() = ink?.maxOfOrNull { l -> l.points.maxOf { it.y } }?.plus(size * 0.4f) ?: (at.y + lines.size * size * LINE_HEIGHT)

    companion object {
        /** Claude's preview ink: a clear blue, distinct from the user's pens and the red tutor marks. */
        const val PREVIEW_COLOR = 0xFF2F6FDF.toInt()
        const val LINE_HEIGHT = 1.35f
        /** Average handwritten letter width relative to the letter height (Caveat). */
        const val CHAR_WIDTH = 0.42f

        /**
         * Markdown as it reads on paper: no #, ** or backticks; list dashes become bullets.
         * Lines wrap at [maxWidth] (screen pixels) for letters of height [size].
         */
        fun previewLines(markdown: String, size: Float, maxWidth: Float): List<String> {
            val perLine = (maxWidth / (size * CHAR_WIDTH)).toInt().coerceAtLeast(MIN_CHARS)
            return markdown.trim().lines().flatMap { raw ->
                val line = raw.trimEnd()
                    .replace(Regex("^\\s*#{1,6}\\s+"), "")
                    .replace(Regex("^(\\s*)[-*+]\\s+"), "$1• ")
                    .replace("**", "").replace("__", "").replace("`", "")
                wrap(line, perLine)
            }
        }

        private fun wrap(line: String, perLine: Int): List<String> {
            if (line.length <= perLine) return listOf(line)
            val out = mutableListOf<String>()
            var current = StringBuilder()
            for (word in line.split(' ')) {
                if (current.isNotEmpty() && current.length + 1 + word.length > perLine) {
                    out += current.toString()
                    current = StringBuilder()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }

        private const val MIN_CHARS = 12
    }
}

/**
 * The notes_* tools: write into the clone's open note (after the user's ✓, or directly),
 * read it, and check the connection. Part of [PhoneToolbox].
 */
internal class NotesTools(private val host: PhoneHost, private val font: HersheyFont) {
    private var nextId = 1

    suspend fun call(name: String, args: JsonObject, geometry: suspend () -> ShotGeometry): ToolResult? = when (name) {
        "notes_status" -> ToolResult.text(describe(host.notes()?.status()))
        "notes_read" -> {
            val notes = connected() ?: return notConnected()
            notes.read().fold(
                onSuccess = { ToolResult.text("Note \"${it.title}\" (${it.noteId}), typed text as last saved:\n${it.text.ifBlank { "(no typed text)" }}") },
                onFailure = { ToolResult.text(it.message ?: "Couldn't read the note.", true) },
            )
        }
        "notes_insert" -> {
            val notes = connected() ?: return notConnected()
            val text = args.text()
            notes.insert(text)?.let { ToolResult.text(it, true) }
                ?: ToolResult.text("Inserted into the open note (below its content).")
        }
        "notes_suggest" -> suggest(args, geometry())
        "notes_write" -> write(args, geometry())
        else -> null
    }

    /** Handwriting straight into the note, without a preview. */
    private suspend fun write(args: JsonObject, g: ShotGeometry): ToolResult {
        val notes = connected() ?: return notConnected()
        if (!notes.status().ink) return ToolResult.text("This Claude Notes bridge can't write by hand yet; rebuild the clone with the current bridge. Use notes_insert for typed text meanwhile.", true)
        val text = args.text()
        val (at, size, maxWidth) = placement(args, g)
        val lines = Suggestion.previewLines(text, size, maxWidth)
        val ink = NotesInk.text(font, lines, at, size, args.color() ?: INK_COLOR)
            ?: return ToolResult.text("The handwriting font can't write ${font.missing(text).joinToString(" ")}; spell it differently or use notes_insert.", true)
        return notes.addStrokes(ink)?.let { ToolResult.text(it, true) }
            ?: ToolResult.text("Wrote ${lines.size} line(s) by hand into the note (${ink.size} strokes, one undo step).")
    }

    /** Where text goes ('at', or below 'around'), its letter height and wrap width, in screen pixels. */
    private fun placement(args: JsonObject, g: ShotGeometry): Triple<Pt, Float, Float> {
        val size = (args.num("size") ?: DEFAULT_SIZE).let(g::toScreenLength)
        val around = args.box("around")?.let { (l, t, r, b) -> g.toScreen(l, t) to g.toScreen(r, b) }
        val at = args.point("at")?.let { (x, y) -> g.toScreen(x, y) }
            ?: around?.let { (tl, br) -> Pt(tl.x, br.y + size * 0.6f) }
            ?: throw NotesArgs("Give 'at' (where the text starts) or 'around' (the question's box) in screenshot pixels.")
        val (screenW, _) = host.screenSize()
        val maxWidth = args.num("max_width")?.let(g::toScreenLength) ?: (screenW - at.x - size)
        return Triple(at, size, maxWidth.coerceAtLeast(size * 8))
    }

    private fun JsonObject.color(): Int? =
        (this["color"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { AnnotationParser.parseColor(it) }

    private suspend fun suggest(args: JsonObject, g: ShotGeometry): ToolResult {
        val text = args.text()
        val (at, size, maxWidth) = placement(args, g)
        val marks = (args["marks"] as? JsonArray)?.let { AnnotationParser.parse(it, g) }.orEmpty()
        val lines = Suggestion.previewLines(text, size, maxWidth)
        val suggestion = Suggestion(
            id = nextId++, text = text, at = at, size = size, lines = lines, marks = marks,
            ink = NotesInk.text(font, lines, at, size, INK_COLOR),
        )
        host.showSuggestion(suggestion)
        val status = connected()?.status()
        val how = when {
            status == null -> "would write it, but Claude Notes isn't connected right now (${describe(host.notes()?.status())})"
            status.ink && suggestion.ink != null -> "writes it by hand into the note right there; 'Aa' inserts it as typed text below the content"
            else -> "inserts it as typed text below the note's content"
        }
        return ToolResult.text(
            "Suggestion #${suggestion.id} is shown over the page as a blue handwritten preview with ✓/Aa/✗ buttons. " +
                "The user's ✓ $how; ✗ discards it. You'll be told which with their next message — don't wait for it."
        )
    }

    private fun connected(): NotesAccess? = host.notes()?.takeIf { it.status().connected }

    private fun notConnected() = ToolResult.text(describe(host.notes()?.status()), true)

    private fun JsonObject.text(): String =
        (this["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw NotesArgs("'text' is required")

    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.floatOrNull

    private fun JsonObject.point(k: String): List<Float>? = floats(k, 2)

    private fun JsonObject.box(k: String): List<Float>? = floats(k, 4)

    private fun JsonObject.floats(k: String, n: Int): List<Float>? {
        val a = this[k] as? JsonArray ?: return null
        val v = a.map { (it as? JsonPrimitive)?.floatOrNull ?: throw NotesArgs("'$k' must be $n numbers") }
        if (v.size != n) throw NotesArgs("'$k' must be $n numbers")
        return v
    }

    class NotesArgs(message: String) : Exception(message)

    companion object {
        const val DEFAULT_SIZE = 26f
        /** Claude's handwriting in the note: the preview's blue. */
        val INK_COLOR = Suggestion.PREVIEW_COLOR

        fun describe(status: NotesStatus?): String = when {
            status == null -> "Claude Notes isn't set up: the user needs the patched Samsung Notes clone with the " +
                "Claude bridge (scripts/notes-clone in the Claude Overlay repository), opened once and paired."
            status.awaitingPairing -> "Claude Notes asked to pair: the user must confirm it in the notification " +
                "\"Pair Claude Notes?\"."
            !status.connected -> "Claude Notes is paired but not running; it connects when the user opens a note in it."
            !status.editorOpen -> "Claude Notes is connected, but no note is open in its editor."
            else -> "Claude Notes is connected; the open note is ${status.noteId ?: "(not saved yet)"}." +
                if (status.ink) " You can write into it by hand (notes_write, or ✓ on a suggestion)." else " This bridge only inserts typed text."
        }

        private const val TEXT = """"text":{"type":"string","description":"Markdown: headings, lists, bold become Notes formatting"}"""
        private const val POINT = """{"type":"array","items":{"type":"number"},"minItems":2,"maxItems":2,"description":"[x, y] in screenshot pixels"}"""

        fun definitions(tool: (String, String, String) -> JsonObject, shapes: String): List<JsonObject> = listOf(
            tool(
                "notes_status",
                "Check the link to Claude Notes (the user's Samsung Notes clone with the Claude bridge): paired, " +
                    "running, which note is open.",
                """{"type":"object","properties":{}}""",
            ),
            tool(
                "notes_suggest",
                "Answer on the user's page in Claude Notes: shows your text as a blue handwritten preview at the " +
                    "question, optionally with marks (highlight the question, arrows, checks), and ✓/Aa/✗ buttons. " +
                    "Only the user's choice changes the note: ✓ writes your text and marks there by hand (real pen " +
                    "strokes), Aa inserts the text as typed text below the content. Prefer this to notes_write and " +
                    "notes_insert. Returns at once; the user's choice arrives with their next message. Keep it short " +
                    "and note-like; the handwriting font writes Latin letters, digits, Greek and common math symbols.",
                """{"type":"object","required":["text"],"properties":{$TEXT,
                  "around":{"type":"array","items":{"type":"number"},"minItems":4,"maxItems":4,"description":"[left, top, right, bottom] of the question on the page (screenshot pixels); the preview goes below it"},
                  "at":$POINT,
                  "size":{"type":"number","description":"letter height in screenshot pixels (default 26)"},
                  "max_width":{"type":"number","description":"wrap the preview at this width (screenshot pixels)"},
                  "marks":$shapes}}""",
            ),
            tool(
                "notes_write",
                "Write text by hand straight into the open Claude Notes note, as real pen strokes at a place on the " +
                    "page (one undo step). Only when the user asked you to write into the note; otherwise use " +
                    "notes_suggest.",
                """{"type":"object","required":["text"],"properties":{"text":{"type":"string"},
                  "around":{"type":"array","items":{"type":"number"},"minItems":4,"maxItems":4,"description":"[left, top, right, bottom] of what to write below (screenshot pixels)"},
                  "at":$POINT,
                  "size":{"type":"number","description":"letter height in screenshot pixels (default 26)"},
                  "max_width":{"type":"number","description":"wrap at this width (screenshot pixels)"},
                  "color":{"type":"string","description":"blue (default), black, red, green or #RRGGBB"}}}""",
            ),
            tool(
                "notes_insert",
                "Insert text into the open note in Claude Notes right away, live in the editor, below its content " +
                    "(not at the cursor). Only when the user asked you to write into the note directly; otherwise " +
                    "use notes_suggest.",
                """{"type":"object","required":["text"],"properties":{$TEXT}}""",
            ),
            tool(
                "notes_read",
                "Read the open Claude Notes note's title and typed text, as last saved. Handwriting isn't included: " +
                    "look at a screenshot for that.",
                """{"type":"object","properties":{}}""",
            ),
        )
    }
}
