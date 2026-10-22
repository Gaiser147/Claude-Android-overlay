package io.github.gaiser147.claudeoverlay.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Something Claude draws over the screen, in screen pixels. The kinds are the marks a tutor
 * makes with a pen on a worksheet, not general vector graphics; how they look is up to [Ink].
 */
sealed interface Annotation {
    /** ARGB; null = the kind's default ink. */
    val color: Int?
    val label: String?
    /** Pen width relative to the default (1 = a fineliner, 2 = a marker). */
    val thickness: Float

    data class Arrow(val from: Pt, val to: Pt, val bend: Float?, override val color: Int?, override val label: String?, override val thickness: Float = 1f) : Annotation
    data class Box(val topLeft: Pt, val bottomRight: Pt, override val color: Int?, override val label: String?, override val thickness: Float = 1f) : Annotation
    data class Ellipse(val center: Pt, val rx: Float, val ry: Float, override val color: Int?, override val label: String?, override val thickness: Float = 1f) : Annotation
    data class Highlight(val topLeft: Pt, val bottomRight: Pt, override val color: Int?, override val label: String? = null, override val thickness: Float = 1f) : Annotation
    data class Underline(val from: Pt, val to: Pt, val wavy: Boolean, override val color: Int?, override val label: String?, override val thickness: Float = 1f) : Annotation
    data class Strike(val from: Pt, val to: Pt, override val color: Int?, override val label: String? = null, override val thickness: Float = 1f) : Annotation
    data class Check(val center: Pt, val size: Float, override val color: Int?, override val label: String?, override val thickness: Float = 1f) : Annotation
    data class Cross(val center: Pt, val size: Float, override val color: Int?, override val label: String?, override val thickness: Float = 1f) : Annotation
    data class Dot(val at: Pt, override val color: Int?, override val label: String?, override val thickness: Float = 1f) : Annotation
    data class Label(
        val at: Pt,
        val text: String,
        val size: Float?,
        override val color: Int?,
        override val thickness: Float = 1f,
        /** Plain print instead of handwriting. */
        val print: Boolean = false,
    ) : Annotation {
        override val label: String? get() = null
    }
    data class Path(val points: List<Pt>, override val color: Int?, override val label: String?, override val thickness: Float = 1f) : Annotation
    /** Free drawing from SVG path data: one polyline per subpath. */
    data class Sketch(val contours: List<List<Pt>>, override val color: Int?, override val label: String?, override val thickness: Float = 1f) : Annotation
}

/** Parses the draw_annotations tool's `shapes` argument (screenshot pixels) into screen space. */
object AnnotationParser {
    class Invalid(message: String) : Exception(message)

    const val DEFAULT_MARK_SIZE = 28f

    val TYPES = listOf(
        "arrow", "box", "circle", "ellipse", "highlight", "underline", "strike",
        "check", "cross", "dot", "text", "path", "svg",
    )

    /**
     * Coordinates are taken the way a model measures most reliably: the bounding box of the
     * thing to mark (`around: [left, top, right, bottom]`), a `center`, or a point `at`.
     * The older x/y/width/height form (x, y = top-left) is still accepted.
     */
    fun parse(shapes: JsonArray, geometry: ShotGeometry): List<Annotation> = shapes.mapIndexed { i, el ->
        val o = el as? JsonObject ?: throw Invalid("shapes[$i] is not an object")
        val type = o.str("type")
        val color = o.str("color")?.let { parseColor(it) ?: throw Invalid("shapes[$i].color '$it' is not a colour name or #RRGGBB") }
        val label = o.str("label")?.takeIf { it.isNotBlank() }
        val thickness = (o.num("thickness") ?: 1f).coerceIn(0.4f, 4f)
        fun num(key: String) = o.num(key) ?: throw Invalid("shapes[$i] ($type) needs a number '$key'")
        fun nums(key: String, n: Int): List<Float>? {
            val a = o[key] as? JsonArray ?: return null
            val v = a.map { (it as? JsonPrimitive)?.floatOrNull }
            if (v.size != n || v.any { it == null }) throw Invalid("shapes[$i].$key must be ${if (n == 2) "[x, y]" else "[left, top, right, bottom]"}")
            return v.map { it!! }
        }
        fun xy(a: JsonArray?, key: String): Pt {
            val x = (a?.getOrNull(0) as? JsonPrimitive)?.floatOrNull
            val y = (a?.getOrNull(1) as? JsonPrimitive)?.floatOrNull
            if (x == null || y == null) throw Invalid("shapes[$i].$key must be [x, y]")
            return geometry.toScreen(x, y)
        }
        fun point(key: String) = xy(o[key] as? JsonArray, key)
        /** A point given as `at: [x, y]` (or the legacy x, y). */
        fun at(): Pt = nums("at", 2)?.let { (x, y) -> geometry.toScreen(x, y) } ?: geometry.toScreen(num("x"), num("y"))
        /** The marked region: `around: [l, t, r, b]`, else legacy top-left x, y + width, height. */
        fun region(): Pair<Pt, Pt> {
            nums("around", 4)?.let { (l, t, r, b) ->
                return geometry.toScreen(minOf(l, r), minOf(t, b)) to geometry.toScreen(maxOf(l, r), maxOf(t, b))
            }
            val x = num("x"); val y = num("y"); val w = num("width"); val h = num("height")
            return geometry.toScreen(x, y) to geometry.toScreen(x + w, y + h)
        }
        fun size() = geometry.toScreenLength(o.num("size") ?: DEFAULT_MARK_SIZE)
        when (type) {
            "arrow" -> Annotation.Arrow(point("from"), point("to"), o.num("bend")?.coerceIn(-1f, 1f), color, label, thickness)
            "box" -> region().let { (a, b) -> Annotation.Box(a, b, color, label, thickness) }
            "circle", "ellipse" -> when {
                o["around"] != null || o.num("width") != null -> region().let { (a, b) ->
                    Annotation.Ellipse(Pt((a.x + b.x) / 2, (a.y + b.y) / 2), (b.x - a.x) / 2, (b.y - a.y) / 2, color, label, thickness)
                }
                else -> {
                    val c = nums("center", 2)?.let { (x, y) -> geometry.toScreen(x, y) } ?: geometry.toScreen(num("x"), num("y"))
                    val rx = geometry.toScreenLength(o.num("rx") ?: num("radius"))
                    val ry = geometry.toScreenLength(o.num("ry") ?: o.num("radius") ?: num("rx"))
                    Annotation.Ellipse(c, rx, ry, color, label, thickness)
                }
            }
            "highlight" -> region().let { (a, b) -> Annotation.Highlight(a, b, color, thickness = thickness) }
            "underline" -> if (o["around"] != null) {
                // Under the marked words: along the bottom edge of their box.
                region().let { (a, b) ->
                    val y = b.y + geometry.toScreenLength(3f)
                    Annotation.Underline(Pt(a.x, y), Pt(b.x, y), o.bool("wavy") ?: false, color, label, thickness)
                }
            } else {
                val x = num("x"); val y = num("y"); val w = num("width")
                Annotation.Underline(geometry.toScreen(x, y), geometry.toScreen(x + w, y), o.bool("wavy") ?: false, color, label, thickness)
            }
            "strike" -> region().let { (a, b) ->
                val midY = (a.y + b.y) / 2
                Annotation.Strike(Pt(a.x, midY), Pt(b.x, midY), color, thickness = thickness)
            }
            "check" -> Annotation.Check(at(), size(), color, label, thickness)
            "cross" -> Annotation.Cross(at(), size(), color, label, thickness)
            "dot" -> Annotation.Dot(at(), color, label, thickness)
            "text" -> Annotation.Label(
                at(),
                o.str("text") ?: throw Invalid("shapes[$i] (text) needs 'text'"),
                o.num("size")?.let(geometry::toScreenLength),
                color,
                thickness,
                print = o.str("font") == "print",
            )
            "path" -> {
                val pts = (o["points"] as? JsonArray)?.map { xy(it as? JsonArray, "points") }
                    ?: throw Invalid("shapes[$i] (path) needs 'points'")
                if (pts.size < 2) throw Invalid("shapes[$i] (path) needs at least 2 points")
                Annotation.Path(pts, color, label, thickness)
            }
            "svg" -> {
                val d = o.str("d") ?: throw Invalid("shapes[$i] (svg) needs path data 'd'")
                val dx = o.num("x") ?: 0f
                val dy = o.num("y") ?: 0f
                val k = o.num("scale") ?: 1f
                val contours = try {
                    SvgPath.parse(d)
                } catch (e: IllegalArgumentException) {
                    throw Invalid("shapes[$i].d: ${e.message}")
                }.map { c -> c.map { p -> geometry.toScreen(dx + p.x * k, dy + p.y * k) } }.filter { it.size >= 2 }
                if (contours.isEmpty()) throw Invalid("shapes[$i].d draws nothing")
                Annotation.Sketch(contours, color, label, thickness)
            }
            else -> throw Invalid("shapes[$i].type must be one of ${TYPES.joinToString()}")
        }
    }

    /** Ink names (tuned to read well on paper and screens) or "#RRGGBB" / "#AARRGGBB" → ARGB. */
    fun parseColor(s: String): Int? {
        NAMED[s.trim().lowercase()]?.let { return it }
        val hex = s.trim().removePrefix("#")
        val v = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> (0xFF000000 or v).toInt()
            8 -> v.toInt()
            else -> null
        }
    }

    private val NAMED = mapOf(
        "red" to 0xFFD93A2B, "orange" to 0xFFE0702F, "yellow" to 0xFFF2C12E, "green" to 0xFF1F9D55,
        "blue" to 0xFF2F6FDB, "purple" to 0xFF8448C8, "pink" to 0xFFE0508C, "black" to 0xFF2A2A2A,
        "white" to 0xFFFFFFFF, "gray" to 0xFF7A7A7A, "grey" to 0xFF7A7A7A,
    ).mapValues { it.value.toInt() }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.floatOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
}
