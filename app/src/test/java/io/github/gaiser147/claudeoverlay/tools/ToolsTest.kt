package io.github.gaiser147.claudeoverlay.tools

import io.github.gaiser147.claudeoverlay.capture.Shot
import io.github.gaiser147.claudeoverlay.cli.Attachment
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun font(name: String) = HersheyFont(File("src/main/assets/hershey/$name.txt").readLines())

class FakeHost(var blocked: String? = null) : PhoneHost {
    val log = mutableListOf<String>()
    val gestures = mutableListOf<List<Stroke>>()
    val strokes: List<Stroke> get() = gestures.flatten()
    var annotations: List<Annotation> = emptyList()
    override suspend fun screenshot(withDrawings: Boolean) =
        Shot(Attachment("s.jpg", "image/jpeg", byteArrayOf(1, 2)), null, 500, 1000).also { log += if (withDrawings) "shot+drawings" else "shot" }
    override fun screenSize() = 1000 to 2000
    override suspend fun showAnnotations(annotations: List<Annotation>, clearPrevious: Boolean): Long {
        this.annotations = annotations
        log += "draw"
        return 400
    }
    override suspend fun clearAnnotations() { annotations = emptyList() }
    override fun controlBlockedReason() = blocked
    /** Whether real pen input is "set up" on this fake phone. */
    var stylusReady = false
    var lastKind: InputKind? = null
    override suspend fun gesture(strokes: List<Stroke>, preferStylus: Boolean): InputKind {
        gestures += strokes
        return (if (preferStylus && stylusReady) InputKind.Stylus else InputKind.Finger).also { lastKind = it }
    }
    override suspend fun typeText(text: String, submit: Boolean) = "Typed ${text.length} characters"
    override suspend fun pressKey(key: String) = key in setOf("back", "home")
    override fun listApps() = listOf("Samsung Notes" to "com.samsung.android.app.notes", "Chrome" to "com.android.chrome")
    override suspend fun openApp(query: String) = listApps().firstOrNull { it.first.contains(query, true) }?.first
}

class PhoneToolboxTest {
    private val host = FakeHost()
    private val box = PhoneToolbox(host, font("romans"), font("scripts"), settleMs = 0, sleep = {})

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `screenshot returns the image and its size`() = runTest {
        val r = box.call("screenshot", args("{}"))
        assertFalse(r.isError)
        assertTrue(r.content[0]["text"]!!.jsonPrimitive.content.contains("500×1000"))
        assertEquals("image", r.content[1]["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `drawing maps screenshot pixels to the screen, taking a screenshot first if needed`() = runTest {
        val r = box.call("draw_annotations", args("""{"shapes":[{"type":"box","x":100,"y":200,"width":50,"height":25,"label":"hier"}]}"""))
        assertFalse(r.isError)
        // Reference screenshot first, then a check screenshot with the drawings visible.
        assertEquals(listOf("shot", "draw", "shot+drawings"), host.log)
        assertEquals("image", r.content.last()["type"]!!.jsonPrimitive.content)
        val b = host.annotations.single() as Annotation.Box
        assertEquals(Pt(200f, 400f), b.topLeft)
        assertEquals(Pt(300f, 450f), b.bottomRight)
    }

    @Test
    fun `bad shapes come back as a readable error`() = runTest {
        box.call("screenshot", args("{}"))
        val r = box.call("draw_annotations", args("""{"shapes":[{"type":"arrow","from":[1]}]}"""))
        assertTrue(r.isError)
        assertTrue(r.content[0]["text"]!!.jsonPrimitive.content.contains("from"))
    }

    @Test
    fun `control tools refuse while control is off`() = runTest {
        host.blocked = "Phone control is off."
        val r = box.call("tap", args("""{"x":1,"y":1}"""))
        assertTrue(r.isError)
        assertTrue(host.gestures.isEmpty())
    }

    @Test
    fun `tap lands on the mapped point and answers with a screenshot`() = runTest {
        box.call("screenshot", args("{}"))
        val r = box.call("tap", args("""{"x":250,"y":500}"""))
        assertFalse(r.isError)
        assertEquals(Pt(500f, 1000f), host.strokes.single().points.single())
        assertEquals("image", r.content.last()["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `handwriting becomes many short pen strokes inside the target area`() = runTest {
        box.call("screenshot", args("{}"))
        val r = box.call("write_handwriting", args("""{"text":"Hallo Wörld","x":50,"y":100,"letter_height":20}"""))
        assertFalse(r.isError)
        assertTrue(host.strokes.size > 10)
        val pts = host.strokes.flatMap { it.points }
        // x 50..~250 screenshot px → 100..~500 screen px; y 100..~125 → 200..~250.
        assertTrue(pts.all { it.x in 90f..600f && it.y in 180f..270f })
        assertTrue(host.strokes.all { it.durationMs >= Strokes.MIN_STROKE_MS })
    }

    @Test
    fun `writing uses the pen when it's set up and says so`() = runTest {
        box.call("screenshot", args("{}"))
        host.stylusReady = true
        val pen = box.call("write_handwriting", args("""{"text":"Hi","x":10,"y":10}"""))
        assertEquals(InputKind.Stylus, host.lastKind)
        assertTrue(pen.content[0]["text"]!!.jsonPrimitive.content.contains("stylus"))
    }

    @Test
    fun `without the pen, writing falls back to the finger and explains the way out`() = runTest {
        box.call("screenshot", args("{}"))
        val finger = box.call("draw_strokes", args("""{"strokes":[[[10,10],[20,20]]]}"""))
        assertEquals(InputKind.Finger, host.lastKind)
        assertTrue(finger.content[0]["text"]!!.jsonPrimitive.content.contains("Shizuku"))
    }

    @Test
    fun `taps stay finger touches even when the pen is set up`() = runTest {
        box.call("screenshot", args("{}"))
        host.stylusReady = true
        box.call("tap", args("""{"x":1,"y":1}"""))
        assertEquals(InputKind.Finger, host.lastKind)
    }

    @Test
    fun `apps open by name`() = runTest {
        val r = box.call("open_app", args("""{"name":"notes"}"""))
        assertTrue(r.content[0]["text"]!!.jsonPrimitive.content.startsWith("Opened Samsung Notes"))
        assertTrue(box.call("open_app", args("""{"name":"nope"}""")).isError)
    }
}

class HersheyFontTest {
    @Test
    fun `capitals are cap-height tall and wrapping starts a new line`() {
        val f = font("romans")
        val strokes = f.layout("H", 0f, 0f, 21f)
        val ys = strokes.flatten().map { it.y }
        assertEquals(0f, ys.min(), 0.01f)
        assertEquals(21f, ys.max(), 0.01f)
        val wrapped = f.layout("aaaa aaaa", 0f, 0f, 21f, maxWidth = 60f).flatten()
        assertTrue(wrapped.maxOf { it.y } > 30f)
    }

    @Test
    fun `umlauts get dots, sharp s becomes ss, unknown becomes a question mark`() {
        val f = font("romans")
        assertTrue(f.layout("ö", 0f, 0f, 21f).size > f.layout("o", 0f, 0f, 21f).size)
        assertEquals(f.layout("ss", 0f, 0f, 21f), f.layout("ß", 0f, 0f, 21f))
        assertEquals(f.layout("?", 0f, 0f, 21f), f.layout("€", 0f, 0f, 21f))
    }

    @Test
    fun `cursive has every printable character`() {
        val f = font("scripts")
        assertTrue(f.layout("Hausaufgabe 3: x = 42!", 0f, 0f, 21f).size > 20)
    }
}

class McpHttpServerTest {
    private val host = FakeHost()
    private val server = McpHttpServer(PhoneToolbox(host, font("romans"), font("scripts"), 0) {})
    private val auth = Json.parseToJsonElement(server.cliConfig("phone")).jsonObject["mcpServers"]!!
        .jsonObject["phone"]!!.jsonObject["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content

    private fun post(body: String, authorization: String? = auth) = server.handle(
        McpHttpServer.Request("POST", "/mcp", listOfNotNull(authorization?.let { "authorization" to it }).toMap(), body.toByteArray())
    )

    private fun result(body: String): JsonObject =
        Json.parseToJsonElement(post(body).second!!).jsonObject["result"]!!.jsonObject

    @Test
    fun `initialize echoes the client's protocol version and offers tools`() {
        val r = result("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""")
        assertEquals("2025-03-26", r["protocolVersion"]!!.jsonPrimitive.content)
        assertNotNull(r["capabilities"]!!.jsonObject["tools"])
    }

    @Test
    fun `tools are listed with schemas`() {
        val tools = result("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")["tools"] as JsonArray
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("screenshot", "draw_annotations", "tap", "write_handwriting", "type_text")))
        assertTrue(tools.all { it.jsonObject["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content == "object" })
    }

    @Test
    fun `tool calls return MCP content`() {
        val r = result("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"screenshot","arguments":{}}}""")
        assertEquals(false, r["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2, r["content"]!!.jsonArray.size)
    }

    @Test
    fun `notifications are accepted without a body, strangers are refused`() {
        assertEquals(202 to null, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertEquals(401, post("{}", authorization = "Bearer wrong").first)
        assertEquals(404, server.handle(McpHttpServer.Request("POST", "/other", mapOf("authorization" to auth), ByteArray(0))).first)
    }

    @Test
    fun `chunked and content-length requests are both read`() {
        val raw = "POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n" +
            "POST /mcp HTTP/1.1\r\nContent-Length: 2\r\n\r\nhi"
        val input = raw.byteInputStream()
        assertEquals("hello", McpHttpServer.readRequest(input)!!.body.decodeToString())
        assertEquals("hi", McpHttpServer.readRequest(input)!!.body.decodeToString())
        assertEquals(null, McpHttpServer.readRequest(input))
    }
}
