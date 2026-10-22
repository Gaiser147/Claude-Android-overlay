package io.github.gaiser147.claudeoverlay.cli

import io.github.gaiser147.claudeoverlay.tools.McpHttpServer
import io.github.gaiser147.claudeoverlay.tools.ToolResult
import io.github.gaiser147.claudeoverlay.tools.Toolbox
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.util.Collections

/**
 * A client that resets its connection must not take the app down. On Android an exception
 * escaping any thread kills the whole process, so the servers' connection threads must
 * swallow I/O failures (the CLI resets connections whenever it restarts or the network
 * changes).
 */
class ConnectionResetTest {
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previous: Thread.UncaughtExceptionHandler? = null

    @Before
    fun catchUncaught() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun restore() = Thread.setDefaultUncaughtExceptionHandler(previous)

    /** Sends [bytes], then aborts with a TCP reset instead of a clean close. */
    private fun resetAfter(port: Int, bytes: String) {
        val s = Socket("127.0.0.1", port)
        s.getOutputStream().write(bytes.toByteArray())
        s.getOutputStream().flush()
        Thread.sleep(50)
        s.setSoLinger(true, 0)
        s.close()
        Thread.sleep(300)
    }

    @Test
    fun `proxy survives a reset mid-request`() {
        val proxy = LocalProxy()
        try {
            resetAfter(proxy.port, "CONNECT example.test:443 HTTP/1.1\r\nHost: x")
            assertEquals(emptyList<Throwable>(), uncaught.toList())
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `mcp server survives a reset mid-request and on a kept-alive connection`() {
        val server = McpHttpServer(object : Toolbox {
            override fun definitions() = emptyList<JsonObject>()
            override suspend fun call(name: String, arguments: JsonObject) = ToolResult.text("")
        })
        try {
            resetAfter(server.port, "POST /mcp HTTP/1.1\r\nContent-Length: 100\r\n\r\n{\"jsonrpc\"")
            resetAfter(server.port, "POST /mcp HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}")
            assertEquals(emptyList<Throwable>(), uncaught.toList())
        } finally {
            server.close()
        }
    }
}
