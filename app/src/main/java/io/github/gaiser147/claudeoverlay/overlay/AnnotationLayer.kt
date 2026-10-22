package io.github.gaiser147.claudeoverlay.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.gaiser147.claudeoverlay.access.ControlService
import io.github.gaiser147.claudeoverlay.tools.Annotation
import io.github.gaiser147.claudeoverlay.tools.Pt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A full-screen, see-through, untouchable window where Claude's drawings appear: arrows,
 * boxes, circles, highlights and labels over whatever app is open.
 *
 * Touches must reach the app underneath. Android 12+ blocks touches that pass through another
 * app's window more opaque than 0.8 — except accessibility overlays, which are trusted. So the
 * layer is an accessibility overlay when the service is on, else an app overlay capped at the
 * maximum obscuring opacity.
 */
class AnnotationLayer(
    private val context: Context,
    private val owner: Any,
) {
    private var annotations by mutableStateOf<List<Annotation>>(emptyList())
    /** Bumped on every draw so new shapes animate in. */
    private var generation by mutableIntStateOf(0)
    private var view: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var hidden = false
    private var opacity = 1f

    val isShowing: Boolean get() = annotations.isNotEmpty()

    fun show(list: List<Annotation>, clearPrevious: Boolean) {
        annotations = if (clearPrevious) list else annotations + list
        generation++
        if (view == null) attach()
    }

    fun clear() {
        annotations = emptyList()
        detach()
    }

    /** Out of the way for screenshots and gestures (alpha 0 never obscures touches). */
    fun setHidden(hidden: Boolean) {
        this.hidden = hidden
        val p = params ?: return
        p.alpha = if (hidden) 0f else opacity
        runCatching { windowManager?.updateViewLayout(view, p) }
    }

    private fun attach() {
        val service = ControlService.instance.value
        val wm = (service ?: context).getSystemService(WindowManager::class.java)
        opacity = if (service != null) 1f else maxObscuringOpacity()
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = if (hidden) 0f else opacity
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        val v = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner as LifecycleOwner)
            setViewTreeSavedStateRegistryOwner(owner as SavedStateRegistryOwner)
            setContent { Drawings(annotations, generation) }
        }
        runCatching { wm.addView(v, p) }.onSuccess {
            view = v
            windowManager = wm
            params = p
        }
    }

    private fun detach() {
        view?.let { v -> runCatching { windowManager?.removeView(v) } }
        view = null
        params = null
    }

    private fun maxObscuringOpacity(): Float =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        } else 0.8f
}

private val Accent = Color(0xFFD97757)
private val HighlightDefault = Color(0xFFFFD54F)

@Composable
private fun Drawings(annotations: List<Annotation>, generation: Int) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(generation) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(DRAW_IN_MS))
    }
    // The window may not start at the screen's corner (cutouts, bars); shapes are in screen pixels.
    var origin by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() }) {
        val t = progress.value
        annotations.forEach { a ->
            val color = a.color?.let { Color(it) } ?: if (a is Annotation.Highlight) HighlightDefault else Accent
            val strokeWidth = px(4)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun o(p: Pt) = Offset(p.x - origin.x, p.y - origin.y)
            when (a) {
                is Annotation.Highlight -> drawRect(
                    color.copy(alpha = 0.35f * t), topLeft = o(a.topLeft), size = size(o(a.topLeft), o(a.bottomRight)),
                )
                is Annotation.Label -> label(measurer, a.text, o(a.at), color, t)
                else -> {
                    val path = outline(a, ::o)
                    drawPath(partial(path, t), color, style = stroke)
                    if (a is Annotation.Arrow && t >= 1f) arrowHead(o(a.from), o(a.to), color, stroke)
                }
            }
            a.label?.let { text -> labelAnchor(a, ::o)?.let { label(measurer, text, it, color, t) } }
        }
    }
}

private fun DrawScope.size(a: Offset, b: Offset) = Size(b.x - a.x, b.y - a.y)

private fun DrawScope.outline(a: Annotation, o: (Pt) -> Offset): Path = Path().apply {
    when (a) {
        is Annotation.Arrow -> { val f = o(a.from); val t = o(a.to); moveTo(f.x, f.y); lineTo(t.x, t.y) }
        is Annotation.Box -> addRoundRect(
            androidx.compose.ui.geometry.RoundRect(Rect(o(a.topLeft), o(a.bottomRight)), CornerRadius(px(8)))
        )
        is Annotation.Circle -> { val c = o(a.center); addOval(Rect(c, a.radius)) }
        is Annotation.Path -> a.points.map(o).let { pts ->
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        else -> Unit
    }
}

/** The first [fraction] of [path], for the draw-in animation. */
private fun partial(path: Path, fraction: Float): Path {
    if (fraction >= 1f) return path
    val measure = PathMeasure().apply { setPath(path, false) }
    return Path().also { measure.getSegment(0f, measure.length * fraction, it, true) }
}

private fun DrawScope.arrowHead(from: Offset, to: Offset, color: Color, stroke: Stroke) {
    val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    val len = px(18)
    for (side in listOf(-1, 1)) {
        val a = angle + Math.PI + side * Math.toRadians(28.0)
        drawLine(
            color, to, Offset(to.x + (len * cos(a)).toFloat(), to.y + (len * sin(a)).toFloat()),
            strokeWidth = stroke.width, cap = StrokeCap.Round,
        )
    }
}

/** Where a shape's caption goes: at the arrow's tail, above a box or circle. */
private fun labelAnchor(a: Annotation, o: (Pt) -> Offset): Offset? = when (a) {
    is Annotation.Arrow -> o(a.from)
    is Annotation.Box -> o(a.topLeft).let { Offset(it.x, it.y - 34f) }
    is Annotation.Circle -> o(a.center).let { Offset(it.x - a.radius, it.y - a.radius - 34f) }
    is Annotation.Path -> o(a.points.first())
    else -> null
}

private fun DrawScope.label(measurer: androidx.compose.ui.text.TextMeasurer, text: String, at: Offset, color: Color, t: Float) {
    val layout = measurer.measure(text, TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    val pad = px(6)
    val w = layout.size.width + 2 * pad
    val h = layout.size.height + pad
    // Keep the pill on screen.
    val x = at.x.coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    val y = at.y.coerceIn(0f, (size.height - h).coerceAtLeast(0f))
    drawRoundRect(color.copy(alpha = 0.92f * t), Offset(x, y), Size(w, h), CornerRadius(h / 2))
    drawText(layout, topLeft = Offset(x + pad, y + pad / 2), alpha = t)
}

private fun DrawScope.px(dp: Int): Float = dp * density

private const val DRAW_IN_MS = 450
