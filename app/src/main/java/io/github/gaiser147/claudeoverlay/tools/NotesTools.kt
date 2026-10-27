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
}

data class NotesStatus(
    /** The bridge in the clone is connected right now (the clone runs). */
    val connected: Boolean,
    /** A note editor is in front. */
    val editorOpen: Boolean = false,
    val noteId: String? = null,
    /** A clone asked to pair and waits for the user's confirmation. */
    val awaitingPairing: Boolean = false,
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
) {
    /** The handwritten text and the marks, as the drawing layer takes them. */
    val preview: List<Annotation>
        get() = marks + Annotation.Label(at, lines.joinToString("\n"), size, PREVIEW_COLOR)

    /** Bottom edge of the preview text (screen pixels), where the ✓/✗ buttons go. */
    val bottom: Float get() = at.y + lines.size * size * LINE_HEIGHT

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
internal class NotesTools(private val host: PhoneHost) {
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
        else -> null
    }

    private suspend fun suggest(args: JsonObject, g: ShotGeometry): ToolResult {
        val text = args.text()
        val size = (args.num("size") ?: DEFAULT_SIZE).let(g::toScreenLength)
        val around = args.box("around")?.let { (l, t, r, b) -> g.toScreen(l, t) to g.toScreen(r, b) }
        val at = args.point("at")?.let { (x, y) -> g.toScreen(x, y) }
            ?: around?.let { (tl, br) -> Pt(tl.x, br.y + size * 0.6f) }
            ?: throw NotesArgs("Give 'at' (where the text starts) or 'around' (the question's box) in screenshot pixels.")
        val (screenW, _) = host.screenSize()
        val maxWidth = args.num("max_width")?.let(g::toScreenLength) ?: (screenW - at.x - size)
        val marks = (args["marks"] as? JsonArray)?.let { AnnotationParser.parse(it, g) }.orEmpty()
        val suggestion = Suggestion(
            id = nextId++, text = text, at = at, size = size,
            lines = Suggestion.previewLines(text, size, maxWidth.coerceAtLeast(size * 8)),
            marks = marks,
        )
        host.showSuggestion(suggestion)
        val how = if (connected() != null) "inserts it into the open note (below its content)"
        else "would insert it, but Claude Notes isn't connected right now (${describe(host.notes()?.status())})"
        return ToolResult.text(
            "Suggestion #${suggestion.id} is shown over the page as a blue handwritten preview with ✓/✗ buttons. " +
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

        fun describe(status: NotesStatus?): String = when {
            status == null -> "Claude Notes isn't set up: the user needs the patched Samsung Notes clone with the " +
                "Claude bridge (scripts/notes-clone in the Claude Overlay repository), opened once and paired."
            status.awaitingPairing -> "Claude Notes asked to pair: the user must confirm it in the notification " +
                "\"Pair Claude Notes?\"."
            !status.connected -> "Claude Notes is paired but not running; it connects when the user opens a note in it."
            !status.editorOpen -> "Claude Notes is connected, but no note is open in its editor."
            else -> "Claude Notes is connected; the open note is ${status.noteId ?: "(not saved yet)"}."
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
                    "question, optionally with marks (highlight the question, arrows, checks), and ✓/✗ buttons. " +
                    "Only the user's ✓ puts the text into the note (as typed text below its content); marks stay " +
                    "previews. Prefer this to notes_insert. Returns at once; the user's choice arrives with their " +
                    "next message. Keep it short and note-like.",
                """{"type":"object","required":["text"],"properties":{$TEXT,
                  "around":{"type":"array","items":{"type":"number"},"minItems":4,"maxItems":4,"description":"[left, top, right, bottom] of the question on the page (screenshot pixels); the preview goes below it"},
                  "at":$POINT,
                  "size":{"type":"number","description":"letter height in screenshot pixels (default 26)"},
                  "max_width":{"type":"number","description":"wrap the preview at this width (screenshot pixels)"},
                  "marks":$shapes}}""",
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
