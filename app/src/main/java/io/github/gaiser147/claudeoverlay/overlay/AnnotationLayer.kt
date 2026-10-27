package io.github.gaiser147.claudeoverlay.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.gaiser147.claudeoverlay.R
import io.github.gaiser147.claudeoverlay.access.ControlService
import io.github.gaiser147.claudeoverlay.tools.Annotation
import io.github.gaiser147.claudeoverlay.tools.Ink
import io.github.gaiser147.claudeoverlay.tools.InkMark
import io.github.gaiser147.claudeoverlay.tools.InkStroke
import io.github.gaiser147.claudeoverlay.tools.InkText
import io.github.gaiser147.claudeoverlay.tools.Pt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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
    /** Screen area to keep clear: the overlay's panel or orb (this layer is above them). */
    private var keepClear by mutableStateOf<Rect?>(null)
    /** Bumped on every draw so new shapes animate in. */
    private var generation by mutableIntStateOf(0)
    private var view: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var hidden = false
    private var opacity = 1f

    val isShowing: Boolean get() = annotations.isNotEmpty()

    /** Shows [list]; returns how long the draw-on animation takes (ms). */
    fun show(list: List<Annotation>, clearPrevious: Boolean): Long {
        annotations = if (clearPrevious) list else annotations + list
        generation++
        if (view == null) attach()
        val ink = Ink(context.resources.displayMetrics.density)
        return DrawSchedule(annotations.map { ink.render(it).effort }, context.resources.displayMetrics.density).totalMs.toLong()
    }

    fun clear() {
        annotations = emptyList()
        detach()
    }

    fun keepClear(rect: Rect?) {
        if (keepClear != rect) keepClear = rect
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
            setContent { Drawings(annotations, generation, keepClear) }
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

/** Claude's handwriting: Caveat (SIL OFL 1.1), see THIRD_PARTY_NOTICES.md. */
private val Handwriting = FontFamily(Font(R.font.caveat_semibold, FontWeight.SemiBold))

@Composable
private fun Drawings(annotations: List<Annotation>, generation: Int, exclusion: Rect?) {
    val density = LocalDensity.current.density
    val marks = remember(annotations, density) {
        val ink = Ink(density)
        annotations.map(ink::render)
    }
    // Marks appear one after another at writing speed, like someone annotating by hand.
    val schedule = remember(marks) { DrawSchedule(marks.map { it.effort }, density) }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(generation) {
        clock.snapTo(0f)
        clock.animateTo(schedule.totalMs.toFloat(), tween(schedule.totalMs, easing = LinearEasing))
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() }) {
        val draw = {
            translate(-origin.x, -origin.y) {
                marks.forEachIndexed { i, mark -> drawMark(mark, schedule.progress(i, clock.value), measurer) }
            }
        }
        // Never draw over the overlay's own panel or orb: this layer sits above them.
        if (exclusion != null) {
            clipRect(
                exclusion.left - origin.x, exclusion.top - origin.y,
                exclusion.right - origin.x, exclusion.bottom - origin.y,
                clipOp = ClipOp.Difference,
            ) { draw() }
        } else draw()
    }
}

/** When each mark starts and how long it takes, from how much writing it is. */
private class DrawSchedule(efforts: List<Float>, density: Float) {
    private val durations: List<Int>
    private val starts: List<Int>
    val totalMs: Int

    init {
        // ~0.9 dp of pen travel per ms, a brisk hand; whole drawings are capped so a big
        // diagram doesn't take ages to appear.
        val raw = efforts.map { (it / (0.9f * density)).coerceIn(MIN_MARK_MS, MAX_MARK_MS) }
        val sum = raw.sum()
        val k = if (sum > MAX_TOTAL_MS) MAX_TOTAL_MS / sum else 1f
        durations = raw.map { (it * k).toInt().coerceAtLeast(1) }
        starts = durations.runningFold(0) { acc, d -> acc + d }.dropLast(1)
        totalMs = durations.sum().coerceAtLeast(1)
    }

    fun progress(i: Int, nowMs: Float): Float = ((nowMs - starts[i]) / durations[i]).coerceIn(0f, 1f)

    private companion object {
        const val MIN_MARK_MS = 160f
        const val MAX_MARK_MS = 700f
        const val MAX_TOTAL_MS = 2600f
    }
}

/** Draws [mark] up to [progress]: strokes in order by pen travel, then its words. */
private fun DrawScope.drawMark(mark: InkMark, progress: Float, measurer: TextMeasurer) {
    if (progress <= 0f) return
    val strokeLen = mark.strokes.sumOf { it.length.toDouble() }.toFloat()
    val total = mark.effort.coerceAtLeast(1f)
    var budget = progress * total
    for (s in mark.strokes) {
        if (budget <= 0f) return
        val shown = minOf(1f, budget / s.length.coerceAtLeast(1f))
        drawInk(s, shown)
        budget -= s.length
    }
    if (mark.texts.isEmpty()) return
    val textShare = ((progress * total - strokeLen) / (total - strokeLen).coerceAtLeast(1f)).coerceIn(0f, 1f)
    if (textShare > 0f) mark.texts.forEach { drawHandwriting(it, textShare, measurer) }
}

/** One stroke as a filled outline whose width follows the pen pressure, with a faint halo. */
private fun DrawScope.drawInk(s: InkStroke, fraction: Float) {
    val n = if (fraction >= 1f) s.points.size else prefixCount(s, fraction)
    if (n < 2) return
    val color = Color(s.color)
    if (!s.marker && s.halo) {
        // A soft paper-coloured edge keeps ink legible over busy text or dark screens.
        drawPath(outline(s, n, extra = 1.6f * density), Color.White.copy(alpha = 0.32f))
    }
    drawPath(outline(s, n, extra = 0f), color.copy(alpha = s.alpha))
}

/** How many points of [s] cover the first [fraction] of its length. */
private fun prefixCount(s: InkStroke, fraction: Float): Int {
    val target = s.length * fraction
    var acc = 0f
    for (i in 1 until s.points.size) {
        acc += hypot(s.points[i].x - s.points[i - 1].x, s.points[i].y - s.points[i - 1].y)
        if (acc >= target) return i + 1
    }
    return s.points.size
}

/** The stroke's outline: both sides of the centreline at half the local width, round ends. */
private fun outline(s: InkStroke, n: Int, extra: Float): Path {
    val left = ArrayList<Offset>(n)
    val right = ArrayList<Offset>(n)
    for (i in 0 until n) {
        val p = s.points[i]
        val a = s.points[maxOf(0, i - 1)]
        val b = s.points[minOf(n - 1, i + 1)]
        var dx = b.x - a.x
        var dy = b.y - a.y
        val l = hypot(dx, dy)
        if (l < 1e-3f) { dx = 1f; dy = 0f } else { dx /= l; dy /= l }
        val w = s.widths[i] / 2 + extra
        left += Offset(p.x - dy * w, p.y + dx * w)
        right += Offset(p.x + dy * w, p.y - dx * w)
    }
    return Path().apply {
        moveTo(left[0].x, left[0].y)
        for (i in 1 until n) lineTo(left[i].x, left[i].y)
        cap(s.points[n - 1], left[n - 1], right[n - 1])
        for (i in n - 1 downTo 0) lineTo(right[i].x, right[i].y)
        cap(s.points[0], right[0], left[0])
        close()
    }
}

/** A half-round pen end from [from] to [to] around [c]. */
private fun Path.cap(c: Pt, from: Offset, to: Offset) {
    val a0 = atan2(from.y - c.y, from.x - c.x)
    val r = hypot(from.x - c.x, from.y - c.y)
    var a1 = atan2(to.y - c.y, to.x - c.x)
    // Go round the outside: the sweep is always half a turn, clockwise in screen space.
    if (a1 > a0) a1 -= (2 * Math.PI).toFloat()
    val steps = 6
    for (k in 1..steps) {
        val a = a0 + (a1 - a0) * k / steps
        lineTo(c.x + r * cos(a), c.y + r * sin(a))
    }
}

/** Handwritten words, revealed left to right as if being written, with a faint halo. */
private fun DrawScope.drawHandwriting(t: InkText, fraction: Float, measurer: TextMeasurer) {
    val style = TextStyle(
        fontFamily = if (t.print) FontFamily.Default else Handwriting,
        fontSize = (t.size / density / fontScale).sp,
        fontWeight = if (t.print) FontWeight.Medium else FontWeight.SemiBold,
    )
    val layout = measurer.measure(t.text, style)
    val x = t.at.x.coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
    val y = t.at.y.coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f))
    rotate(t.rotationDeg, pivot = Offset(x, y + layout.size.height / 2f)) {
        clipRect(x - 8 * density, y - 8 * density, x + layout.size.width * fraction + 4 * density, y + layout.size.height + 8 * density) {
            // Colour and style passed explicitly: the halo is a thin white outline behind the
            // ink, never an ink-coloured stroke that would make the letters look bloated.
            drawText(
                layout, color = Color.White.copy(alpha = 0.75f), topLeft = Offset(x, y),
                drawStyle = Stroke(width = 2.5f * density, join = StrokeJoin.Round),
            )
            drawText(layout, color = Color(t.color).copy(alpha = Ink.INK_ALPHA), topLeft = Offset(x, y), drawStyle = Fill)
        }
    }
}
