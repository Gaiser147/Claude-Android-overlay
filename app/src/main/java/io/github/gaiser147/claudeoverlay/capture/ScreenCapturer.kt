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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Screenshots through MediaProjection, for when the accessibility service isn't enabled.
 *
 * Mirrors the screen into an ImageReader for as long as the overlay runs and keeps only the
 * newest frame. One VirtualDisplay for the whole projection: Android 14+ allows a consent token
 * exactly one. Frames arrive only when the screen changes, so a capture waits for the frame the
 * compositor produces after the overlay disappeared instead of taking whatever came last
 * (which would still show the overlay).
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val onStopped: () -> Unit,
) : ScreenshotSource {
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var latest: Image? = null
    /** System.nanoTime() at which [latest] arrived. */
    private var latestAt = 0L
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

    override suspend fun capture(notBeforeNanos: Long): Shot? = withContext(Dispatchers.IO) {
        ensureOrientation()
        // A frame that arrives within two vsyncs of hiding may have been composed before it.
        val fresh = notBeforeNanos + FRAME_SLACK_NANOS
        var waited = 0L
        while (synchronized(lock) { latestAt } < fresh && waited < FRESH_FRAME_TIMEOUT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
        }
        // No new frame at all means nothing changed on screen; the latest one is then current.
        val bitmap = synchronized(lock) { latest?.let(::toBitmap) } ?: return@withContext null
        ShotEncoder.encode(bitmap)
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
    private suspend fun ensureOrientation() {
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
            latestAt = 0L
        }
        old.close()
    }

    private fun newReader(size: Pair<Int, Int>) =
        ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                synchronized(lock) {
                    latest?.close()
                    latest = image
                    latestAt = System.nanoTime()
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

    /** Screen size scaled so the long edge is at most [ShotEncoder.MAX_EDGE]. */
    private fun targetSize(): Pair<Int, Int> {
        val (w, h) = screenSize(context)
        val scale = minOf(1f, ShotEncoder.MAX_EDGE.toFloat() / max(w, h))
        return (w * scale).roundToInt() to (h * scale).roundToInt()
    }

    private fun densityDpi() = context.resources.displayMetrics.densityDpi

    companion object {
        private const val FRAME_SLACK_NANOS = 34_000_000L
        private const val FRESH_FRAME_TIMEOUT_MS = 600L
        private const val POLL_MS = 16L
    }
}

/** The full physical screen size in pixels, status and navigation bars included. */
fun screenSize(context: Context): Pair<Int, Int> {
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
