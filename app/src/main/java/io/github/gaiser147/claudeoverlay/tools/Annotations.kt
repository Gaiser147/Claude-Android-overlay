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

    data class Arrow(val from: Pt, val to: Pt, val bend: Float?, override val color: Int?, override val label: String?) : Annotation
    data class Box(val topLeft: Pt, val bottomRight: Pt, override val color: Int?, override val label: String?) : Annotation
    data class Ellipse(val center: Pt, val rx: Float, val ry: Float, override val color: Int?, override val label: String?) : Annotation
    data class Highlight(val topLeft: Pt, val bottomRight: Pt, override val color: Int?, override val label: String? = null) : Annotation
    data class Underline(val from: Pt, val to: Pt, val wavy: Boolean, override val color: Int?, override val label: String?) : Annotation
    data class Strike(val from: Pt, val to: Pt, override val color: Int?, override val label: String? = null) : Annotation
    data class Check(val center: Pt, val size: Float, override val color: Int?, override val label: String?) : Annotation
    data class Cross(val center: Pt, val size: Float, override val color: Int?, override val label: String?) : Annotation
    data class Dot(val at: Pt, override val color: Int?, override val label: String?) : Annotation
    data class Label(val at: Pt, val text: String, val size: Float?, override val color: Int?) : Annotation {
        override val label: String? get() = null
    }
    data class Path(val points: List<Pt>, override val color: Int?, override val label: String?) : Annotation
    /** Free drawing from SVG path data: one polyline per subpath. */
    data class Sketch(val contours: List<List<Pt>>, override val color: Int?, override val label: String?) : Annotation
}

/** Parses the draw_annotations tool's `shapes` argument (screenshot pixels) into screen space. */
object AnnotationParser {
    class Invalid(message: String) : Exception(message)

    const val DEFAULT_MARK_SIZE = 28f

    val TYPES = listOf(
        "arrow", "box", "circle", "ellipse", "highlight", "underline", "strike",
        "check", "cross", "dot", "text", "path", "svg",
    )

    fun parse(shapes: JsonArray, geometry: ShotGeometry): List<Annotation> = shapes.mapIndexed { i, el ->
        val o = el as? JsonObject ?: throw Invalid("shapes[$i] is not an object")
        val type = o.str("type")
        val color = o.str("color")?.let { parseColor(it) ?: throw Invalid("shapes[$i].color '$it' is not a colour name or #RRGGBB") }
        val label = o.str("label")?.takeIf { it.isNotBlank() }
        fun num(key: String) = o.num(key) ?: throw Invalid("shapes[$i] ($type) needs a number '$key'")
        fun xy(a: JsonArray?, key: String): Pt {
            val x = (a?.getOrNull(0) as? JsonPrimitive)?.floatOrNull
            val y = (a?.getOrNull(1) as? JsonPrimitive)?.floatOrNull
            if (x == null || y == null) throw Invalid("shapes[$i].$key must be [x, y]")
            return geometry.toScreen(x, y)
        }
        fun point(key: String) = xy(o[key] as? JsonArray, key)
        fun rect(): Pair<Pt, Pt> {
            val x = num("x"); val y = num("y"); val w = num("width"); val h = num("height")
            return geometry.toScreen(x, y) to geometry.toScreen(x + w, y + h)
        }
        fun size() = geometry.toScreenLength(o.num("size") ?: DEFAULT_MARK_SIZE)
        when (type) {
            "arrow" -> Annotation.Arrow(point("from"), point("to"), o.num("bend")?.coerceIn(-1f, 1f), color, label)
            "box" -> rect().let { (a, b) -> Annotation.Box(a, b, color, label) }
            "circle", "ellipse" ->
                if (o.num("width") != null) {
                    rect().let { (a, b) ->
                        Annotation.Ellipse(Pt((a.x + b.x) / 2, (a.y + b.y) / 2), (b.x - a.x) / 2, (b.y - a.y) / 2, color, label)
                    }
                } else {
                    val r = geometry.toScreenLength(num("radius"))
                    Annotation.Ellipse(geometry.toScreen(num("x"), num("y")), r, r, color, label)
                }
            "highlight" -> rect().let { (a, b) -> Annotation.Highlight(a, b, color) }
            "underline" -> {
                val x = num("x"); val y = num("y"); val w = num("width")
                Annotation.Underline(geometry.toScreen(x, y), geometry.toScreen(x + w, y), o.bool("wavy") ?: false, color, label)
            }
            "strike" -> rect().let { (a, b) ->
                val midY = (a.y + b.y) / 2
                Annotation.Strike(Pt(a.x, midY), Pt(b.x, midY), color)
            }
            "check" -> Annotation.Check(geometry.toScreen(num("x"), num("y")), size(), color, label)
            "cross" -> Annotation.Cross(geometry.toScreen(num("x"), num("y")), size(), color, label)
            "dot" -> Annotation.Dot(geometry.toScreen(num("x"), num("y")), color, label)
            "text" -> Annotation.Label(
                geometry.toScreen(num("x"), num("y")),
                o.str("text") ?: throw Invalid("shapes[$i] (text) needs 'text'"),
                o.num("size")?.let(geometry::toScreenLength),
                color,
            )
            "path" -> {
                val pts = (o["points"] as? JsonArray)?.map { xy(it as? JsonArray, "points") }
                    ?: throw Invalid("shapes[$i] (path) needs 'points'")
                if (pts.size < 2) throw Invalid("shapes[$i] (path) needs at least 2 points")
                Annotation.Path(pts, color, label)
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
                Annotation.Sketch(contours, color, label)
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
