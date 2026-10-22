package io.github.gaiser147.claudeoverlay.stylus

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.github.gaiser147.claudeoverlay.BuildConfig
import io.github.gaiser147.claudeoverlay.CrashLog
import io.github.gaiser147.claudeoverlay.tools.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Real pen input through Shizuku, for apps that only draw with a stylus (Samsung Notes on S Pen
 * devices). Android lets ordinary apps — accessibility services included — inject touches only
 * as a finger; Shizuku runs a small service for us with the shell user's input rights, the same
 * ones `adb shell input` has. Optional: without it, strokes stay finger gestures.
 */
object Stylus {
    enum class State {
        /** The Shizuku app isn't installed. */
        NotInstalled,
        /** Installed, but not started (it must be started again after every reboot). */
        NotRunning,
        /** Running; the user hasn't let this app use it yet. */
        NeedsPermission,
        Ready,
    }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST = 4711
    private const val BIND_TIMEOUT_MS = 8_000L
    /** Events per binder call; a call's payload must stay well under the 1 MB binder limit. */
    private const val CHUNK_EVENTS = 3_000

    private val _state = MutableStateFlow(State.NotRunning)
    val state: StateFlow<State> = _state

    private var appContext: Context? = null
    @Volatile private var service: IStylusService? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, StylusService::class.java.name))
            .daemon(false)
            .processNameSuffix("stylus")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun refresh() {
        val context = appContext ?: return
        _state.value = runCatching {
            when {
                // A running binder also covers Sui, Shizuku's root variant without the app.
                Shizuku.pingBinder() && !Shizuku.isPreV11() ->
                    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) State.Ready
                    else State.NeedsPermission
                installed(context) -> State.NotRunning
                else -> State.NotInstalled
            }
        }.getOrDefault(State.NotRunning)
    }

    fun requestPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    /** Draws [strokes] as a stylus; false if Shizuku isn't ready or Android refused. */
    suspend fun draw(strokes: List<Stroke>): Boolean {
        val s = bind() ?: return false
        return withContext(Dispatchers.IO) {
            try {
                chunks(StylusPlan.plan(strokes)).all { chunk ->
                    val t0 = chunk.first().timeMs
                    s.inject(
                        IntArray(chunk.size) { chunk[it].action },
                        FloatArray(chunk.size) { chunk[it].x },
                        FloatArray(chunk.size) { chunk[it].y },
                        FloatArray(chunk.size) { chunk[it].pressure },
                        LongArray(chunk.size) { chunk[it].timeMs - t0 },
                    )
                }
            } catch (e: Exception) {
                CrashLog.nonFatal(e, "stylus inject")
                service = null
                false
            }
        }
    }

    /** Splits the plan into binder-sized pieces, only while the pen is in the air. */
    internal fun chunks(events: List<PenEvent>, max: Int = CHUNK_EVENTS): List<List<PenEvent>> {
        val out = mutableListOf<List<PenEvent>>()
        var current = mutableListOf<PenEvent>()
        for (e in events) {
            if (e.action == StylusPlan.HOVER_ENTER && current.size >= max) {
                out += current
                current = mutableListOf()
            }
            current += e
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private suspend fun bind(): IStylusService? {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        refresh()
        if (_state.value != State.Ready) return null
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val s = binder?.takeIf { it.pingBinder() }?.let(IStylusService.Stub::asInterface)
                            service = s
                            if (cont.isActive) cont.resume(s)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            service = null
                        }
                    }
                    runCatching { Shizuku.bindUserService(serviceArgs, connection) }
                        .onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        }
    }

    private fun installed(context: Context) =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
}
