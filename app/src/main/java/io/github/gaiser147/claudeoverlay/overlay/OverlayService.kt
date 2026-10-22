package io.github.gaiser147.claudeoverlay.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.gaiser147.claudeoverlay.AppGraph
import io.github.gaiser147.claudeoverlay.MainActivity
import io.github.gaiser147.claudeoverlay.R
import io.github.gaiser147.claudeoverlay.capture.ScreenCapturer
import io.github.gaiser147.claudeoverlay.ui.theme.ClaudeOverlayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Hosts the floating window (orb ⇄ chat panel) and keeps the CLI process alive in the
 * background as a foreground service. Holds the screen-capture projection when granted.
 */
class OverlayService : LifecycleService(), SavedStateRegistryOwner, OverlayWindowActions {
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var wm: WindowManager
    private lateinit var root: OverlayRoot
    private lateinit var params: WindowManager.LayoutParams
    lateinit var controller: ChatController
        private set

    private var bubbleX = 0
    private var bubbleY = 0
    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = 0

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        current = this
        wm = getSystemService(WindowManager::class.java)
        controller = ChatController(AppGraph.get(this), lifecycleScope, ::setHidden)
        val (sw, sh) = screenSize()
        bubbleX = sw - dp(BUBBLE_DP) - dp(8)
        bubbleY = (sh * 0.35f).roundToInt()
        panelW = minOf(sw - dp(16), dp(420))
        panelH = (sh * 0.55f).roundToInt()
        panelX = (sw - panelW) / 2
        panelY = (sh * 0.08f).roundToInt()
        createWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXPAND -> expand()
        }
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        // Keep the mediaProjection type while a capture is live, or Android ends the projection.
        startInForeground(data != null || controller.state.value.canCapture)
        if (data != null) {
            val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            if (projection != null) {
                val capturer = runCatching {
                    ScreenCapturer(this, projection) { lifecycleScope.launch { controller.onCaptureStopped() } }
                }.getOrNull()
                controller.attachCapturer(capturer)
            }
        }
        // The projection consent can't be replayed, so a restart without it would be half-working.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.close()
        runCatching { wm.removeView(root) }
        if (current === this) current = null
        super.onDestroy()
    }

    // ── window ──────────────────────────────────────────────────────────────────────────────

    private fun createWindow() {
        params = WindowManager.LayoutParams(
            dp(BUBBLE_DP), dp(BUBBLE_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BUBBLE_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                ClaudeOverlayTheme {
                    val state by controller.state.collectAsState()
                    if (state.expanded) ChatPanel(state, controller, this@OverlayService)
                    else Bubble(state, this@OverlayService)
                }
            }
        }
        root = OverlayRoot(this).apply {
            addView(compose)
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
        }
        wm.addView(root, params)
    }

    private val expanded get() = controller.state.value.expanded

    override fun moveBy(dx: Float, dy: Float) {
        val (sw, sh) = screenSize()
        params.x = (params.x + dx.roundToInt()).coerceIn(0, maxOf(0, sw - params.width))
        params.y = (params.y + dy.roundToInt()).coerceIn(0, maxOf(0, sh - params.height))
        if (expanded) { panelX = params.x; panelY = params.y } else { bubbleX = params.x; bubbleY = params.y }
        update()
    }

    override fun resizeBy(dx: Float, dy: Float) {
        if (!expanded) return
        val (sw, sh) = screenSize()
        panelW = (params.width + dx.roundToInt()).coerceIn(dp(240), maxOf(dp(240), sw - params.x))
        panelH = (params.height + dy.roundToInt()).coerceIn(dp(260), maxOf(dp(260), sh - params.y))
        params.width = panelW
        params.height = panelH
        update()
    }

    override fun expand() {
        if (expanded) return
        // The screen may have rotated since the panel was last placed.
        val (sw, sh) = screenSize()
        panelW = panelW.coerceAtMost(sw)
        panelH = panelH.coerceAtMost(sh)
        panelX = panelX.coerceIn(0, sw - panelW)
        panelY = panelY.coerceIn(0, sh - panelH)
        params.width = panelW
        params.height = panelH
        params.x = panelX
        params.y = panelY
        params.flags = PANEL_FLAGS
        controller.setExpanded(true)
        update()
    }

    override fun collapse() {
        if (!expanded) return
        hideKeyboard()
        params.width = dp(BUBBLE_DP)
        params.height = dp(BUBBLE_DP)
        params.x = bubbleX
        params.y = bubbleY
        params.flags = BUBBLE_FLAGS
        controller.setExpanded(false)
        update()
    }

    override fun pickFiles() {
        startActivity(Intent(this, PickFileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openApp() {
        collapse()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Hidden while a screenshot is taken, so the overlay isn't in its own picture. */
    private suspend fun setHidden(hidden: Boolean) = withContext(Dispatchers.Main) {
        root.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    /**
     * The panel takes keyboard focus only while it's being used: a touch outside hands focus
     * (and the keyboard, and Back) back to the app underneath; touching the panel takes it again.
     */
    private fun setFocusable(focusable: Boolean) {
        val flags = if (focusable) PANEL_FLAGS else PANEL_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (params.flags == flags) return
        params.flags = flags
        if (!focusable) hideKeyboard()
        update()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun update() = runCatching { wm.updateViewLayout(root, params) }

    private inner class OverlayRoot(context: Context) : FrameLayout(context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (expanded) when (ev.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> setFocusable(false)
                MotionEvent.ACTION_DOWN -> setFocusable(true)
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && expanded) {
                if (event.action == KeyEvent.ACTION_UP) collapse()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    // ── foreground service ─────────────────────────────────────────────────────────────────

    private fun startInForeground(withProjection: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val expand = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_EXPAND), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(expand)
            .addAction(0, getString(R.string.stop_overlay), stop)
            .build()
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (withProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            Build.VERSION.SDK_INT >= 29 && withProjection -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun screenSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else m.widthPixels to m.heightPixels
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val ACTION_STOP = "io.github.gaiser147.claudeoverlay.STOP"
        const val ACTION_EXPAND = "io.github.gaiser147.claudeoverlay.EXPAND"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 1

        private const val BUBBLE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        private const val PANEL_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        @Volatile var current: OverlayService? = null
            private set

        fun start(context: Context, resultCode: Int? = null, data: Intent? = null) {
            val intent = Intent(context, OverlayService::class.java)
            if (resultCode != null && data != null) {
                intent.putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}

