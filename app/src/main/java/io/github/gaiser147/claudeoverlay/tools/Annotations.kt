package io.github.gaiser147.claudeoverlay.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/** Something Claude draws over the screen, already in screen pixels. */
sealed interface Annotation {
    /** ARGB; null = the overlay's accent colour. */
    val color: Int?
    val label: String?

    data class Arrow(val from: Pt, val to: Pt, override val color: Int?, override val label: String?) : Annotation
    data class Box(val topLeft: Pt, val bottomRight: Pt, override val color: Int?, override val label: String?) : Annotation
    data class Circle(val center: Pt, val radius: Float, override val color: Int?, override val label: String?) : Annotation
    data class Highlight(val topLeft: Pt, val bottomRight: Pt, override val color: Int?, override val label: String? = null) : Annotation
    data class Label(val at: Pt, val text: String, override val color: Int?) : Annotation {
        override val label: String? get() = null
    }
    data class Path(val points: List<Pt>, override val color: Int?, override val label: String?) : Annotation
}

/** Parses the draw_annotations tool's `shapes` argument (screenshot pixels) into screen space. */
object AnnotationParser {
    class Invalid(message: String) : Exception(message)

    fun parse(shapes: JsonArray, geometry: ShotGeometry): List<Annotation> = shapes.mapIndexed { i, el ->
        val o = el as? JsonObject ?: throw Invalid("shapes[$i] is not an object")
        val color = o.str("color")?.let { parseColor(it) ?: throw Invalid("shapes[$i].color '$it' is not #RRGGBB") }
        val label = o.str("label")?.takeIf { it.isNotBlank() }
        fun num(key: String) = o.num(key) ?: throw Invalid("shapes[$i] (${o.str("type")}) needs a number '$key'")
        fun point(key: String): Pt {
            val a = o[key] as? JsonArray ?: throw Invalid("shapes[$i].$key must be [x, y]")
            val x = (a.getOrNull(0) as? JsonPrimitive)?.floatOrNull
            val y = (a.getOrNull(1) as? JsonPrimitive)?.floatOrNull
            if (x == null || y == null) throw Invalid("shapes[$i].$key must be [x, y]")
            return geometry.toScreen(x, y)
        }
        fun rect(): Pair<Pt, Pt> {
            val x = num("x"); val y = num("y"); val w = num("width"); val h = num("height")
            return geometry.toScreen(x, y) to geometry.toScreen(x + w, y + h)
        }
        when (o.str("type")) {
            "arrow" -> Annotation.Arrow(point("from"), point("to"), color, label)
            "box" -> rect().let { (a, b) -> Annotation.Box(a, b, color, label) }
            "circle" -> Annotation.Circle(geometry.toScreen(num("x"), num("y")), geometry.toScreenLength(num("radius")), color, label)
            "highlight" -> rect().let { (a, b) -> Annotation.Highlight(a, b, color) }
            "text" -> Annotation.Label(
                geometry.toScreen(num("x"), num("y")),
                o.str("text") ?: throw Invalid("shapes[$i] (text) needs 'text'"),
                color,
            )
            "path" -> {
                val pts = (o["points"] as? JsonArray)?.map { p ->
                    val a = p as? JsonArray
                    val x = (a?.getOrNull(0) as? JsonPrimitive)?.floatOrNull
                    val y = (a?.getOrNull(1) as? JsonPrimitive)?.floatOrNull
                    if (x == null || y == null) throw Invalid("shapes[$i].points must be [[x, y], …]")
                    geometry.toScreen(x, y)
                } ?: throw Invalid("shapes[$i] (path) needs 'points'")
                if (pts.size < 2) throw Invalid("shapes[$i] (path) needs at least 2 points")
                Annotation.Path(pts, color, label)
            }
            else -> throw Invalid("shapes[$i].type must be arrow, box, circle, highlight, text or path")
        }
    }

    /** "#RRGGBB" or "#AARRGGBB" → ARGB. */
    fun parseColor(s: String): Int? {
        val hex = s.removePrefix("#")
        val v = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> (0xFF000000 or v).toInt()
            8 -> v.toInt()
            else -> null
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.floatOrNull
}
