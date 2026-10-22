package io.github.gaiser147.claudeoverlay.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import io.github.gaiser147.claudeoverlay.tools.Box
import io.github.gaiser147.claudeoverlay.tools.ScreenSnapshot
import io.github.gaiser147.claudeoverlay.tools.Stroke
import io.github.gaiser147.claudeoverlay.tools.UiNode
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import io.github.gaiser147.claudeoverlay.capture.ScreenshotSource
import io.github.gaiser147.claudeoverlay.capture.Shot
import io.github.gaiser147.claudeoverlay.capture.ShotEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * The accessibility service: screenshots without a MediaProjection prompt on every start, and
 * (when the user allows it) the hands Claude uses to operate other apps.
 */
class ControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    override fun onDestroy() {
        if (_instance.value === this) _instance.value = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Off the main thread: copying a full-resolution hardware bitmap takes a few ms. */
    private val captureExecutor: Executor = Dispatchers.Default.asExecutor()

    /** The screen as a software bitmap, or null on failure. */
    @RequiresApi(30)
    suspend fun screenshot(): Bitmap? {
        repeat(2) { attempt ->
            val result = suspendCancellableCoroutine<Result<Bitmap?>> { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, captureExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(shot: ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        shot.hardwareBuffer.close()
                        cont.resume(Result.success(soft))
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(Result.failure(IllegalStateException("screenshot error $errorCode")))
                    }
                })
            }
            result.getOrNull()?.let { return it }
            // The service may take one screenshot per ~333 ms; wait out the limit once.
            if (attempt == 0) delay(SCREENSHOT_INTERVAL_MS)
        }
        return null
    }

    /**
     * Performs [strokes] one after another, lifting the finger in between, as touch input to
     * whatever app is on screen. Returns false if Android cancelled one.
     */
    suspend fun perform(strokes: List<Stroke>): Boolean {
        val maxDuration = GestureDescription.getMaxGestureDuration()
        for ((i, stroke) in strokes.withIndex()) {
            val path = Path().apply {
                val first = stroke.points.first()
                moveTo(first.x, first.y)
                stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, stroke.durationMs.coerceIn(1, maxDuration)))
                .build()
            val done = suspendCancellableCoroutine { cont ->
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = cont.resume(true)
                    override fun onCancelled(gestureDescription: GestureDescription?) = cont.resume(false)
                }, null)
                if (!accepted) cont.resume(false)
            }
            if (!done) return false
            if (i < strokes.lastIndex) delay(PEN_LIFT_MS)
        }
        return true
    }

    /** Types into the focused field: appends via ACTION_SET_TEXT, else pastes. */
    fun typeText(text: String, submit: Boolean): String {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "No text field has focus. Tap the field first, then type."
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val typed = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
            },
        ) || run {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Claude", text))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        if (!typed) return "The focused field didn't accept text."
        if (submit && Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return "Typed ${text.length} characters" + if (submit) " and pressed enter" else ""
    }

    /**
     * The other apps' windows as accessibility trees, topmost first, for reading the screen as
     * text. Our own windows are left out; the keyboard is only noted by where it starts.
     */
    fun readScreen(screenWidth: Int, screenHeight: Int): ScreenSnapshot {
        val screen = Box(0, 0, screenWidth, screenHeight)
        val roots = mutableListOf<UiNode>()
        var keyboardTop: Int? = null
        var app: String? = null
        val budget = intArrayOf(MAX_NODES)
        val bounds = Rect()
        for (w in runCatching { windows }.getOrDefault(emptyList()).sortedByDescending { it.layer }) {
            w.getBoundsInScreen(bounds)
            when (w.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> keyboardTop = bounds.top
                AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_SYSTEM -> {
                    // System windows only when they take over (notification shade, system
                    // dialogs), not the status and navigation bars.
                    if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                        bounds.width().toLong() * bounds.height() < screenWidth.toLong() * screenHeight / 3
                    ) continue
                    val root = w.root ?: continue
                    if (root.packageName == packageName) continue
                    if (app == null) app = appLabel(root.packageName?.toString())
                    toUiNode(root, 0, budget)?.let(roots::add)
                }
            }
        }
        if (roots.isEmpty()) rootInActiveWindow?.takeIf { it.packageName != packageName }?.let { root ->
            app = appLabel(root.packageName?.toString())
            toUiNode(root, 0, budget)?.let(roots::add)
        }
        return ScreenSnapshot(app, roots, screen, keyboardTop)
    }

    private fun toUiNode(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): UiNode? {
        if (!node.isVisibleToUser || depth > MAX_DEPTH || budget[0]-- <= 0) return null
        val r = Rect().also(node::getBoundsInScreen)
        val showingHint = node.isShowingHintText
        val children = (0 until node.childCount).mapNotNull { i -> node.getChild(i)?.let { toUiNode(it, depth + 1, budget) } }
        return UiNode(
            text = node.text?.toString()?.takeUnless { showingHint },
            description = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            role = node.className?.toString()?.substringAfterLast('.').orEmpty(),
            clickable = node.isClickable || node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            selected = node.isSelected,
            focused = node.isFocused,
            enabled = node.isEnabled,
            bounds = Box(r.left, r.top, r.right, r.bottom),
            children = children,
        )
    }

    private fun appLabel(pkg: String?): String? = pkg?.let {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }
            .getOrDefault(it)
    }

    fun pressKey(key: String): Boolean {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(action)
    }

    companion object {
        private const val SCREENSHOT_INTERVAL_MS = 350L
        private const val PEN_LIFT_MS = 30L
        /** Enough for busy screens; a runaway tree (huge lists, web pages) is cut off. */
        private const val MAX_NODES = 1500
        private const val MAX_DEPTH = 60

        private val _instance = MutableStateFlow<ControlService?>(null)
        /** The connected service, or null while it's off in the system's accessibility settings. */
        val instance: StateFlow<ControlService?> = _instance

        /** Screenshots through the service need Android 11+. */
        val canScreenshot: Boolean get() = Build.VERSION.SDK_INT >= 30
    }
}

/** Screenshots via [ControlService]: taken at call time, so only the compositor needs to settle. */
@RequiresApi(30)
class AccessibilityShots(private val service: ControlService) : ScreenshotSource {
    override suspend fun capture(notBeforeNanos: Long, maxEdge: Int): Shot? {
        // Two vsyncs for the hidden overlay to leave the composited screen.
        val settle = (notBeforeNanos + SETTLE_NANOS - System.nanoTime()) / 1_000_000
        if (settle > 0) delay(settle)
        val bitmap = service.screenshot() ?: return null
        return withContext(Dispatchers.Default) { ShotEncoder.encode(bitmap, maxEdge) }
    }

    private companion object {
        const val SETTLE_NANOS = 50_000_000L
    }
}
