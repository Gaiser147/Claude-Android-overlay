package io.github.gaiser147.claudeoverlay.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import io.github.gaiser147.claudeoverlay.capture.CaptureWindow
import io.github.gaiser147.claudeoverlay.access.ControlService
import io.github.gaiser147.claudeoverlay.capture.Shot
import io.github.gaiser147.claudeoverlay.tools.Annotation
import io.github.gaiser147.claudeoverlay.stylus.Stylus
import io.github.gaiser147.claudeoverlay.tools.HersheyFont
import io.github.gaiser147.claudeoverlay.tools.InputKind
import io.github.gaiser147.claudeoverlay.tools.PenState
import io.github.gaiser147.claudeoverlay.tools.ScreenSnapshot
import io.github.gaiser147.claudeoverlay.tools.McpHttpServer
import io.github.gaiser147.claudeoverlay.tools.Ink
import io.github.gaiser147.claudeoverlay.tools.NotesAccess
import io.github.gaiser147.claudeoverlay.tools.NotesInk
import io.github.gaiser147.claudeoverlay.tools.Suggestion
import io.github.gaiser147.claudeoverlay.notes.LiveTrigger
import io.github.gaiser147.claudeoverlay.notes.NotesLink
import io.github.gaiser147.claudeoverlay.notes.NotesWatcher
import io.github.gaiser147.claudeoverlay.tools.Box
import io.github.gaiser147.claudeoverlay.notes.NotesPin
import io.github.gaiser147.claudeoverlay.tools.PhoneHost
import io.github.gaiser147.claudeoverlay.tools.PhoneToolbox
import io.github.gaiser147.claudeoverlay.tools.Stroke
import io.github.gaiser147.claudeoverlay.capture.screenSize as physicalScreenSize
import io.github.gaiser147.claudeoverlay.capture.ScreenCapturer
import io.github.gaiser147.claudeoverlay.ui.theme.ClaudeOverlayTheme
import io.github.gaiser147.claudeoverlay.voice.Speaker
import io.github.gaiser147.claudeoverlay.voice.VoiceInput
import io.github.gaiser147.claudeoverlay.CrashLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private lateinit var annotations: AnnotationLayer
    private lateinit var suggestions: SuggestionLayer
    private lateinit var watcher: NotesWatcher
    /** Claude's handwriting font, for writing into Claude Notes. */
    private lateinit var cursive: HersheyFont
    private lateinit var mcp: McpHttpServer

    /**
     * The service's coroutines: an exception escaping one would otherwise kill the app (and
     * with it the CLI, possibly mid token refresh). Logged instead, and the overlay stays up.
     */
    private val safeScope by lazy {
        CoroutineScope(lifecycleScope.coroutineContext + CoroutineExceptionHandler { _, e ->
            CrashLog.nonFatal(e, "overlay coroutine")
        })
    }

    /** A MediaProjection is live, so the foreground service must keep its mediaProjection type. */
    private var projectionActive = false
    private var inForeground = false
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
        annotations = AnnotationLayer(this, this)
        suggestions = SuggestionLayer(
            this, this,
            accept = ::putIntoNote,
            launch = { work -> safeScope.launch { work() } },
            decided = { s, accepted -> controller.suggestionDecided(s.id, accepted) },
        )
        safeScope.launch { NotesLink.setPin(NotesPin.decode(AppGraph.get(this@OverlayService).settings.notesPin())) }
        cursive = HersheyFont(asset("hershey/scripts.txt"))
        val toolbox = PhoneToolbox(phoneHost, HersheyFont(asset("hershey/romans.txt")), cursive)
        mcp = McpHttpServer(toolbox)
        controller = ChatController(
            AppGraph.get(this), safeScope, captureWindow, VoiceInput(this), Speaker(this),
            mcpConfig = mcp.cliConfig("phone"),
            onNewTurn = ::clearDrawings,
            onShotSent = { toolbox.noteScreenshot(it.width, it.height) },
        )
        watcher = NotesWatcher(
            safeScope,
            enabled = { controller.state.value.notesLive },
            ownWindows = ::ownWindows,
            isBusy = { controller.state.value.busy },
            ask = ::askFromPage,
        ).also { it.start() }
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
        // Only (re)declare the foreground types on starts that come from the app's own screen:
        // microphone is a while-in-use type that can't be added from the background, and a
        // notification tap (ACTION_EXPAND) has nothing to change. Keep the mediaProjection type
        // while a capture is live, or Android ends the projection.
        // A null intent is Android restarting us after it killed the process (START_STICKY).
        val systemRestart = intent == null
        if (!inForeground || data != null) {
            try {
                startInForeground(data != null || projectionActive, withMic = intent != null && intent.action == null)
            } catch (e: Exception) {
                // Android may refuse a foreground start from the background; don't crash over it.
                CrashLog.nonFatal(e, "startForeground")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (systemRestart) controller.continueAfterRestart()
        if (data != null) {
            val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            if (projection != null) {
                val capturer = runCatching {
                    ScreenCapturer(this, projection) {
                        lifecycleScope.launch {
                            projectionActive = false
                            controller.onProjectionStopped()
                        }
                    }
                }.getOrNull()
                projectionActive = capturer != null
                controller.attachProjection(capturer)
            }
        }
        // If Android kills the app, bring the overlay back. A screen-recording consent can't be
        // replayed, but with the accessibility service screenshots work without one.
        return START_STICKY
    }

    override fun onDestroy() {
        watcher.stop()
        controller.close()
        mcp.close()
        annotations.clear()
        suggestions.clear()
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
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishKeepClear() }
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

    override fun listen() {
        expand()
        if (!controller.state.value.listening) controller.toggleVoice()
    }

    override fun openApp() {
        collapse()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** How a screenshot gets the overlay (and its keyboard) out of the picture. */
    private val captureWindow = object : CaptureWindow {
        override suspend fun dismissKeyboard() = withContext(Dispatchers.Main) {
            // The keyboard is its own window: hiding ours leaves it on screen, over the content.
            val wasShown = hideKeyboard()
            if (!wasShown && !imeVisible()) return@withContext
            var waited = 0L
            while (imeVisible() && waited < IME_HIDE_TIMEOUT_MS) {
                delay(FRAME_MS)
                waited += FRAME_MS
            }
            // Insets flip when the slide-out starts; let it finish (or cover a device whose
            // overlay windows never receive IME insets at all).
            delay((IME_EXIT_ANIMATION_MS - waited).coerceAtLeast(FRAME_MS * 2))
        }

        override suspend fun setHidden(hidden: Boolean, keepDrawings: Boolean) = withContext(Dispatchers.Main) {
            // Window alpha, not just view visibility: an invisible view still leaves a window
            // the compositor (and Android's touch-occlusion check) accounts for.
            root.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            params.alpha = if (hidden) 0f else 1f
            update()
            if (!keepDrawings) annotations.setHidden(hidden)
            suggestions.setHidden(hidden, keepPreviews = keepDrawings)
        }
    }

    // ── Claude's phone tools ────────────────────────────────────────────────────────────────

    override fun clearDrawings() {
        annotations.clear()
        controller.setDrawingsShown(false)
    }

    /**
     * Out of the way of a gesture: invisible and untouchable, so the touch reaches the app
     * below (and Android doesn't treat our window as obscuring it).
     */
    private suspend fun stepAside(aside: Boolean) = withContext(Dispatchers.Main) {
        params.alpha = if (aside) 0f else 1f
        params.flags = if (aside) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        update()
        annotations.setHidden(aside)
        suggestions.setHidden(aside)
        if (aside) delay(INPUT_SETTLE_MS)
    }

    private val phoneHost = object : PhoneHost {
        override suspend fun screenshot(withDrawings: Boolean, maxEdge: Int): Shot? =
            controller.captureForTools(withDrawings, maxEdge)

        override fun screenSize(): Pair<Int, Int> = physicalScreenSize(this@OverlayService)

        override suspend fun readScreen(): ScreenSnapshot? {
            val service = ControlService.instance.value ?: return null
            val (sw, sh) = screenSize()
            return withContext(Dispatchers.Default) { runCatching { service.readScreen(sw, sh) }.getOrNull() }
        }

        override suspend fun showAnnotations(annotations: List<Annotation>, clearPrevious: Boolean): Long =
            withContext(Dispatchers.Main) {
                controller.setDrawingsShown(true)
                this@OverlayService.annotations.show(annotations, clearPrevious)
            }

        override suspend fun clearAnnotations() = withContext(Dispatchers.Main) { clearDrawings() }

        override fun controlBlockedReason(): String? = when {
            ControlService.instance.value == null ->
                "Phone control is unavailable: the Claude Overlay accessibility service is off. " +
                    "The user can turn it on in the app's setup (step 6)."
            !controller.state.value.controlAllowed ->
                "Phone control is switched off. The user can allow it in the overlay's ⋮ menu " +
                    "(\"Let Claude operate the phone\")."
            else -> null
        }

        override suspend fun gesture(strokes: List<Stroke>, preferStylus: Boolean): InputKind? {
            val service = ControlService.instance.value ?: return null
            Stylus.refresh()
            val pen = preferStylus && Stylus.state.value == Stylus.State.Ready
            // Let the user watch Claude work instead of the panel covering the app.
            withContext(Dispatchers.Main) { collapse() }
            stepAside(true)
            return try {
                when {
                    pen -> InputKind.Stylus.takeIf { Stylus.draw(strokes) }
                    service.perform(strokes) -> InputKind.Finger
                    else -> null
                }
            } finally {
                stepAside(false)
            }
        }

        override fun penState(): PenState {
            Stylus.refresh()
            return when (Stylus.state.value) {
                Stylus.State.Ready -> PenState.Ready
                Stylus.State.NotInstalled -> PenState.NotSetUp
                Stylus.State.NotRunning -> PenState.Stopped
                Stylus.State.NeedsPermission -> PenState.NoPermission
            }
        }

        override suspend fun typeText(text: String, submit: Boolean): String {
            val service = ControlService.instance.value ?: return "Phone control is unavailable."
            // Our own text field must not be the one holding input focus.
            withContext(Dispatchers.Main) { collapse() }
            delay(INPUT_SETTLE_MS)
            return withContext(Dispatchers.Main) { service.typeText(text, submit) }
        }

        override suspend fun pressKey(key: String): Boolean =
            withContext(Dispatchers.Main) { ControlService.instance.value?.pressKey(key) ?: false }

        override fun listApps(): List<Pair<String, String>> = launcherApps().map { it.first to it.second }

        override suspend fun openApp(query: String): String? = withContext(Dispatchers.Main) {
            val apps = launcherApps()
            val q = query.trim().lowercase()
            val match = apps.firstOrNull { it.first.lowercase() == q || it.second == query }
                ?: apps.firstOrNull { it.first.lowercase().contains(q) }
                ?: return@withContext null
            val intent = packageManager.getLaunchIntentForPackage(match.second) ?: return@withContext null
            collapse()
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            match.first
        }

        override fun notes(): NotesAccess? = NotesLink.takeIf { it.known }

        override suspend fun showSuggestion(suggestion: Suggestion) = withContext(Dispatchers.Main) {
            suggestions.show(suggestion)
        }
    }

    /**
     * The user took a suggestion: by hand (real pen strokes where the preview is, marks
     * included) when the bridge can, else, or with [typed], as text below the note's content.
     */
    private suspend fun putIntoNote(s: Suggestion, typed: Boolean): String? {
        val ink = s.ink
        if (typed || ink == null || !NotesLink.status().ink) return NotesLink.insert(s.text)
        val unit = resources.displayMetrics.density
        val marks = NotesInk.marks(s.marks.map(Ink(unit)::render), cursive)
        return NotesLink.addStrokes(ink + marks)
    }

    /** A question the user wrote on the Claude Notes page, marked for Claude ("@c …"). */
    private fun askFromPage(question: LiveTrigger.Question) {
        controller.send("✍️ ${question.text}", aboutShot = { shot ->
            NotesWatcher.message(question, shot?.let { it.width to it.height }, screenSize())
        })
    }

    /** The overlay's own windows on screen: the orb or panel, and Claude's suggestions. */
    private fun ownWindows(): List<Box> {
        val own = mutableListOf<Box>()
        if (root.isAttachedToWindow) {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            own += Box(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
        }
        return own + suggestions.areas()
    }

    /** Launchable apps as (label, package). */
    private fun launcherApps(): List<Pair<String, String>> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
    }

    private fun asset(path: String): List<String> = assets.open(path).bufferedReader().use { it.readLines() }

    private fun imeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true

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

    /** @return true if the keyboard was showing. */
    private fun hideKeyboard(): Boolean =
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)

    private fun update() {
        runCatching { wm.updateViewLayout(root, params) }
        root.post(::publishKeepClear)
    }

    /** Tells the drawing layer where the panel/orb is, so drawings never cover it. */
    private fun publishKeepClear() {
        if (!root.isAttachedToWindow) return
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        annotations.keepClear(
            androidx.compose.ui.geometry.Rect(
                loc[0].toFloat(), loc[1].toFloat(), (loc[0] + root.width).toFloat(), (loc[1] + root.height).toFloat(),
            )
        )
    }

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

    private fun startInForeground(withProjection: Boolean, withMic: Boolean) {
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
        val projectionType = if (withProjection && Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0
        // Dictation from the overlay counts as background mic use without this type.
        val micType = if (withMic && Build.VERSION.SDK_INT >= 30 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val specialType = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, projectionType or micType or specialType)
        inForeground = true
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
        private const val FRAME_MS = 16L
        private const val IME_HIDE_TIMEOUT_MS = 600L
        private const val IME_EXIT_ANIMATION_MS = 300L
        /** Time for window changes to reach the input system before a gesture. */
        private const val INPUT_SETTLE_MS = 80L
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

