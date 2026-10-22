package io.github.gaiser147.claudeoverlay.cli

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread

/**
 * A loopback-only HTTP CONNECT proxy, so the CLI never has to resolve a hostname itself.
 *
 * The CLI's musl libc reads nameservers from /etc/resolv.conf, which doesn't exist on Android,
 * so every lookup it makes fails. With HTTPS_PROXY pointing here it only ever connects to
 * 127.0.0.1 and names the host in the CONNECT line; the JVM resolves it with Android's own
 * resolver. TLS stays end-to-end between the CLI and the server; this only moves bytes.
 *
 * Any app on the device can reach a loopback port, so requests must carry a per-run random
 * credential (sent by the CLI from the user:password in [url]) or they're refused.
 */
class LocalProxy(
    private val resolve: (String) -> InetAddress = { InetAddress.getByName(it) },
) {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val secret = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val expectedAuth = "Basic " + Base64.getEncoder()
        .encodeToString("claude:$secret".toByteArray())

    val port: Int get() = server.localPort
    val url: String get() = "http://claude:$secret@127.0.0.1:$port"

    @Volatile private var running = true

    init {
        thread(name = "local-proxy", isDaemon = true) {
            while (running) {
                val client = try { server.accept() } catch (e: IOException) { break }
                thread(name = "local-proxy-conn", isDaemon = true) {
                    // A reset or dropped connection is routine (the CLI restarts, the network
                    // changes); escaping this thread would kill the whole app.
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    fun close() {
        running = false
        runCatching { server.close() }
    }

    private fun handle(client: Socket) {
        client.use {
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            val head = readHead(input) ?: return
            val requestLine = head.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3 || parts[0] != "CONNECT") {
                respond(out, "405 Method Not Allowed")
                return
            }
            val auth = head.drop(1).firstNotNullOfOrNull { line ->
                val idx = line.indexOf(':')
                if (idx > 0 && line.substring(0, idx).trim().equals("Proxy-Authorization", true)) {
                    line.substring(idx + 1).trim()
                } else null
            }
            if (auth != expectedAuth) {
                respond(out, "407 Proxy Authentication Required")
                return
            }
            val (host, port) = splitHostPort(parts[1]) ?: run {
                respond(out, "400 Bad Request")
                return
            }
            val upstream = try {
                Socket().apply { connect(InetSocketAddress(resolve(host), port), 15_000) }
            } catch (e: IOException) {
                respond(out, "502 Bad Gateway")
                return
            }
            upstream.use {
                respond(out, "200 Connection Established")
                val back = thread(name = "local-proxy-down", isDaemon = true) {
                    pump(upstream.getInputStream(), out)
                    runCatching { client.shutdownOutput() }
                }
                // Anything the client sent right after the CONNECT head is already buffered.
                pump(input, upstream.getOutputStream())
                runCatching { upstream.shutdownOutput() }
                back.join()
            }
        }
    }

    private fun respond(out: OutputStream, status: String) {
        runCatching {
            out.write("HTTP/1.1 $status\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Reads the request head (up to the blank line) as lines, or null if it's malformed. */
        internal fun readHead(input: InputStream, limit: Int = 16 * 1024): List<String>? {
            val bytes = StringBuilder()
            var total = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                bytes.append(b.toChar())
                if (++total > limit) return null
                if (bytes.endsWith("\r\n\r\n")) break
            }
            return bytes.removeSuffix("\r\n\r\n").split("\r\n")
        }

        internal fun splitHostPort(authority: String): Pair<String, Int>? {
            val idx = authority.lastIndexOf(':')
            if (idx <= 0) return null
            val host = authority.substring(0, idx).removePrefix("[").removeSuffix("]")
            val port = authority.substring(idx + 1).toIntOrNull() ?: return null
            if (port !in 1..65535 || host.isEmpty()) return null
            return host to port
        }
    }
}
