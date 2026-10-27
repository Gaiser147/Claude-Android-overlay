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
    suspend fun screenshot(withDrawings: Boolean = false, maxEdge: Int = PhoneToolbox.TOOL_EDGE): Shot?
    /** Screen size in pixels, to map screenshot coordinates. */
    fun screenSize(): Pair<Int, Int>
    /** The other apps' accessibility trees, or null when the accessibility service is off. */
    suspend fun readScreen(): ScreenSnapshot?

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
    /** Whether real pen input is available right now. */
    fun penState(): PenState
    /** Types into the focused field; returns a short description of what happened. */
    suspend fun typeText(text: String, submit: Boolean): String
    suspend fun pressKey(key: String): Boolean
    fun listApps(): List<Pair<String, String>>
    /** Opens the best match for [query] (label or package); returns what was opened, or null. */
    suspend fun openApp(query: String): String?

    /** The Claude Notes clone, or null while none was ever paired. */
    fun notes(): NotesAccess? = null
    /** Shows a suggestion's preview and its ✓/✗ buttons over the page. */
    suspend fun showSuggestion(suggestion: Suggestion) {}
}

/** How a gesture reached the app: as a finger (accessibility) or as a pen (Shizuku). */
enum class InputKind { Finger, Stylus }

/** Real pen input: [Ready], never set up, or set up but [Stopped] / missing [NoPermission]. */
enum class PenState { Ready, NotSetUp, Stopped, NoPermission }

/**
 * Claude's tools for the phone, served over MCP: draw on the screen (always available) and
 * operate apps (only when the user switched phone control on). Every coordinate is in pixels
 * of the most recent screenshot, which is what the model can measure. Actions answer with
 * the screen as text (numbered elements from the accessibility tree), which costs a fraction
 * of a screenshot; pictures come when asked for, when the screen has little text (a canvas,
 * a game), and after drawing, where the ink is what matters.
 */
class PhoneToolbox(
    private val host: PhoneHost,
    private val print: HersheyFont,
    private val cursive: HersheyFont,
    private val settleMs: Long = 400,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : Toolbox {
    @Volatile private var geometry: ShotGeometry? = null
    /** Where the elements of the last screen text are, by number (screen pixels). */
    @Volatile private var elements: List<Pt> = emptyList()
    /** The pen worked earlier: the app on screen likely draws only with it. */
    @Volatile private var penWorked = false
    private val notes = NotesTools(host, cursive)

    override fun definitions(): List<JsonObject> = DEFINITIONS

    /** A screenshot of this size went out with a message: coordinates now refer to it. */
    fun noteScreenshot(width: Int, height: Int) {
        val (sw, sh) = host.screenSize()
        geometry = ShotGeometry(width, height, sw, sh)
    }

    override suspend fun call(name: String, arguments: JsonObject): ToolResult = try {
        when (name) {
            "screenshot" -> screenshotResult("Screenshot", maxEdge = edge(arguments))
            "read_screen" -> { settle(arguments, acted = false); screenResult("Screen", arguments) }
            "draw_annotations" -> draw(arguments)
            "clear_annotations" -> { host.clearAnnotations(); ToolResult.text("Cleared.") }
            "list_apps" -> control { listApps() }
            "open_app" -> control {
                val query = arguments.str("name")
                val opened = host.openApp(query)
                    ?: return@control ToolResult.text("No app matches '$query'. Use list_apps.", true)
                act("Opened $opened", arguments) {}
            }
            "tap" -> control { val p = target(arguments); act("Tapped", arguments) { gestureOrFail(listOf(Stroke(listOf(p), TAP_MS))) } }
            "long_press" -> control {
                val p = target(arguments)
                act("Long-pressed", arguments) { gestureOrFail(listOf(Stroke(listOf(p), arguments.long("duration_ms") ?: LONG_PRESS_MS))) }
            }
            "swipe" -> control {
                val a = point(arguments, "x1", "y1")
                val b = point(arguments, "x2", "y2")
                act("Swiped", arguments) { gestureOrFail(listOf(Stroke(listOf(a, b), arguments.long("duration_ms") ?: SWIPE_MS))) }
            }
            "draw_strokes" -> control { drawStrokes(arguments) }
            "write_handwriting" -> control { handwriting(arguments) }
            "type_text" -> control {
                val text = arguments.str("text")
                // Tap the field first when one is named: one call instead of tap, look, type.
                if (arguments.containsKey("element")) {
                    gestureOrFail(listOf(Stroke(listOf(target(arguments)), TAP_MS)))
                    sleep(FOCUS_MS)
                }
                val said = host.typeText(text, arguments.bool("submit") ?: false)
                act(said, arguments) {}
            }
            "press_key" -> control {
                val key = arguments.str("key")
                if (!host.pressKey(key)) ToolResult.text("Unknown key '$key'.", true) else act("Pressed $key", arguments) {}
            }
            else -> notes.call(name, arguments, ::geometry) ?: ToolResult.text("Unknown tool $name", true)
        }
    } catch (e: NotesTools.NotesArgs) {
        ToolResult.text(e.message ?: "Bad arguments", true)
    } catch (e: BadArgs) {
        ToolResult.text(e.message ?: "Bad arguments", true)
    } catch (e: AnnotationParser.Invalid) {
        ToolResult.text(e.message ?: "Bad shapes", true)
    }

    private suspend fun geometry(): ShotGeometry =
        geometry ?: run { screenshotResult("Screenshot"); geometry } ?: throw BadArgs("No screenshot available")

    /** The coordinate space for screen text: the latest screenshot's, or a tool screenshot's size. */
    private fun textGeometry(): ShotGeometry = geometry ?: run {
        val (sw, sh) = host.screenSize()
        ShotGeometry.forScreen(sw, sh, TOOL_EDGE).also { geometry = it }
    }

    private fun edge(args: JsonObject) = when (args.optStr("detail")) {
        "low" -> LOW_EDGE
        "high" -> HIGH_EDGE
        else -> TOOL_EDGE
    }

    /** An element number from the last screen text, or x/y in screenshot pixels. */
    private fun target(args: JsonObject): Pt {
        val id = args.long("element") ?: return point(args, "x", "y")
        if (elements.isEmpty()) throw BadArgs("No element numbers yet: call read_screen first.")
        return elements.getOrNull(id.toInt() - 1)
            ?: throw BadArgs("No element [$id] on the last screen (1–${elements.size}); call read_screen again.")
    }

    /**
     * Waits until the screen has settled after an action: optionally until [wait_for] text
     * shows up, then until two readings a moment apart agree (a page done loading), within
     * limits, instead of a fixed guess.
     */
    private suspend fun settle(args: JsonObject, acted: Boolean = true) {
        if (acted) sleep(settleMs)
        args.optStr("wait_for")?.takeIf { it.isNotBlank() }?.let { want ->
            val limit = ((args.float("timeout_s") ?: DEFAULT_WAIT_S).coerceIn(0f, MAX_WAIT_S) * 1000).toLong()
            var waited = 0L
            while (waited < limit) {
                val now = host.readScreen() ?: break
                if (ScreenText.elements(now).any { it.label.contains(want, ignoreCase = true) }) break
                sleep(POLL_MS)
                waited += POLL_MS
            }
        }
        if (!acted) return
        var previous = host.readScreen()?.let(ScreenText::elements) ?: return
        var waited = 0L
        while (waited < STABLE_MAX_MS) {
            sleep(POLL_MS)
            waited += POLL_MS
            val now = host.readScreen()?.let(ScreenText::elements) ?: return
            if (now == previous) return
            previous = now
        }
    }

    /**
     * The screen as numbered text lines; with a screenshot too when asked for
     * (`screenshot: true`) or when there's too little text to work from.
     */
    private suspend fun screenResult(caption: String, args: JsonObject): ToolResult {
        val snapshot = host.readScreen() ?: return screenshotResult("$caption (screen text unavailable)", maxEdge = edge(args))
        val found = ScreenText.elements(snapshot)
        val g = textGeometry()
        elements = found.take(ScreenText.MAX_ELEMENTS).map { it.center }
        val text = "$caption:\n" + ScreenText.render(snapshot, found, g)
        if (args.bool("screenshot") != true && !ScreenText.isSparse(found)) return ToolResult.text(text)
        val shot = screenshotResult("Screenshot", maxEdge = edge(args))
        // The picture may have a different size than the space the text used: renumber in it.
        return if (shot.isError) ToolResult.text(text) else ToolResult(
            listOf(ToolResult.textBlock("$caption:\n" + ScreenText.render(snapshot, found, geometry ?: g))) + shot.content
        )
    }

    private suspend fun screenshotResult(
        caption: String,
        withDrawings: Boolean = false,
        maxEdge: Int = TOOL_EDGE,
    ): ToolResult {
        val shot = host.screenshot(withDrawings, maxEdge) ?: return ToolResult.text("Couldn't take a screenshot.", true)
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

    private suspend fun act(what: String, args: JsonObject, action: suspend () -> Unit): ToolResult {
        action()
        settle(args)
        return screenResult("$what. Screen now", args)
    }

    private suspend fun gestureOrFail(strokes: List<Stroke>, preferStylus: Boolean = false): InputKind =
        host.gesture(strokes, preferStylus) ?: throw BadArgs("Android didn't perform the gesture.")

    /** Draws or writes with the pen when it's set up, and says which input the app got. */
    private suspend fun penAct(what: String, strokes: List<Stroke>): ToolResult {
        val pen = host.penState()
        // Once the pen has worked, finger touches would only pan a pen-only canvas: stop instead.
        if (penWorked && pen != PenState.Ready) return ToolResult.text("Nothing was drawn: ${penLost(pen)}", true)
        val kind = gestureOrFail(strokes, preferStylus = true)
        if (kind == InputKind.Stylus) penWorked = true
        sleep(settleMs)
        val note = if (kind == InputKind.Stylus) STYLUS_NOTE else fingerNote(pen)
        return screenshotResult("$what ${strokes.size.strokes()} $note. Screen now")
    }

    private fun Int.strokes() = if (this == 1) "as 1 stroke" else "as $this strokes"

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
        return penAct("Drew", strokes)
    }

    private suspend fun handwriting(args: JsonObject): ToolResult {
        val g = geometry()
        val text = args.str("text")
        val x = args.float("x") ?: throw BadArgs("'x' is required")
        val y = args.float("y") ?: throw BadArgs("'y' is required")
        val height = args.float("letter_height") ?: DEFAULT_LETTER_HEIGHT
        val maxWidth = args.float("max_width")
        val font = if (args.optStr("style") == "cursive") cursive else print
        font.missing(text).takeIf { it.isNotEmpty() }?.let { chars ->
            throw BadArgs("Nothing was written: the handwriting font has no ${chars.joinToString(" ")}. " +
                "Spell it differently or draw it with draw_strokes.")
        }
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
        /** Long edge of tool screenshots: ~1000 tokens on a tablet instead of ~2000 at full size. */
        const val TOOL_EDGE = 1092
        const val LOW_EDGE = 768
        const val HIGH_EDGE = 1568
        const val FOCUS_MS = 400L
        const val POLL_MS = 300L
        const val STABLE_MAX_MS = 2_400L
        const val DEFAULT_WAIT_S = 8f
        const val MAX_WAIT_S = 20f

        const val STYLUS_NOTE = "with the stylus (real pen input)"

        /** Why the pen, once set up, can't draw now — in words the model can pass on. */
        fun penLost(pen: PenState) = when (pen) {
            PenState.NoPermission -> "real pen input lost its Shizuku permission. Ask the user to allow it again " +
                "in the Claude Overlay app (setup step 'Real stylus')."
            else -> "real pen input stopped: the Shizuku app isn't running (it stops when the device restarts, " +
                "and on some devices when wireless debugging turns off). Ask the user to open Shizuku and start it " +
                "again, then try once more."
        }

        /**
         * Without Shizuku, apps can inject touches only as a finger, and note apps on pen
         * devices often pan with the finger instead of drawing.
         */
        fun fingerNote(pen: PenState) = "as finger touches (" +
            (if (pen == PenState.NotSetUp) "real pen input isn't set up" else penLost(pen)) +
            "). Check the screenshot: if no ink appeared or the page only moved, the app draws with the stylus only. " +
            "Then tell the user they can set up real pen input in the Claude Overlay app (setup step 'Real stylus', " +
            "via the Shizuku app), allow drawing with the finger in the note app, or let you write with the app's " +
            "text tool (keyboard icon) and type_text"

        /** How pen apps treat what Claude draws, so it can correct mistakes without collateral damage. */
        private const val PEN_APP_NOTE = " In note apps every stroke is its own undo step (the result says how many " +
            "strokes were drawn), and the stroke eraser removes whole strokes, so undo right after a mistake rather " +
            "than erasing near other ink."

        private const val XY = """{"type":"number","description":"pixels in the latest screenshot"}"""
        private const val ELEMENT = """{"type":"integer","description":"element number from the latest screen text (read_screen or an action's result); instead of x/y"}"""
        private const val DETAIL = """{"type":"string","enum":["low","normal","high"],"description":"screenshot resolution: low (~500 tokens) for layout, normal, high (~2000 tokens) to read small text"}"""
        /** What every action takes besides its own arguments. */
        private const val AFTER = """"wait_for":{"type":"string","description":"wait until an element containing this text appears (e.g. a page loading)"},
                  "timeout_s":{"type":"number","description":"how long wait_for may wait (default 8, max 20)"},
                  "screenshot":{"type":"boolean","description":"also return a screenshot (costs ~1000 tokens; only when the text isn't enough)"},
                  "detail":$DETAIL"""
        private const val RETURNS_TEXT = "Returns the screen as text afterwards (numbered elements; a screenshot only when asked for or when the screen has little text)."
        private const val POINT = """{"type":"array","items":{"type":"number"},"minItems":2,"maxItems":2,"description":"[x, y] in screenshot pixels"}"""
        private const val COLOR = """{"type":"string","description":"red, orange, yellow, green, blue, purple, pink, black, gray or #RRGGBB (default: red-orange pen; highlights default to yellow)"}"""

        /** draw_annotations' shapes; notes_suggest takes the same for its marks. */
        private const val SHAPES = """{"type":"array","items":{"type":"object","required":["type"],"properties":{
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
                    "color":$COLOR}}}"""

        private fun tool(name: String, description: String, schema: String): JsonObject =
            Json.parseToJsonElement(
                """{"name":"$name","description":${JsonPrimitive(description)},"inputSchema":$schema}"""
            ).jsonObject

        private val DEFINITIONS = NotesTools.definitions(::tool, SHAPES) + listOf(
            tool(
                "read_screen",
                "Read the user's screen as text from the accessibility tree: the app, then one line per element — " +
                    "[number] kind \"label\" @x,y and state (focused, on/off, under keyboard). Costs a few hundred " +
                    "tokens where a screenshot costs 1000–2000, and gives exact targets: tap or type into an element by " +
                    "its number. Prefer it to screenshot for finding and operating things; take a screenshot for " +
                    "pictures, layout, handwriting, canvases or when the text is missing something.",
                """{"type":"object","properties":{$AFTER}}""",
            ),
            tool(
                "screenshot",
                "Take a fresh screenshot of the user's phone screen (the overlay hides itself). Coordinates for every other tool are pixels in the most recent screenshot. Use detail 'low' when layout is enough.",
                """{"type":"object","properties":{"detail":$DETAIL}}""",
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
                  "shapes":$SHAPES}}""",
            ),
            tool("clear_annotations", "Remove everything drawn with draw_annotations.", """{"type":"object","properties":{}}"""),
            tool("list_apps", "List the launchable apps on the phone as 'Label (package)'. Requires phone control.", """{"type":"object","properties":{}}"""),
            tool(
                "open_app",
                "Open an app by its name (e.g. 'Samsung Notes') or package. $RETURNS_TEXT Requires phone control.",
                """{"type":"object","required":["name"],"properties":{"name":{"type":"string"},$AFTER}}""",
            ),
            tool(
                "tap", "Tap an element (by number) or a point. $RETURNS_TEXT Requires phone control.",
                """{"type":"object","properties":{"element":$ELEMENT,"x":$XY,"y":$XY,$AFTER}}""",
            ),
            tool(
                "long_press", "Press and hold an element (by number) or a point. $RETURNS_TEXT Requires phone control.",
                """{"type":"object","properties":{"element":$ELEMENT,"x":$XY,"y":$XY,"duration_ms":{"type":"integer"},$AFTER}}""",
            ),
            tool(
                "swipe", "Swipe/scroll from one point to another. $RETURNS_TEXT Requires phone control.",
                """{"type":"object","required":["x1","y1","x2","y2"],"properties":{"x1":$XY,"y1":$XY,"x2":$XY,"y2":$XY,"duration_ms":{"type":"integer"},$AFTER}}""",
            ),
            tool(
                "draw_strokes",
                "Draw in the app on screen (e.g. on a Samsung Notes canvas) like a pen — with the real stylus when that's set up, else with a finger: each stroke is a polyline drawn in one movement, the pen lifts between strokes. Draw long curves as a few strokes so a part can be fixed alone. Returns a screenshot afterwards. Requires phone control." + PEN_APP_NOTE,
                """{"type":"object","required":["strokes"],"properties":{"strokes":{"type":"array","items":{"type":"array","items":$POINT}}}}""",
            ),
            tool(
                "write_handwriting",
                "Write text by hand into the app on screen (e.g. a Samsung Notes page) as pen strokes, starting with its top-left at (x, y). Use when the app expects drawing rather than typing; for text fields use type_text. Besides Latin letters with accents, it writes Greek letters (π, α, Δ…), math symbols (± × ÷ · − ≠ ≈ ≤ ≥ √ ∫ ∂ ∞ ° → ∈ ∩ ∪ ⊥ ∠) and superscripts/subscripts (x², a₁); it refuses text with other characters before drawing anything. Returns a screenshot afterwards. Requires phone control." + PEN_APP_NOTE,
                """{"type":"object","required":["text","x","y"],"properties":{"text":{"type":"string"},"x":$XY,"y":$XY,
                  "letter_height":{"type":"number","description":"capital letter height in screenshot pixels (default 28)"},
                  "max_width":{"type":"number","description":"wrap lines at this width in screenshot pixels"},
                  "style":{"type":"string","enum":["print","cursive"]}}}""",
            ),
            tool(
                "type_text",
                "Type text into a text field: give its element number to tap it first, or type into the focused field. With submit it presses enter/search too — field, text and search in one call. $RETURNS_TEXT Requires phone control.",
                """{"type":"object","required":["text"],"properties":{"text":{"type":"string"},"element":$ELEMENT,"submit":{"type":"boolean","description":"press enter/search afterwards"},$AFTER}}""",
            ),
            tool(
                "press_key", "Press a system button. $RETURNS_TEXT Requires phone control.",
                """{"type":"object","required":["key"],"properties":{"key":{"type":"string","enum":["back","home","recents","notifications","quick_settings"]},$AFTER}}""",
            ),
        )
    }
}
