package io.github.gaiser147.claudeoverlay

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installs [CrashLog] before anything else runs. */
class ClaudeOverlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Keeps the last crash (and the last swallowed error) on disk, so the setup screen can show
 * it and the user can send it on — an overlay that silently vanishes is otherwise impossible
 * to diagnose without a cable and logcat.
 */
object CrashLog {
    private lateinit var dir: File

    fun install(context: Context) {
        dir = File(context.filesDir, "crash").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(FATAL, report(e, "crash in thread ${thread.name}")) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** An error that was caught and survived, but that we still want to see. */
    fun nonFatal(e: Throwable, where: String) {
        if (!::dir.isInitialized) return
        runCatching { write(NON_FATAL, report(e, where)) }
    }

    /** The last crash report, or else the last non-fatal one; null if there is none. */
    fun latest(): String? {
        if (!::dir.isInitialized) return null
        return listOf(FATAL, NON_FATAL).map { File(dir, it) }.firstOrNull { it.isFile }?.readText()
    }

    fun clear() {
        if (!::dir.isInitialized) return
        listOf(FATAL, NON_FATAL).forEach { File(dir, it).delete() }
    }

    private fun write(name: String, text: String) = File(dir, name).writeText(text)

    private fun report(e: Throwable, where: String): String {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        return buildString {
            appendLine("Claude Overlay ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("$time · $where")
            append(trace)
        }
    }

    private const val FATAL = "last-crash.txt"
    private const val NON_FATAL = "last-error.txt"
}
