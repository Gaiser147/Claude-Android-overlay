package io.github.gaiser147.claudeoverlay.overlay

import io.github.gaiser147.claudeoverlay.AppGraph
import io.github.gaiser147.claudeoverlay.access.AccessibilityShots
import io.github.gaiser147.claudeoverlay.access.ControlService
import io.github.gaiser147.claudeoverlay.capture.CaptureSequence
import io.github.gaiser147.claudeoverlay.capture.CaptureWindow
import io.github.gaiser147.claudeoverlay.capture.ScreenCapturer
import io.github.gaiser147.claudeoverlay.capture.ScreenshotSource
import io.github.gaiser147.claudeoverlay.capture.Shot
import io.github.gaiser147.claudeoverlay.cli.Attachment
import io.github.gaiser147.claudeoverlay.cli.ChatEvent
import io.github.gaiser147.claudeoverlay.cli.ClaudeSession
import io.github.gaiser147.claudeoverlay.cli.ModelInfo
import io.github.gaiser147.claudeoverlay.cli.ShotFingerprint
import io.github.gaiser147.claudeoverlay.cli.UserMessage
import io.github.gaiser147.claudeoverlay.voice.SentenceChunker
import io.github.gaiser147.claudeoverlay.voice.Speaker
import io.github.gaiser147.claudeoverlay.voice.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ChatItem {
    val key: Long

    data class User(override val key: Long, val text: String, val note: String?) : ChatItem
    data class Assistant(override val key: Long, val text: String) : ChatItem
    data class Tool(
        override val key: Long,
        val id: String,
        val name: String,
        val summary: String,
        val status: ToolStatus,
    ) : ChatItem
    data class Notice(override val key: Long, val text: String, val isError: Boolean) : ChatItem
}

enum class ToolStatus { Running, Done, Failed }

data class ChatUiState(
    val items: List<ChatItem> = emptyList(),
    val busy: Boolean = false,
    val model: String = ClaudeSession.DEFAULT_MODEL,
    val models: List<ModelInfo> = ClaudeSession.FALLBACK_MODELS,
    val servedModel: String? = null,
    val screenshots: Boolean = true,
    val canCapture: Boolean = false,
    val pendingFiles: List<String> = emptyList(),
    val resumableSession: String? = null,
    val needsLogin: Boolean = false,
    val contextTokens: Long = 0,
    /** A reply finished while the overlay was collapsed: the bubble shows a ✓. */
    val doneWhileCollapsed: Boolean = false,
    val expanded: Boolean = false,
    /** The composer's text; lives here so dictation can fill it in live. */
    val draft: String = "",
    val listening: Boolean = false,
    /** Microphone level while listening, 0..1. */
    val voiceLevel: Float = 0f,
    val speaking: Boolean = false,
    /** Read every reply aloud, not only replies to spoken messages. */
    val readAloud: Boolean = false,
)

/**
 * Turn logic for the overlay: capture → build the user message → stream the reply into
 * [state]. Lives as long as the overlay service.
 */
class ChatController(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    /** The overlay window, so it can step aside for screenshots. */
    captureWindow: CaptureWindow,
    private val voice: VoiceInput,
    private val speaker: Speaker,
) {
    private val captureSequence = CaptureSequence(captureWindow)
    private val session = ClaudeSession(graph.env, graph.proxy)
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    /** MediaProjection capture, only used while the accessibility service is off. */
    private var projection: ScreenCapturer? = null
    private var accessibility: ControlService? = null
    private val pending = mutableListOf<Attachment>()
    private var lastSentFingerprint: LongArray? = null
    private var nextKey = 0L
    private var interrupted = false
    /** This turn's reply is read aloud (it was spoken, or read-aloud is on). */
    private var speakTurn = false
    private var chunker = SentenceChunker()
    /** What was typed before dictation started; dictated words are appended to it. */
    private var draftBeforeVoice = ""

    init {
        scope.launch {
            val model = graph.settings.model()
            session.setModel(model)
            val models = graph.settings.models()
            _state.update {
                it.copy(
                    model = model,
                    models = models ?: it.models,
                    screenshots = graph.settings.screenshots(),
                    resumableSession = graph.settings.lastSession(),
                    readAloud = graph.settings.readAloud(),
                )
            }
            if (graph.auth.isLoggedIn()) startSession(null) else _state.update { it.copy(needsLogin = true) }
        }
        scope.launch { session.events.collect(::onEvent) }
        scope.launch { speaker.speaking.collect { on -> _state.update { it.copy(speaking = on) } } }
        scope.launch {
            ControlService.instance.collect { service ->
                accessibility = service?.takeIf { ControlService.canScreenshot }
                updateCanCapture()
            }
        }
    }

    fun attachProjection(c: ScreenCapturer?) {
        projection?.release()
        projection = c
        updateCanCapture()
    }

    fun onProjectionStopped() {
        projection = null
        updateCanCapture()
        if (accessibility == null) {
            notice("Screen capture was stopped. Restart the overlay from the app to send screenshots again.", false)
        }
    }

    /** The accessibility service when it's on (no consent prompt, exact timing), else MediaProjection. */
    private fun screenshotSource(): ScreenshotSource? =
        accessibility?.let { if (android.os.Build.VERSION.SDK_INT >= 30) AccessibilityShots(it) else null } ?: projection

    private fun updateCanCapture() = _state.update { it.copy(canCapture = screenshotSource() != null) }

    fun setExpanded(expanded: Boolean) =
        _state.update { it.copy(expanded = expanded, doneWhileCollapsed = if (expanded) false else it.doneWhileCollapsed) }

    fun setScreenshots(on: Boolean) {
        _state.update { it.copy(screenshots = on) }
        scope.launch { graph.settings.setScreenshots(on) }
    }

    fun setModel(model: String) {
        if (model.isBlank()) return
        session.setModel(model)
        // Until the CLI confirms (set_model reply / next init), show the choice, not the old model.
        _state.update { it.copy(model = model, servedModel = null) }
        scope.launch { graph.settings.setModel(model) }
    }

    fun addFiles(files: List<Attachment>) {
        pending += files
        _state.update { it.copy(pendingFiles = pending.map(Attachment::name)) }
    }

    fun clearFiles() {
        pending.clear()
        _state.update { it.copy(pendingFiles = emptyList()) }
    }

    fun setDraft(text: String) {
        if (text.isNotEmpty()) speaker.stop()
        _state.update { it.copy(draft = text) }
    }

    fun setReadAloud(on: Boolean) {
        if (!on) speaker.stop()
        _state.update { it.copy(readAloud = on) }
        scope.launch { graph.settings.setReadAloud(on) }
    }

    fun stopSpeaking() = speaker.stop()

    /** Mic button: start dictating, or finish (which sends what was said). */
    fun toggleVoice() {
        if (_state.value.listening) {
            voice.stop()
            return
        }
        if (_state.value.busy) return
        speaker.stop()
        draftBeforeVoice = _state.value.draft.trimEnd().let { if (it.isEmpty()) it else "$it " }
        _state.update { it.copy(listening = true, voiceLevel = 0f) }
        voice.start(object : VoiceInput.Listener {
            override fun onPartial(text: String) = _state.update { it.copy(draft = draftBeforeVoice + text) }
            override fun onLevel(level: Float) = _state.update { it.copy(voiceLevel = level) }
            override fun onFinal(text: String) {
                _state.update { it.copy(listening = false, voiceLevel = 0f) }
                val full = (draftBeforeVoice + text).trim()
                if (text.isBlank()) return
                send(full, spoken = true)
            }

            override fun onError(reason: VoiceInput.Reason) {
                _state.update { it.copy(listening = false, voiceLevel = 0f) }
                when (reason) {
                    VoiceInput.Reason.NoPermission ->
                        notice("Microphone access is off. Allow it in the app's setup (Settings ⋮).", true)
                    VoiceInput.Reason.Unavailable ->
                        notice("No speech recognizer is installed on this phone.", true)
                    VoiceInput.Reason.Busy -> notice("The speech recognizer is busy; try again.", false)
                    VoiceInput.Reason.NothingHeard -> Unit
                    VoiceInput.Reason.Other -> notice("Speech recognition failed; try again.", false)
                }
            }
        })
    }

    fun send(text: String, spoken: Boolean = false) {
        val s = _state.value
        if (s.busy) return
        val files = pending.toList()
        if (text.isBlank() && files.isEmpty() && !(s.screenshots && s.canCapture)) return
        clearFiles()
        interrupted = false
        speaker.stop()
        speakTurn = spoken || s.readAloud
        chunker = SentenceChunker()
        _state.update { it.copy(busy = true, resumableSession = null, draft = "") }
        scope.launch {
            val shot = if (s.screenshots) takeShot() else null
            val unchanged = shot != null && ShotFingerprint.sameScreen(shot.fingerprint, lastSentFingerprint)
            val built = withContext(Dispatchers.Default) {
                UserMessage.build(
                    text, if (unchanged) null else shot?.attachment, unchanged, files,
                    screenshotSize = shot?.let { it.width to it.height },
                )
            }
            if (shot != null && !unchanged) lastSentFingerprint = shot.fingerprint
            val note = listOfNotNull(
                when {
                    unchanged -> "🖼 unchanged"
                    shot != null -> "🖼 screen"
                    else -> null
                },
                files.takeIf { it.isNotEmpty() }?.let { "📎 ${it.size}" },
            ).joinToString("  ").ifEmpty { null }
            add { ChatItem.User(it, text, note) }
            if (built.rejected.isNotEmpty()) {
                notice("Not sent:\n" + built.rejected.joinToString("\n") { "  ${it.name} — ${it.reason}" }, true)
            }
            try {
                withContext(Dispatchers.IO) { session.send(built.line) }
            } catch (e: Exception) {
                notice("Couldn't start Claude: ${e.message}", true)
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun stop() {
        speaker.stop()
        if (!_state.value.busy) return
        interrupted = true
        scope.launch(Dispatchers.IO) { runCatching { session.interrupt() } }
    }

    fun newConversation() {
        lastSentFingerprint = null
        clearFiles()
        _state.update { it.copy(items = emptyList(), busy = false, contextTokens = 0, servedModel = null) }
        scope.launch {
            graph.settings.setLastSession(null)
            startSession(null)
        }
    }

    /** (Re)starts the CLI ahead of the first message, so a cold start doesn't delay the reply. */
    private suspend fun startSession(resume: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            session.start(_state.value.model, resume)
            true
        } catch (e: Exception) {
            notice("Couldn't start Claude: ${e.message}", true)
            false
        }
    }

    fun resumeLast() {
        val id = _state.value.resumableSession ?: return
        _state.update { it.copy(resumableSession = null) }
        scope.launch {
            if (startSession(id)) notice("Resumed your last conversation.", false)
        }
    }

    fun recheckLogin() {
        scope.launch {
            val ok = graph.auth.isLoggedIn()
            _state.update { it.copy(needsLogin = !ok) }
        }
    }

    fun close() {
        voice.cancel()
        speaker.shutdown()
        session.stop()
        projection?.release()
        projection = null
    }

    private suspend fun takeShot(): Shot? {
        val source = screenshotSource() ?: return null
        return runCatching { captureSequence.run(source) }.getOrNull()
    }

    private fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.SessionStarted -> {
                _state.update { it.copy(servedModel = event.model) }
                scope.launch { graph.settings.setLastSession(event.sessionId) }
            }
            is ChatEvent.TextDelta -> {
                appendText(event.text)
                speak(chunker.feed(event.text))
            }
            is ChatEvent.AssistantText -> {
                appendText(event.text, newBlock = true)
                speak(chunker.feed(event.text + "\n"))
            }
            is ChatEvent.ToolStarted -> add { ChatItem.Tool(it, event.id, event.name, "", ToolStatus.Running) }
            is ChatEvent.ToolCall -> updateTool(event.id) { tool ->
                tool.copy(summary = event.summary)
            } ?: add { ChatItem.Tool(it, event.id, event.name, event.summary, ToolStatus.Running) }
            is ChatEvent.ToolFinished -> updateTool(event.id) {
                it.copy(status = if (event.isError) ToolStatus.Failed else ToolStatus.Done)
            }
            is ChatEvent.ModelChanged -> _state.update { it.copy(servedModel = event.model) }
            is ChatEvent.ModelsAvailable -> if (event.models.isNotEmpty()) {
                _state.update { it.copy(models = event.models) }
                scope.launch { graph.settings.setModels(event.models) }
            }
            is ChatEvent.TurnResult -> {
                if (!interrupted) speak(chunker.flush())
                if (event.isError && !interrupted) {
                    val text = event.text?.takeIf { it.isNotBlank() } ?: "Claude reported an error (${event.subtype})."
                    notice(text, true)
                    if (looksLikeAuthError(text)) _state.update { it.copy(needsLogin = true) }
                } else if (interrupted) {
                    notice("Stopped.", false)
                }
                interrupted = false
                _state.update {
                    it.copy(
                        busy = false,
                        contextTokens = event.contextTokens.takeIf { t -> t > 0 } ?: it.contextTokens,
                        doneWhileCollapsed = !it.expanded,
                    )
                }
            }
            is ChatEvent.ProcessExited -> if (_state.value.busy) {
                val detail = event.stderrTail.lines().lastOrNull { it.isNotBlank() }?.let { ": $it" } ?: ""
                notice("Claude stopped unexpectedly (exit ${event.code})$detail. Your next message reconnects to the same conversation.", true)
                if (looksLikeAuthError(event.stderrTail)) _state.update { it.copy(needsLogin = true) }
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun appendText(text: String, newBlock: Boolean = false) = _state.update { s ->
        val last = s.items.lastOrNull()
        if (last is ChatItem.Assistant) {
            val joined = if (newBlock && last.text.isNotEmpty()) last.text + "\n\n" + text else last.text + text
            s.copy(items = s.items.dropLast(1) + last.copy(text = joined))
        } else {
            s.copy(items = s.items + ChatItem.Assistant(nextKey++, text))
        }
    }

    private fun updateTool(id: String, change: (ChatItem.Tool) -> ChatItem.Tool): Unit? {
        var found = false
        _state.update { s ->
            s.copy(items = s.items.map { item ->
                if (item is ChatItem.Tool && item.id == id) { found = true; change(item) } else item
            })
        }
        return if (found) Unit else null
    }

    private fun speak(sentences: List<String>) {
        if (speakTurn && !interrupted) sentences.forEach(speaker::speak)
    }

    private fun add(make: (Long) -> ChatItem) = _state.update { it.copy(items = it.items + make(nextKey++)) }

    private fun notice(text: String, isError: Boolean) = add { ChatItem.Notice(it, text, isError) }

    private fun looksLikeAuthError(text: String) =
        listOf("/login", "not logged in", "authentication", "oauth", "401").any { text.contains(it, ignoreCase = true) }

}
