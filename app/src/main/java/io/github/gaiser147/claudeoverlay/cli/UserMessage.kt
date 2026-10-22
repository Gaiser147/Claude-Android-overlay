package io.github.gaiser147.claudeoverlay.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** A file the user attached (or a screenshot), already read into memory. */
class Attachment(val name: String, val mimeType: String, val bytes: ByteArray)

/** Why an attachment was left out, in words the user can act on. */
data class Rejected(val name: String, val reason: String)

/**
 * Builds the stream-json user message for one turn, mirroring the desktop overlay's
 * worker._build_query + _inline_text: images inline as base64 image blocks, PDFs as native
 * document blocks, plus a one-line note so the model knows what it's looking at.
 */
object UserMessage {
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    const val MAX_PDF_BYTES = 32 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    const val MAX_ATTACHMENTS = 16

    private val imageTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    class Built(val line: String, val rejected: List<Rejected>)

    /**
     * @param screenshot a fresh capture, or null.
     * @param screenUnchanged true when a capture was taken but looked the same as the last one
     *   sent; the model is told to keep using that one instead of being sent a duplicate.
     */
    fun build(
        text: String,
        screenshot: Attachment?,
        screenUnchanged: Boolean,
        files: List<Attachment>,
    ): Built {
        val rejected = mutableListOf<Rejected>()
        val blocks = mutableListOf<JsonObject>()
        var total = 0L
        var attachedFiles = 0
        var shotAttached = false
        for (a in listOfNotNull(screenshot) + files) {
            if (blocks.size >= MAX_ATTACHMENTS) {
                rejected += Rejected(a.name, "over the $MAX_ATTACHMENTS-attachment limit for one message")
                continue
            }
            val isPdf = a.mimeType == "application/pdf"
            val isImage = a.mimeType in imageTypes
            val cap = if (isPdf) MAX_PDF_BYTES else MAX_IMAGE_BYTES
            val reason = when {
                !isPdf && !isImage -> "${a.mimeType} isn't a supported type (images and PDFs are)"
                a.bytes.isEmpty() -> "is empty"
                a.bytes.size > cap -> "${a.bytes.size / 1_048_576}MB, over the ${cap / 1_048_576}MB limit"
                total + a.bytes.size > MAX_TOTAL_BYTES ->
                    "no room left in this message (${MAX_TOTAL_BYTES / 1_048_576}MB of attachments total)"
                else -> null
            }
            if (reason != null) {
                rejected += Rejected(a.name, reason)
                continue
            }
            total += a.bytes.size
            if (a === screenshot) shotAttached = true else attachedFiles++
            blocks += buildJsonObject {
                put("type", if (isPdf) "document" else "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", a.mimeType)
                    put("data", Base64.getEncoder().encodeToString(a.bytes))
                }
            }
        }
        val prompt = promptText(text, shotAttached, screenUnchanged, attachedFiles)
        val content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", prompt) })
            blocks.forEach { add(it) }
        }
        return Built(envelope(content), rejected)
    }

    fun promptText(text: String, screenshot: Boolean, unchanged: Boolean, files: Int): String {
        val note = buildList {
            if (unchanged) add(
                "[My screen is UNCHANGED since the most recent screenshot of it earlier in this " +
                    "conversation — keep using that one. If you no longer have that screenshot in " +
                    "context, say so instead of guessing.]"
            )
            if (screenshot) add("[Attached: a live screenshot of my phone screen.]")
            if (files > 0) add("[Attached: $files file(s).]")
        }
        val body = when {
            text.isNotBlank() -> text
            screenshot || files > 0 ->
                "Look at the attached screen/file(s) and tell me what's there / what I might want help with."
            else -> "Look at my screen (the most recent screenshot earlier in this conversation — it " +
                "hasn't changed) and tell me what I might want help with."
        }
        return if (note.isEmpty()) body else note.joinToString("\n") + "\n\n" + body
    }

    fun envelope(content: JsonArray): String = buildJsonObject {
        put("type", "user")
        putJsonObject("message") {
            put("role", "user")
            put("content", content)
        }
        put("parent_tool_use_id", JsonNull)
    }.toString()
}
