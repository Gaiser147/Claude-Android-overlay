package io.github.gaiser147.claudeoverlay.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** What the UI cares about from one line of the CLI's `--output-format stream-json`. */
sealed interface ChatEvent {
    data class SessionStarted(val sessionId: String, val model: String?) : ChatEvent
    data class TextDelta(val text: String) : ChatEvent
    /** A whole text block, for messages that arrived without partial-message deltas. */
    data class AssistantText(val text: String) : ChatEvent
    data class ToolStarted(val id: String, val name: String) : ChatEvent
    data class ToolCall(val id: String, val name: String, val summary: String) : ChatEvent
    data class ToolFinished(val id: String, val isError: Boolean) : ChatEvent
    data class TurnResult(
        val isError: Boolean,
        val subtype: String,
        val text: String?,
        val sessionId: String?,
        val contextTokens: Long,
        val costUsd: Double?,
    ) : ChatEvent
    data class ModelChanged(val model: String) : ChatEvent
    /** The CLI's model menu for this login, from its `initialize` response. */
    data class ModelsAvailable(val models: List<ModelInfo>) : ChatEvent
    data class ProcessExited(val code: Int, val stderrTail: String) : ChatEvent
}

/**
 * Turns stream-json lines into [ChatEvent]s. Stateful: an assistant message's text is
 * streamed as deltas *and* repeated whole in the final `assistant` event, so the whole copy is
 * only passed on for messages that never streamed (the same `_saw_stream` guard worker.py has).
 */
class StreamJsonParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val streamedMessages = HashSet<String>()
    private var currentMessageId: String? = null

    fun parse(line: String): List<ChatEvent> {
        val root = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject
            ?: return emptyList()
        return when (root.str("type")) {
            "system" -> system(root)
            "stream_event" -> streamEvent(root["event"] as? JsonObject ?: return emptyList())
            "assistant" -> assistant(root)
            "user" -> user(root)
            "result" -> listOf(result(root))
            "control_response" -> controlResponse(root)
            else -> emptyList()
        }
    }

    private fun system(root: JsonObject): List<ChatEvent> =
        if (root.str("subtype") == "init") {
            val sid = root.str("session_id") ?: return emptyList()
            listOf(ChatEvent.SessionStarted(sid, root.str("model")))
        } else emptyList()

    private fun streamEvent(event: JsonObject): List<ChatEvent> {
        when (event.str("type")) {
            "message_start" -> currentMessageId = event.obj("message")?.str("id")
            "content_block_start" -> {
                val block = event.obj("content_block") ?: return emptyList()
                if (block.str("type") == "tool_use") {
                    return listOf(ChatEvent.ToolStarted(block.str("id") ?: "", displayName(block.str("name") ?: "tool")))
                }
            }
            "content_block_delta" -> {
                val delta = event.obj("delta") ?: return emptyList()
                if (delta.str("type") == "text_delta") {
                    val text = delta.str("text").orEmpty()
                    currentMessageId?.let { streamedMessages += it }
                    if (text.isNotEmpty()) return listOf(ChatEvent.TextDelta(text))
                }
            }
        }
        return emptyList()
    }

    private fun assistant(root: JsonObject): List<ChatEvent> {
        val message = root.obj("message") ?: return emptyList()
        val streamed = message.str("id")?.let { it in streamedMessages } == true
        return message.blocks().mapNotNull { block ->
            when (block.str("type")) {
                "text" -> if (streamed) null else block.str("text")?.takeIf { it.isNotBlank() }
                    ?.let { ChatEvent.AssistantText(it) }
                "tool_use" -> {
                    val name = displayName(block.str("name") ?: "tool")
                    ChatEvent.ToolCall(block.str("id") ?: "", name, summarizeTool(name, block.obj("input")))
                }
                else -> null
            }
        }
    }

    private fun user(root: JsonObject): List<ChatEvent> =
        root.obj("message")?.blocks().orEmpty().mapNotNull { block ->
            if (block.str("type") == "tool_result") {
                ChatEvent.ToolFinished(
                    block.str("tool_use_id") ?: "",
                    (block["is_error"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            } else null
        }

    private fun result(root: JsonObject): ChatEvent {
        val usage = root.obj("usage")
        val context = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
            .sumOf { (usage?.get(it) as? JsonPrimitive)?.longOrNull ?: 0L }
        return ChatEvent.TurnResult(
            isError = (root["is_error"] as? JsonPrimitive)?.booleanOrNull == true,
            subtype = root.str("subtype") ?: "",
            text = root.str("result"),
            sessionId = root.str("session_id"),
            contextTokens = context,
            costUsd = (root["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull,
        )
    }

    private fun controlResponse(root: JsonObject): List<ChatEvent> {
        val response = root.obj("response")?.obj("response") ?: return emptyList()
        (response["models"] as? JsonArray)?.let { models ->
            return listOf(ChatEvent.ModelsAvailable(ModelInfo.parseList(models)))
        }
        return response.str("model")?.let { listOf(ChatEvent.ModelChanged(it)) } ?: emptyList()
    }

    companion object {
        /** A one-line description of a tool call for its chip, like the desktop overlay shows. */
        /** "mcp__phone__tap" → "tap": the app's own tools need no server prefix in the chip. */
        fun displayName(name: String): String = name.removePrefix("mcp__phone__")

        fun summarizeTool(name: String, input: JsonObject?): String {
            if (input == null) return ""
            val key = when (name) {
                "Bash" -> listOf("description", "command")
                "Read", "Write", "Edit", "NotebookEdit" -> listOf("file_path", "notebook_path")
                "Grep", "Glob" -> listOf("pattern")
                "WebFetch" -> listOf("url")
                "WebSearch" -> listOf("query")
                "Task", "Agent" -> listOf("description")
                else -> listOf("description", "command", "file_path", "path", "url", "query", "pattern", "text", "name", "key")
            }.firstNotNullOfOrNull { input.str(it)?.takeIf(String::isNotBlank) } ?: return ""
            val oneLine = key.replace(Regex("\\s+"), " ").trim()
            return if (oneLine.length > 80) oneLine.take(79) + "…" else oneLine
        }
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.blocks(): List<JsonObject> =
    (this["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

