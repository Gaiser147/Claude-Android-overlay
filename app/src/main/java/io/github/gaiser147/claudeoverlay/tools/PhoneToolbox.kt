package io.github.gaiser147.claudeoverlay.tools

import io.github.gaiser147.claudeoverlay.capture.Shot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/** What the tools need from the phone; implemented by the overlay service. */
interface PhoneHost {
    /**
     * A fresh screenshot with the overlay hidden; also becomes the coordinate reference.
     * [withDrawings] keeps Claude's drawings in it, so it can check where they landed.
     */
    suspend fun screenshot(withDrawings: Boolean = false): Shot?
    /** Screen size in pixels, to map screenshot coordinates. */
    fun screenSize(): Pair<Int, Int>

    /** Shows the drawings; returns how long they take to finish appearing (ms). */
    suspend fun showAnnotations(annotations: List<Annotation>, clearPrevious: Boolean): Long
    suspend fun clearAnnotations()

    /** Null when Claude may operate the phone, else why not (in words for the model). */
    fun controlBlockedReason(): String?
    /**
     * Performs strokes one after another (finger or pen lifted in between). With
     * [preferStylus], as real pen input when that's set up. Returns how they were delivered,
     * or null if Android refused.
     */
    suspend fun gesture(strokes: List<Stroke>, preferStylus: Boolean = false): InputKind?
    /** Types into the focused field; returns a short description of what happened. */
    suspend fun typeText(text: String, submit: Boolean): String
    suspend fun pressKey(key: String): Boolean
    fun listApps(): List<Pair<String, String>>
    /** Opens the best match for [query] (label or package); returns what was opened, or null. */
    suspend fun openApp(query: String): String?
}

/** How a gesture reached the app: as a finger (accessibility) or as a pen (Shizuku). */
enum class InputKind { Finger, Stylus }

/**
 * Claude's tools for the phone, served over MCP: draw on the screen (always available) and
 * operate apps (only when the user switched phone control on). Every coordinate is in pixels
 * of the most recent screenshot, which is what the model can measure; every action answers
 * with a fresh screenshot so the model sees what it did, like computer use.
 */
class PhoneToolbox(
    private val host: PhoneHost,
    private val print: HersheyFont,
    private val cursive: HersheyFont,
    private val settleMs: Long = 700,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : Toolbox {
    @Volatile private var geometry: ShotGeometry? = null

    override fun definitions(): List<JsonObject> = DEFINITIONS

    override suspend fun call(name: String, arguments: JsonObject): ToolResult = try {
        when (name) {
            "screenshot" -> screenshotResult("Screenshot")
            "draw_annotations" -> draw(arguments)
            "clear_annotations" -> { host.clearAnnotations(); ToolResult.text("Cleared.") }
            "list_apps" -> control { listApps() }
            "open_app" -> control {
                val query = arguments.str("name")
                val opened = host.openApp(query)
                    ?: return@control ToolResult.text("No app matches '$query'. Use list_apps.", true)
                act("Opened $opened") {}
            }
            "tap" -> control { val p = point(arguments, "x", "y"); act("Tapped") { gestureOrFail(listOf(Stroke(listOf(p), TAP_MS))) } }
            "long_press" -> control {
                val p = point(arguments, "x", "y")
                act("Long-pressed") { gestureOrFail(listOf(Stroke(listOf(p), arguments.long("duration_ms") ?: LONG_PRESS_MS))) }
            }
            "swipe" -> control {
                val a = point(arguments, "x1", "y1")
                val b = point(arguments, "x2", "y2")
                act("Swiped") { gestureOrFail(listOf(Stroke(listOf(a, b), arguments.long("duration_ms") ?: SWIPE_MS))) }
            }
            "draw_strokes" -> control { drawStrokes(arguments) }
            "write_handwriting" -> control { handwriting(arguments) }
            "type_text" -> control {
                val said = host.typeText(arguments.str("text"), arguments.bool("submit") ?: false)
                act(said) {}
            }
            "press_key" -> control {
                val key = arguments.str("key")
                if (!host.pressKey(key)) ToolResult.text("Unknown key '$key'.", true) else act("Pressed $key") {}
            }
            else -> ToolResult.text("Unknown tool $name", true)
        }
    } catch (e: BadArgs) {
        ToolResult.text(e.message ?: "Bad arguments", true)
    } catch (e: AnnotationParser.Invalid) {
        ToolResult.text(e.message ?: "Bad shapes", true)
    }

    private suspend fun geometry(): ShotGeometry =
        geometry ?: run { screenshotResult("Screenshot"); geometry } ?: throw BadArgs("No screenshot available")

    private suspend fun screenshotResult(caption: String, withDrawings: Boolean = false): ToolResult {
        val shot = host.screenshot(withDrawings) ?: return ToolResult.text("Couldn't take a screenshot.", true)
        val (sw, sh) = host.screenSize()
        geometry = ShotGeometry(shot.width, shot.height, sw, sh)
        return ToolResult(
            listOf(
                ToolResult.textBlock("$caption, ${shot.width}×${shot.height} px (use these pixel coordinates)."),
                ToolResult.imageBlock(Base64.getEncoder().encodeToString(shot.attachment.bytes), shot.attachment.mimeType),
            )
        )
    }

    private suspend fun draw(args: JsonObject): ToolResult {
        val shapes = args["shapes"] as? JsonArray ?: throw BadArgs("'shapes' must be an array")
        val annotations = AnnotationParser.parse(shapes, geometry())
        val animationMs = host.showAnnotations(annotations, args.bool("clear_previous") ?: true)
        // Let the marks finish appearing, then show Claude the screen *with* them: every other
        // screenshot hides the drawings, so this is its only way to see where they landed.
        sleep(animationMs + SETTLE_AFTER_DRAW_MS)
        return screenshotResult(
            "Drew ${annotations.size} mark(s). This screenshot shows them on the user's screen — check that each " +
                "sits on its target; if one is off (e.g. the page scrolled), redraw with corrected coordinates " +
                "and clear_previous true. Screen",
            withDrawings = true,
        )
    }

    private inline fun control(block: () -> ToolResult): ToolResult =
        host.controlBlockedReason()?.let { ToolResult.text(it, true) } ?: block()

    private suspend fun act(what: String, action: suspend () -> Unit): ToolResult {
        action()
        sleep(settleMs)
        return screenshotResult("$what. Screen now")
    }

    private suspend fun gestureOrFail(strokes: List<Stroke>, preferStylus: Boolean = false): InputKind =
        host.gesture(strokes, preferStylus) ?: throw BadArgs("Android didn't perform the gesture.")

    /** Draws or writes with the pen when it's set up, and says which input the app got. */
    private suspend fun penAct(what: String, strokes: List<Stroke>): ToolResult {
        val kind = gestureOrFail(strokes, preferStylus = true)
        sleep(settleMs)
        return screenshotResult("$what ${if (kind == InputKind.Stylus) STYLUS_NOTE else FINGER_NOTE}. Screen now")
    }

    private fun listApps(): ToolResult = ToolResult.text(
        host.listApps().sortedBy { it.first.lowercase() }.joinToString("\n") { (label, pkg) -> "$label ($pkg)" }
    )

    private suspend fun drawStrokes(args: JsonObject): ToolResult {
        val g = geometry()
        val strokes = (args["strokes"] as? JsonArray ?: throw BadArgs("'strokes' must be [[[x, y], …], …]")).map { s ->
            val pts = (s as? JsonArray ?: throw BadArgs("each stroke must be [[x, y], …]")).map { p ->
                val a = p as? JsonArray
                val x = (a?.getOrNull(0) as? JsonPrimitive)?.floatOrNull
                val y = (a?.getOrNull(1) as? JsonPrimitive)?.floatOrNull
                if (x == null || y == null) throw BadArgs("points must be [x, y]")
                g.toScreen(x, y)
            }
            if (pts.isEmpty()) throw BadArgs("a stroke needs at least one point")
            Strokes.drawn(pts)
        }
        if (strokes.size > MAX_STROKES) throw BadArgs("at most $MAX_STROKES strokes per call")
        return penAct("Drew ${strokes.size} stroke(s)", strokes)
    }

    private suspend fun handwriting(args: JsonObject): ToolResult {
        val g = geometry()
        val text = args.str("text")
        val x = args.float("x") ?: throw BadArgs("'x' is required")
        val y = args.float("y") ?: throw BadArgs("'y' is required")
        val height = args.float("letter_height") ?: DEFAULT_LETTER_HEIGHT
        val maxWidth = args.float("max_width")
        val font = if (args.optStr("style") == "cursive") cursive else print
        // Lay out in screenshot space, then map every point, so wrapping matches what Claude sees.
        val strokes = font.layout(text, x, y, height, maxWidth).map { s ->
            Strokes.drawn(s.map { p -> g.toScreen(p.x, p.y) })
        }
        if (strokes.size > MAX_HANDWRITING_STROKES) throw BadArgs("text too long for one call; split it up")
        return penAct("Wrote \"${text.take(40)}\"", strokes)
    }

    private fun point(args: JsonObject, kx: String, ky: String): Pt {
        val g = geometry ?: throw BadArgs("Take a screenshot first, coordinates refer to it.")
        val x = args.float(kx) ?: throw BadArgs("'$kx' is required")
        val y = args.float(ky) ?: throw BadArgs("'$ky' is required")
        return g.toScreen(x, y)
    }

    private class BadArgs(message: String) : Exception(message)

    private fun JsonObject.str(k: String) = optStr(k) ?: throw BadArgs("'$k' is required")
    private fun JsonObject.optStr(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.float(k: String) = (this[k] as? JsonPrimitive)?.floatOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val TAP_MS = 60L
        const val LONG_PRESS_MS = 700L
        const val SWIPE_MS = 350L
        const val MAX_STROKES = 200
        const val MAX_HANDWRITING_STROKES = 600
        const val DEFAULT_LETTER_HEIGHT = 28f
        const val SETTLE_AFTER_DRAW_MS = 250L

        const val STYLUS_NOTE = "with the stylus (real pen input)"

        /**
         * Without Shizuku, apps can inject touches only as a finger, and note apps on pen
         * devices often pan with the finger instead of drawing.
         */
        const val FINGER_NOTE = "as finger touches (real pen input isn't set up). Check the screenshot: if no ink " +
            "appeared or the page only moved, the app draws with the stylus only. Then tell the user they can set up " +
            "real pen input in the Claude Overlay app (setup step 'Real stylus', via the Shizuku app), allow drawing " +
            "with the finger in the note app, or let you write with the app's text tool (keyboard icon) and type_text"

        private const val XY = """{"type":"number","description":"pixels in the latest screenshot"}"""
        private const val POINT = """{"type":"array","items":{"type":"number"},"minItems":2,"maxItems":2,"description":"[x, y] in screenshot pixels"}"""
        private const val COLOR = """{"type":"string","description":"red, orange, yellow, green, blue, purple, pink, black, gray or #RRGGBB (default: red-orange pen; highlights default to yellow)"}"""

        private fun tool(name: String, description: String, schema: String): JsonObject =
            Json.parseToJsonElement(
                """{"name":"$name","description":${JsonPrimitive(description)},"inputSchema":$schema}"""
            ).jsonObject

        private val DEFINITIONS = listOf(
            tool(
                "screenshot",
                "Take a fresh screenshot of the user's phone screen (the overlay hides itself). Coordinates for every other tool are pixels in the most recent screenshot.",
                """{"type":"object","properties":{}}""",
            ),
            tool(
                "draw_annotations",
                "Annotate the user's screen like a tutor with a pen: marks are rendered as natural handwriting " +
                    "(tapered strokes, curved arrows, hand-drawn circles, handwritten labels) on a layer that never " +
                    "blocks touches and stays until replaced or cleared. Use few, purposeful marks: circle or " +
                    "underline the thing you mean, an arrow plus a 1–4 word label, a check or cross when correcting, " +
                    "a highlight behind a phrase. Put labels in empty space, not over the content. For diagrams use " +
                    "'svg' path data and 'dot' for nodes. All coordinates are pixels of the latest screenshot; to mark " +
                    "something, give its bounding box as 'around' = [left, top, right, bottom] — the tool fits the " +
                    "circle, box, highlight or underline to it. The result is a screenshot WITH your marks: check them.",
                """{"type":"object","required":["shapes"],"properties":{
                  "clear_previous":{"type":"boolean","description":"remove earlier drawings first (default true)"},
                  "shapes":{"type":"array","items":{"type":"object","required":["type"],"properties":{
                    "type":{"type":"string","enum":["circle","box","underline","highlight","strike","arrow","check","cross","dot","text","path","svg"],
                      "description":"circle/box/underline/highlight/strike: around; arrow: from, to [, bend -1..1]; check/cross/dot: at [, size]; text: at (top-left of the text), text [, size, font]; circle may also use center + radius; path: points; svg: d [, x, y, scale]"},
                    "around":{"type":"array","items":{"type":"number"},"minItems":4,"maxItems":4,"description":"[left, top, right, bottom] of the thing to mark, in screenshot pixels"},
                    "center":$POINT,"radius":$XY,"at":$POINT,"from":$POINT,"to":$POINT,
                    "bend":{"type":"number","description":"arrow curvature, -1..1 (0 = straight)"},
                    "size":{"type":"number","description":"check/cross/dot size or text letter height, screenshot pixels"},
                    "wavy":{"type":"boolean","description":"wavy underline"},
                    "text":{"type":"string"},
                    "font":{"type":"string","enum":["handwriting","print"],"description":"text style (default handwriting)"},
                    "thickness":{"type":"number","description":"pen width: 0.5 fine … 1 normal … 2 marker … 3 bold"},
                    "points":{"type":"array","items":$POINT},
                    "d":{"type":"string","description":"SVG path data in screenshot pixels (M L H V C S Q T A Z)"},
                    "x":{"type":"number"},"y":{"type":"number"},"scale":{"type":"number"},
                    "label":{"type":"string","description":"short handwritten caption placed next to the mark"},
                    "color":$COLOR}}}}}""",
            ),
            tool("clear_annotations", "Remove everything drawn with draw_annotations.", """{"type":"object","properties":{}}"""),
            tool("list_apps", "List the launchable apps on the phone as 'Label (package)'. Requires phone control.", """{"type":"object","properties":{}}"""),
            tool(
                "open_app",
                "Open an app by its name (e.g. 'Samsung Notes') or package. Returns a screenshot afterwards. Requires phone control.",
                """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}""",
            ),
            tool("tap", "Tap the screen. Returns a screenshot afterwards. Requires phone control.", """{"type":"object","required":["x","y"],"properties":{"x":$XY,"y":$XY}}"""),
            tool(
                "long_press", "Press and hold. Returns a screenshot afterwards. Requires phone control.",
                """{"type":"object","required":["x","y"],"properties":{"x":$XY,"y":$XY,"duration_ms":{"type":"integer"}}}""",
            ),
            tool(
                "swipe", "Swipe/scroll from one point to another. Returns a screenshot afterwards. Requires phone control.",
                """{"type":"object","required":["x1","y1","x2","y2"],"properties":{"x1":$XY,"y1":$XY,"x2":$XY,"y2":$XY,"duration_ms":{"type":"integer"}}}""",
            ),
            tool(
                "draw_strokes",
                "Draw with a finger in the app on screen (e.g. on a Samsung Notes canvas), like a pen: each stroke is a polyline drawn in one movement, the finger lifts between strokes. Returns a screenshot afterwards. Requires phone control.",
                """{"type":"object","required":["strokes"],"properties":{"strokes":{"type":"array","items":{"type":"array","items":$POINT}}}}""",
            ),
            tool(
                "write_handwriting",
                "Write text by hand into the app on screen (e.g. a Samsung Notes page) as pen strokes, starting with its top-left at (x, y). Use when the app expects drawing rather than typing; for text fields use type_text. Returns a screenshot afterwards. Requires phone control.",
                """{"type":"object","required":["text","x","y"],"properties":{"text":{"type":"string"},"x":$XY,"y":$XY,
                  "letter_height":{"type":"number","description":"capital letter height in screenshot pixels (default 28)"},
                  "max_width":{"type":"number","description":"wrap lines at this width in screenshot pixels"},
                  "style":{"type":"string","enum":["print","cursive"]}}}""",
            ),
            tool(
                "type_text",
                "Type text into the focused text field (tap the field first). Returns a screenshot afterwards. Requires phone control.",
                """{"type":"object","required":["text"],"properties":{"text":{"type":"string"},"submit":{"type":"boolean","description":"press enter/search afterwards"}}}""",
            ),
            tool(
                "press_key", "Press a system button. Returns a screenshot afterwards. Requires phone control.",
                """{"type":"object","required":["key"],"properties":{"key":{"type":"string","enum":["back","home","recents","notifications","quick_settings"]}}}""",
            ),
        )
    }
}
