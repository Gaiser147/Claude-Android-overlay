package io.github.gaiser147.claudeoverlay.overlay

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.gaiser147.claudeoverlay.cli.Attachment
import io.github.gaiser147.claudeoverlay.cli.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible trampoline: a service can't receive a document picker's result, so this opens the
 * picker, reads what was chosen and hands it to the overlay.
 */
class PickFileActivity : ComponentActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) {
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull(::read) }
            OverlayService.current?.let {
                it.controller.addFiles(files)
                it.expand()
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) picker.launch(arrayOf("image/*", "application/pdf"))
    }

    private fun read(uri: Uri): Attachment? = runCatching {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        val type = contentResolver.getType(uri) ?: "application/octet-stream"
        // Read at most one byte past the cap: enough for UserMessage to name it as too large.
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val limit = UserMessage.MAX_PDF_BYTES + 1
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return null
        Attachment(name, type, bytes)
    }.getOrNull()
}
