package io.github.gaiser147.claudeoverlay.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.graphics.createBitmap
import io.github.gaiser147.claudeoverlay.cli.Attachment
import io.github.gaiser147.claudeoverlay.cli.ShotFingerprint
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A screenshot ready to attach, plus its fingerprint for "is the screen unchanged?". */
class Shot(val attachment: Attachment, val fingerprint: LongArray?)

/**
 * Mirrors the screen into an ImageReader for as long as the overlay runs, and hands out the
 * newest frame on demand. One VirtualDisplay for the whole projection: Android 14+ allows a
 * consent token exactly one.
 *
 * Frames arrive only when the screen changes, so the reader just keeps the most recent
 * Image around (closing the one before) rather than copying every frame.
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var latest: Image? = null
    private var reader: ImageReader
    private var display: VirtualDisplay
    private var size: Pair<Int, Int>
    @Volatile private var released = false

    init {
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // The user ended it (e.g. from the status bar), not us.
                if (!released) {
                    release()
                    onStopped()
                }
            }
        }, handler)
        size = targetSize()
        reader = newReader(size)
        display = projection.createVirtualDisplay(
            "claude-overlay", size.first, size.second, densityDpi(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
    }

    /** The newest frame as a JPEG, or null if none has arrived yet. Call off the main thread. */
    fun capture(): Shot? {
        ensureOrientation()
        val bitmap = synchronized(lock) { latest?.let(::toBitmap) } ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val fingerprint = runCatching { ShotFingerprint.of(pixels, bitmap.width, bitmap.height) }.getOrNull()
        val jpeg = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            it.toByteArray()
        }
        bitmap.recycle()
        return Shot(Attachment("screenshot.jpg", "image/jpeg", jpeg), fingerprint)
    }

    fun release() {
        if (released) return
        released = true
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        runCatching { display.release() }
        runCatching { reader.close() }
        runCatching { projection.stop() }
        thread.quitSafely()
    }

    /** Rotating the phone swaps width and height; follow it so captures aren't letterboxed. */
    private fun ensureOrientation() {
        val wanted = targetSize()
        if (wanted == size) return
        val old = reader
        reader = newReader(wanted)
        display.resize(wanted.first, wanted.second, densityDpi())
        display.surface = reader.surface
        size = wanted
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        old.close()
        // Give the mirror a moment to produce a frame at the new size.
        Thread.sleep(250)
    }

    private fun newReader(size: Pair<Int, Int>) =
        ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                synchronized(lock) {
                    latest?.close()
                    latest = image
                }
            }, handler)
        }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val padded = createBitmap(rowPixels, image.height)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPixels == image.width) return padded
        return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
    }

    /** Screen size scaled so the long edge is at most [MAX_EDGE], like the desktop overlay. */
    private fun targetSize(): Pair<Int, Int> {
        val (w, h) = screenSize()
        val scale = minOf(1f, MAX_EDGE.toFloat() / max(w, h))
        return (w * scale).roundToInt() to (h * scale).roundToInt()
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun densityDpi() = context.resources.displayMetrics.densityDpi

    companion object {
        const val MAX_EDGE = 1568
        const val JPEG_QUALITY = 82
    }
}
