package io.github.gaiser147.claudeoverlay.cli

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * One long-lived `claude -p` process speaking stream-json on stdin/stdout — what the Python
 * claude-agent-sdk does for the desktop overlay, minus the SDK.
 *
 * Turns are written to stdin as user messages; every stdout line goes through
 * [StreamJsonParser] into [events]. If the process dies (crash, OOM kill) the next [send]
 * starts a new one with `--resume <session>`, so the conversation carries on.
 */
class ClaudeSession(
    private val env: CliEnvironment,
    private val proxy: LocalProxy,
    /** `--mcp-config` for the app's own tool server (drawing on and operating the phone). */
    private val mcpConfig: String? = null,
) {
    private val channel = Channel<ChatEvent>(Channel.UNLIMITED)
    val events: Flow<ChatEvent> = channel.receiveAsFlow()

    @Volatile var sessionId: String? = null
        private set
    @Volatile var model: String = DEFAULT_MODEL
        private set
    /** Reasoning effort (low … max), or null for the model's default. */
    @Volatile var effort: String? = null
        private set

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private val requestIds = AtomicInteger()
    private val lock = Any()

    val isAlive: Boolean get() = process?.isAlive == true

    private var startedWithCredentials: Pair<Long, Long>? = null

    /**
     * The stored login changed since this process started (a new sign-in, or a refresh by
     * another process): it may hold superseded tokens and should be restarted before use.
     */
    fun credentialsChanged(): Boolean =
        isAlive && Credentials.signature(Credentials.file(env.home)) != startedWithCredentials

    /** Starts fresh, or resumes [resume] (a session id from an earlier run). */
    fun start(model: String, resume: String? = null) = synchronized(lock) {
        stopLocked(waitForExit = true)
        this.model = model
        sessionId = resume
        val args = buildList {
            addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json"))
            addAll(listOf("--verbose", "--include-partial-messages"))
            addAll(listOf("--model", model, "--permission-mode", "bypassPermissions"))
            effort?.let { addAll(listOf("--effort", it)) }
            addAll(listOf("--append-system-prompt", SYSTEM_APPEND))
            // No interactive question UI here; the model asks inline instead (config.DISALLOWED_TOOLS).
            addAll(listOf("--disallowedTools", "AskUserQuestion"))
            // Only the MCP servers declared here (the app's own), not every one in the user's config.
            add("--strict-mcp-config")
            if (mcpConfig != null) addAll(listOf("--mcp-config", mcpConfig))
            if (resume != null) addAll(listOf("--resume", resume))
        }
        startedWithCredentials = Credentials.signature(Credentials.file(env.home))
        val p = env.processBuilder(args, proxy).start()
        process = p
        stdin = p.outputStream.bufferedWriter()
        // Ask for the CLI's model menu (entitled models with their current versions) up front,
        // so the picker is filled before the first message.
        control { put("subtype", "initialize") }
        val parser = StreamJsonParser()
        val stderr = ArrayDeque<String>()
        thread(name = "claude-stderr", isDaemon = true) {
            runCatching {
                p.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderr) {
                        stderr.addLast(line)
                        if (stderr.size > 20) stderr.removeFirst()
                    }
                }
            }
        }
        thread(name = "claude-stdout", isDaemon = true) {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    for (event in parser.parse(line)) {
                        if (event is ChatEvent.SessionStarted) sessionId = event.sessionId
                        if (event is ChatEvent.ModelChanged) this.model = event.model
                        channel.trySend(event)
                    }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            // Only report the death of the current process, not one we replaced on purpose.
            if (process === p) {
                channel.trySend(ChatEvent.ProcessExited(code, synchronized(stderr) { stderr.joinToString("\n") }))
            }
        }
    }

    /** Sends one turn; restarts the process into the same conversation if it had died. */
    fun send(line: String) = synchronized(lock) {
        if (process?.isAlive != true) start(model, sessionId)
        write(line)
    }

    fun interrupt() = synchronized(lock) {
        if (process?.isAlive == true) control { put("subtype", "interrupt") }
    }

    /** Switches model in place; takes effect from the next turn. */
    fun setModel(model: String) = synchronized(lock) {
        this.model = model
        if (process?.isAlive == true) control { put("subtype", "set_model"); put("model", model) }
    }

    /**
     * Sets the reasoning effort ([level] null: the model's default); takes effect from the
     * next turn. The CLI caps it at what the current model supports.
     */
    fun setEffort(level: String?) = synchronized(lock) {
        effort = level
        if (process?.isAlive == true) control {
            put("subtype", "apply_flag_settings")
            putJsonObject("settings") { put("effortLevel", level) }
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    /**
     * Ends the process gently: closing stdin makes the CLI finish and exit on its own. Killing
     * it outright could land in the middle of a token refresh, and the rotated refresh token
     * would be lost with it (the user would have to sign in again). Only a CLI that doesn't
     * exit within a grace period is terminated.
     */
    private fun stopLocked(waitForExit: Boolean = false) {
        val p = process ?: return
        process = null
        runCatching { stdin?.close() }
        stdin = null
        val finish = {
            val exited = runCatching { p.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!exited) {
                p.destroy()
                runCatching { p.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS) }
            }
        }
        // Before starting a replacement, wait: two CLIs at once may both refresh the token.
        if (waitForExit) finish() else thread(name = "claude-stop", isDaemon = true) { finish() }
    }

    private fun control(request: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        write(buildJsonObject {
            put("type", "control_request")
            put("request_id", "req_${requestIds.incrementAndGet()}")
            putJsonObject("request", request)
        }.toString())
    }

    private fun write(line: String) {
        val w = stdin ?: throw IOException("Claude isn't running")
        w.write(line)
        w.newLine()
        w.flush()
    }

    companion object {
        const val DEFAULT_MODEL = "opus"
        private const val STOP_GRACE_MS = 3000L

        /** Shown until the CLI has reported its own menu once (then that list is cached). */
        val FALLBACK_MODELS = listOf("opus", "fable", "sonnet", "haiku").map {
            ModelInfo(it, null, it.replaceFirstChar(Char::uppercase), "", ModelInfo.DEFAULT_EFFORT_LEVELS)
        }

        /** Android counterpart of the desktop overlay's config.SYSTEM_APPEND. */
        val SYSTEM_APPEND = """
            You are running as a floating overlay assistant on top of whatever app the user has open on their Android phone. The user talks to you without leaving their current app. Messages may include a live screenshot of the phone screen, attached directly as an image. Use it to see what the user is looking at, then help.
            Keep replies concise and skimmable since they render in a small floating window on a phone; expand only when asked.
            If you need to ask the user something, ask it inline as plain text and wait for their typed reply. This overlay is a plain chat with no interactive question UI, so never use a structured multiple-choice question tool.
            Your shell is bash inside this app's private Android sandbox, without root. Only Android's toybox utilities are installed (ls, cat, grep, find, sed and similar; no curl, git, python or package manager). Your working directory is a private scratch folder; use WebFetch and WebSearch for anything online.
            The phone tools (mcp__phone__*) work on the user's real screen. Coordinates are always pixels in the most recent screenshot, whose size is stated with it. Be frugal with images, they are the expensive part: to find and operate things use read_screen (the screen as numbered text elements) and the text every action returns, tap or type into elements by number (type_text with element and submit does field, text and enter in one call), use wait_for instead of guessing load times, and take a screenshot only when you need to see pictures, layout, ink or something the text lacks (detail 'low' is often enough). For public information (web pages, docs) WebFetch and WebSearch are cheaper than operating apps. Use draw_annotations to point things out visually (arrows, boxes, circles, highlights, short labels) when showing beats describing — e.g. where to tap, which part of a document or homework task you mean, or marking mistakes; keep drawings few and clear, and clear them when they're no longer relevant.
            If the user has switched on phone control you can also open apps, tap, swipe, type, draw with strokes and write by hand (write_handwriting, e.g. into Samsung Notes). Taps and swipes arrive as a finger; draw_strokes and write_handwriting use the real stylus when the user has set it up (the tool result says which), else a finger, which note apps that only draw with the pen treat as panning: then follow the tool's advice. In note apps every stroke is one undo step and the stroke eraser removes whole strokes, so undo a mistake right away rather than erasing near other ink. Work like a careful person: check what each action returns before the next step, and stop to ask before anything irreversible or outward-facing (sending messages, posting, purchases, deleting, changing settings). If a control tool reports that control is off, tell the user how to enable it instead of retrying.
        """.trimIndent().replace("\n", " ")
    }
}
