package io.github.gaiser147.claudeoverlay.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

/** One published build of the CLI package, from the npm registry. */
data class CliRelease(
    val version: String,
    val tarballUrl: String,
    /** npm's Subresource-Integrity string, e.g. "sha512-<base64>". */
    val integrity: String,
)

/**
 * Downloads the official Claude Code CLI the same way its own npm installer does: the
 * platform package for linux-arm64-musl, checked against the registry's sha512 integrity.
 *
 * The binary is deliberately not bundled in the APK; it isn't ours to redistribute.
 */
class CliInstaller(private val env: CliEnvironment) {

    fun installedVersion(): String? =
        if (env.isInstalled) env.versionFile.readText().trim().ifEmpty { null } else null

    suspend fun latest(): CliRelease = withContext(Dispatchers.IO) {
        val conn = open(URL(REGISTRY_LATEST))
        val body = conn.inputStream.use { it.readBytes().decodeToString() }
        parseRelease(body)
    }

    /**
     * Streams the tarball straight into place: hashed while it downloads, extracted while it
     * decompresses, and only moved over the installed binary once the hash matched.
     */
    suspend fun install(release: CliRelease, onProgress: (done: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val (algorithm, expected) = parseIntegrity(release.integrity)
            env.cliDir.mkdirs()
            val staging = File(env.cliDir, "claude.download")
            staging.delete()
            val conn = open(URL(release.tarballUrl))
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance(algorithm)
            try {
                var done = 0L
                val counted = object : FilterInputStream(conn.inputStream) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            done += n
                            onProgress(done, total)
                        }
                        return n
                    }
                }
                DigestInputStream(counted, digest).use { hashed ->
                    val found = TarReader(GZIPInputStream(hashed, 64 * 1024))
                        .extract(BINARY_ENTRY, staging) { this@withContext.ensureActive() }
                    if (!found) throw IOException("$BINARY_ENTRY missing from ${release.tarballUrl}")
                    // The hash covers the whole tarball, including whatever follows the entry.
                    val sink = ByteArray(64 * 1024)
                    while (hashed.read(sink) >= 0) Unit
                }
                if (!MessageDigest.isEqual(digest.digest(), expected)) {
                    throw IOException("Integrity check failed for ${release.tarballUrl}")
                }
                // Read-only: nothing should rewrite a file we map executable.
                staging.setReadOnly()
                env.cliBinary.delete()
                if (!staging.renameTo(env.cliBinary)) throw IOException("Couldn't move the CLI into place")
                env.versionFile.writeText(release.version)
            } finally {
                staging.delete()
                conn.disconnect()
            }
        }

    private fun open(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json, application/octet-stream")
            if (responseCode !in 200..299) throw IOException("HTTP $responseCode for $url")
        }

    companion object {
        const val PACKAGE = "@anthropic-ai/claude-code-linux-arm64-musl"
        const val REGISTRY_LATEST = "https://registry.npmjs.org/$PACKAGE/latest"
        const val BINARY_ENTRY = "package/claude"

        private val json = Json { ignoreUnknownKeys = true }

        fun parseRelease(body: String): CliRelease {
            val root = json.parseToJsonElement(body).jsonObject
            val dist = root.getValue("dist").jsonObject
            return CliRelease(
                version = root.getValue("version").jsonPrimitive.content,
                tarballUrl = dist.getValue("tarball").jsonPrimitive.content,
                integrity = dist.getValue("integrity").jsonPrimitive.content,
            )
        }

        /** "sha512-<base64>" → ("SHA-512", bytes). Only the strong algorithms npm uses. */
        fun parseIntegrity(integrity: String): Pair<String, ByteArray> {
            val (alg, b64) = integrity.trim().split('-', limit = 2).takeIf { it.size == 2 }
                ?: throw IOException("Malformed integrity: $integrity")
            val name = when (alg) {
                "sha512" -> "SHA-512"
                "sha384" -> "SHA-384"
                "sha256" -> "SHA-256"
                else -> throw IOException("Unsupported integrity algorithm: $alg")
            }
            return name to Base64.getDecoder().decode(b64)
        }
    }
}

/**
 * Just enough of a ustar reader to pull one file out of an npm tarball: 512-byte headers,
 * octal sizes, data padded to 512. PAX and GNU long-name records are honoured for the name.
 */
class TarReader(private val input: InputStream) {

    fun extract(name: String, dest: File, checkpoint: () -> Unit = {}): Boolean {
        val header = ByteArray(BLOCK)
        var longName: String? = null
        while (true) {
            if (!readFully(header)) return false
            if (header.all { it == 0.toByte() }) return false
            val size = parseOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val padded = (size + BLOCK - 1) / BLOCK * BLOCK
            when (type) {
                'L' -> { longName = readString(size).trimEnd('\u0000'); skip(padded - size); continue }
                'x' -> { longName = paxPath(readString(size)) ?: longName; skip(padded - size); continue }
            }
            val prefix = cString(header, 345, 155)
            val entryName = longName ?: (if (prefix.isEmpty()) "" else "$prefix/") + cString(header, 0, 100)
            longName = null
            if (entryName == name && (type == '0' || type == '\u0000')) {
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var left = size
                    while (left > 0) {
                        checkpoint()
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("Truncated tar entry $name")
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                skip(padded - size)
                return true
            }
            skip(padded)
        }
    }

    private fun readFully(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return if (off == 0) false else throw IOException("Truncated tar header")
            off += n
        }
        return true
    }

    private fun readString(size: Long): String {
        val buf = ByteArray(size.toInt())
        if (!readFully(buf)) throw IOException("Truncated tar record")
        return buf.decodeToString()
    }

    private fun skip(count: Long) {
        var left = count
        val buf = ByteArray(BLOCK * 16)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Truncated tar data")
            left -= n
        }
    }

    companion object {
        private const val BLOCK = 512

        private fun parseOctal(b: ByteArray, off: Int, len: Int): Long =
            cString(b, off, len).trim().ifEmpty { "0" }.toLong(8)

        private fun cString(b: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && b[end] != 0.toByte()) end++
            return String(b, off, end - off, Charsets.UTF_8)
        }

        /** PAX records are "<len> key=value\n"; only `path` matters here. */
        private fun paxPath(records: String): String? =
            records.lineSequence().mapNotNull { line ->
                val rec = line.substringAfter(' ', "")
                if (rec.startsWith("path=")) rec.removePrefix("path=") else null
            }.firstOrNull()
    }
}
