package io.github.gaiser147.claudeoverlay.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread

/** What a tool call returns: MCP content blocks (text, image) and whether it failed. */
data class ToolResult(val content: List<JsonObject>, val isError: Boolean = false) {
    companion object {
        fun text(text: String, isError: Boolean = false) = ToolResult(listOf(textBlock(text)), isError)
        fun textBlock(text: String) = buildJsonObject { put("type", "text"); put("text", text) }
        fun imageBlock(base64: String, mimeType: String) = buildJsonObject {
            put("type", "image"); put("data", base64); put("mimeType", mimeType)
        }
    }
}

/** The tools behind the server. */
interface Toolbox {
    /** MCP tool definitions: name, description, inputSchema. */
    fun definitions(): List<JsonObject>
    suspend fun call(name: String, arguments: JsonObject): ToolResult
}

/**
 * A minimal MCP server (Streamable HTTP transport, plain JSON responses) on loopback, so the
 * CLI can call the app's own tools: drawing on the screen and operating the phone.
 *
 * The CLI connects to it from `--mcp-config` like to any remote MCP server. Any app on the
 * device can reach a loopback port, so every request must carry the per-run bearer secret.
 */
class McpHttpServer(private val toolbox: Toolbox) {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    @Volatile private var running = true
    private val json = Json { ignoreUnknownKeys = true }

    val port: Int get() = server.localPort
    val url: String get() = "http://127.0.0.1:$port/mcp"

    init {
        thread(name = "mcp-server", isDaemon = true) {
            while (running) {
                val client = try { server.accept() } catch (e: IOException) { break }
                thread(name = "mcp-conn", isDaemon = true) {
                    // The CLI drops its connection whenever it exits or restarts; an exception
                    // escaping this thread would kill the whole app.
                    try {
                        serve(client)
                    } catch (e: Exception) {
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    /** The value for the CLI's `--mcp-config`, registering this server as [name]. */
    fun cliConfig(name: String): String = buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject(name) {
                put("type", "http")
                put("url", url)
                putJsonObject("headers") { put("Authorization", "Bearer $secret") }
            }
        }
    }.toString()

    fun close() {
        running = false
        runCatching { server.close() }
    }

    private fun serve(socket: Socket) = socket.use {
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        // HTTP/1.1 keep-alive: the client reuses the connection for the whole session.
        while (true) {
            val request = readRequest(input) ?: return
            val (status, body) = handle(request)
            writeResponse(out, status, body)
        }
    }

    internal class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    internal fun handle(request: Request): Pair<Int, String?> {
        if (request.headers["authorization"] != "Bearer $secret") return 401 to null
        if (request.path.substringBefore('?') != "/mcp") return 404 to null
        return when (request.method) {
            "POST" -> rpc(request.body.decodeToString())
            // No server-initiated stream; the client falls back to request/response.
            "GET" -> 405 to null
            "DELETE" -> 200 to null
            else -> 405 to null
        }
    }

    private fun rpc(body: String): Pair<Int, String?> {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return 400 to error(JsonNull, -32700, "Parse error").toString()
        val responses = when (parsed) {
            is JsonArray -> parsed.mapNotNull { (it as? JsonObject)?.let(::dispatch) }
            is JsonObject -> listOfNotNull(dispatch(parsed))
            else -> listOf(error(JsonNull, -32600, "Invalid request"))
        }
        return when {
            responses.isEmpty() -> 202 to null
            parsed is JsonArray -> 200 to JsonArray(responses).toString()
            else -> 200 to responses.single().toString()
        }
    }

    /** One JSON-RPC message → its response, or null for a notification. */
    private fun dispatch(msg: JsonObject): JsonObject? {
        val id = msg["id"] ?: return null
        val method = (msg["method"] as? JsonPrimitive)?.content ?: return error(id, -32600, "Invalid request")
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, buildJsonObject {
                put("protocolVersion", (params["protocolVersion"] as? JsonPrimitive)?.content ?: PROTOCOL_VERSION)
                putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                putJsonObject("serverInfo") { put("name", "claude-overlay-phone"); put("version", "1") }
            })
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(toolbox.definitions())) })
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.content ?: return error(id, -32602, "Missing tool name")
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val r = try {
                    runBlocking { toolbox.call(name, args) }
                } catch (e: Exception) {
                    ToolResult.text("${e.javaClass.simpleName}: ${e.message}", isError = true)
                }
                result(id, buildJsonObject {
                    put("content", JsonArray(r.content))
                    put("isError", r.isError)
                })
            }
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        putJsonObject("error") { put("code", code); put("message", message) }
    }

    private fun writeResponse(out: OutputStream, status: Int, body: String?) {
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val reason = when (status) {
            200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; else -> "Method Not Allowed"
        }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            if (body != null) append("Content-Type: application/json\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private const val MAX_BODY = 8 * 1024 * 1024

        /** Reads one HTTP/1.1 request (Content-Length or chunked body), or null at end of stream. */
        internal fun readRequest(input: InputStream): Request? {
            val headLines = readHead(input) ?: return null
            val parts = headLines.first().split(" ")
            if (parts.size < 2) return null
            val headers = headLines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            val body = when {
                headers["transfer-encoding"]?.contains("chunked", true) == true -> readChunked(input)
                else -> {
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    if (len > MAX_BODY) throw IOException("Request too large")
                    readExactly(input, len)
                }
            }
            return Request(parts[0], parts[1], headers, body)
        }

        private fun readHead(input: InputStream): List<String>? {
            val line = StringBuilder()
            val lines = mutableListOf<String>()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) {
                    val l = line.toString().removeSuffix("\r")
                    line.clear()
                    if (l.isEmpty()) {
                        if (lines.isEmpty()) continue
                        return lines
                    }
                    lines += l
                    if (lines.size > 100) return null
                } else {
                    line.append(b.toChar())
                    if (line.length > 16 * 1024) return null
                }
            }
        }

        private fun readExactly(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw IOException("Truncated body")
                off += r
            }
            return buf
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input) ?: throw IOException("Truncated chunk")
                val size = sizeLine.substringBefore(';').trim().toInt(16)
                if (size == 0) {
                    while (readLine(input)?.isNotEmpty() == true) Unit
                    return out.toByteArray()
                }
                if (out.size() + size > MAX_BODY) throw IOException("Request too large")
                out.write(readExactly(input, size))
                readLine(input)
            }
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return sb.toString().removeSuffix("\r")
                sb.append(b.toChar())
            }
        }
    }
}

