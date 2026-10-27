package io.github.gaiser147.claudeoverlay.notes

import android.os.IBinder
import android.os.RemoteException
import io.github.gaiser147.claudeoverlay.tools.InkLine
import io.github.gaiser147.claudeoverlay.tools.NoteText
import io.github.gaiser147.claudeoverlay.tools.NotesAccess
import io.github.gaiser147.claudeoverlay.tools.NotesStatus
import io.github.gaiser147.claudeoverlay.tools.PenStroke
import io.github.gaiser147.claudeoverlay.tools.Pt
import io.github.gaiser147.notesbridge.INotesBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The connection to the bridge inside the Claude Notes clone, as [NotesLinkService] sets it
 * up: who is paired, whether the clone is connected, whether its editor is in front.
 */
object NotesLink : NotesAccess {
    data class State(
        /** The clone's bridge, connected and trusted; null while the clone isn't running. */
        val bridge: INotesBridge? = null,
        val pin: NotesPin? = null,
        val editorOpen: Boolean = false,
        val noteId: String? = null,
        /** A clone tried to attach and waits for the user's pairing confirmation. */
        val awaiting: NotesPin? = null,
        /** The bridge's interface version: 2 reads and writes pen strokes. */
        val protocol: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** A clone's bridge that asked to attach, held until the user confirms the pairing. */
    private var pendingBridge: INotesBridge? = null
    private var pendingProtocol = 0

    /** Whether a clone was ever paired (or is waiting to be): the notes tools exist then. */
    val known: Boolean get() = _state.value.let { it.pin != null || it.awaiting != null }

    internal fun setPin(pin: NotesPin?) = _state.update { it.copy(pin = pin) }

    internal fun attach(bridge: INotesBridge, pin: NotesPin, protocol: Int) {
        val binder = bridge.asBinder()
        val death = IBinder.DeathRecipient { detach(bridge) }
        runCatching { binder.linkToDeath(death, 0) }
        _state.update { it.copy(bridge = bridge, pin = pin, awaiting = null, protocol = protocol) }
    }

    internal fun ask(bridge: INotesBridge, candidate: NotesPin, protocol: Int) {
        pendingBridge = bridge
        pendingProtocol = protocol
        _state.update { it.copy(awaiting = candidate) }
    }

    /** The user confirmed the pairing: the waiting bridge becomes the connection. */
    internal fun confirm(): NotesPin? {
        val candidate = _state.value.awaiting ?: return null
        val bridge = pendingBridge
        pendingBridge = null
        if (bridge != null && bridge.asBinder().isBinderAlive) attach(bridge, candidate, pendingProtocol)
        else _state.update { it.copy(pin = candidate, awaiting = null) }
        return candidate
    }

    internal fun decline() {
        pendingBridge = null
        _state.update { it.copy(awaiting = null) }
    }

    internal fun editorChanged(open: Boolean, noteId: String?) =
        _state.update { it.copy(editorOpen = open, noteId = if (open) noteId ?: it.noteId else it.noteId) }

    /** Asks the bridge whether a note is open (after pairing, it last reported before that). */
    internal fun refresh() {
        val bridge = _state.value.bridge ?: return
        val status = call { bridge.status() } ?: return
        val open = (status["editor"] as? JsonPrimitive)?.content == "true"
        editorChanged(open, status.str("noteId"))
    }

    private fun detach(bridge: INotesBridge) = _state.update {
        if (it.bridge?.asBinder() == bridge.asBinder()) it.copy(bridge = null, editorOpen = false) else it
    }

    // ── NotesAccess ────────────────────────────────────────────────────────────────────────

    override fun status(): NotesStatus = _state.value.let {
        NotesStatus(
            connected = it.bridge != null, editorOpen = it.editorOpen, noteId = it.noteId,
            awaitingPairing = it.awaiting != null, ink = it.bridge != null && it.protocol >= INK_PROTOCOL,
        )
    }

    override suspend fun addStrokes(strokes: List<InkLine>): String? = withContext(Dispatchers.IO) {
        val bridge = inkBridge() ?: return@withContext "Claude Notes isn't connected, or its bridge is too old to write by hand."
        val json = buildJsonObject {
            putJsonArray("strokes") {
                for (s in strokes) addJsonObject {
                    putJsonArray("points") { s.points.forEach { p -> add(buildJsonArray { add(p.x); add(p.y) }) } }
                    put("color", s.color)
                    put("width", s.width)
                    put("marker", s.marker)
                }
            }
        }.toString()
        val reply = call { bridge.addStrokes(json) } ?: return@withContext "Claude Notes didn't answer."
        reply.str("error")
    }

    override suspend fun newStrokes(): Result<List<PenStroke>> = withContext(Dispatchers.IO) {
        val bridge = inkBridge() ?: return@withContext Result.failure(IllegalStateException("no ink bridge"))
        val reply = call { bridge.newStrokes() } ?: return@withContext Result.failure(IllegalStateException("no answer"))
        reply.str("error")?.let { return@withContext Result.failure(IllegalStateException(it)) }
        Result.success(parseStrokes(reply))
    }

    private fun inkBridge(): INotesBridge? = _state.value.takeIf { it.protocol >= INK_PROTOCOL }?.bridge

    /** {"strokes":[{"id":7,"points":[[x,y],…],"t":[ms,…]}]} */
    internal fun parseStrokes(reply: JsonObject): List<PenStroke> =
        (reply["strokes"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val pts = (o["points"] as? JsonArray).orEmpty().mapNotNull { p ->
                val a = p as? JsonArray ?: return@mapNotNull null
                val x = (a.getOrNull(0) as? JsonPrimitive)?.floatOrNull
                val y = (a.getOrNull(1) as? JsonPrimitive)?.floatOrNull
                if (x == null || y == null) null else Pt(x, y)
            }
            val times = (o["t"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
            PenStroke((o["id"] as? JsonPrimitive)?.intOrNull ?: 0, pts, times).takeIf { pts.isNotEmpty() }
        }

    /** The bridge version that reads and writes pen strokes. */
    const val INK_PROTOCOL = 2

    override suspend fun insert(text: String): String? = withContext(Dispatchers.IO) {
        val bridge = _state.value.bridge ?: return@withContext "Claude Notes isn't connected."
        val reply = call { bridge.insertText(null, text) } ?: return@withContext "Claude Notes didn't answer."
        reply.str("error")
    }

    override suspend fun read(): Result<NoteText> = withContext(Dispatchers.IO) {
        val bridge = _state.value.bridge ?: return@withContext Result.failure(IllegalStateException("Claude Notes isn't connected."))
        val reply = call { bridge.readText(null) }
            ?: return@withContext Result.failure(IllegalStateException("Claude Notes didn't answer."))
        reply.str("error")?.let { return@withContext Result.failure(IllegalStateException(it)) }
        Result.success(NoteText(reply.str("noteId").orEmpty(), reply.str("title").orEmpty(), reply.str("text").orEmpty()))
    }

    private fun call(block: () -> String?): JsonObject? = try {
        block()?.let { Json.parseToJsonElement(it).jsonObject }
    } catch (e: RemoteException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
