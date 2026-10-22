package io.github.gaiser147.claudeoverlay.cli

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import kotlin.concurrent.thread

class LocalProxyTest {
    private val echo = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val resolved = mutableListOf<String>()
    private val proxy = LocalProxy(resolve = { host ->
        resolved += host
        InetAddress.getByName("127.0.0.1")
    })

    init {
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { echo.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use { it.getInputStream().copyTo(it.getOutputStream()) } }
            }
        }
    }

    @After
    fun tearDown() {
        proxy.close()
        echo.close()
    }

    private fun auth(): String {
        val userInfo = URI(proxy.url).userInfo
        return "Basic " + Base64.getEncoder().encodeToString(userInfo.toByteArray())
    }

    private fun connect(head: String): Pair<Socket, String> {
        val s = Socket("127.0.0.1", proxy.port)
        s.getOutputStream().write(head.toByteArray())
        val status = LocalProxy.readHead(s.getInputStream())!!.first()
        return s to status
    }

    @Test
    fun `tunnels bytes after resolving the host itself`() {
        val (s, status) = connect(
            "CONNECT api.example.test:${echo.localPort} HTTP/1.1\r\nHost: x\r\nProxy-Authorization: ${auth()}\r\n\r\n"
        )
        s.use {
            assertEquals("HTTP/1.1 200 Connection Established", status)
            it.getOutputStream().write("ping".toByteArray())
            val buf = ByteArray(4)
            var read = 0
            while (read < 4) read += it.getInputStream().read(buf, read, 4 - read)
            assertEquals("ping", String(buf))
        }
        assertEquals(listOf("api.example.test"), resolved)
    }

    @Test
    fun `refuses clients without the per-run credential`() {
        val (s, status) = connect("CONNECT api.example.test:${echo.localPort} HTTP/1.1\r\n\r\n")
        s.close()
        assertEquals("HTTP/1.1 407 Proxy Authentication Required", status)
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `only CONNECT is proxied`() {
        val (s, status) = connect("GET http://example.test/ HTTP/1.1\r\nProxy-Authorization: ${auth()}\r\n\r\n")
        s.close()
        assertEquals("HTTP/1.1 405 Method Not Allowed", status)
    }

    @Test
    fun `host and port parsing`() {
        assertEquals("api.anthropic.com" to 443, LocalProxy.splitHostPort("api.anthropic.com:443"))
        assertEquals("::1" to 8443, LocalProxy.splitHostPort("[::1]:8443"))
        assertEquals(null, LocalProxy.splitHostPort("nohost"))
        assertEquals(null, LocalProxy.splitHostPort("h:99999"))
    }
}
