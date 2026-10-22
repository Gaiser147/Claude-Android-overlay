package io.github.gaiser147.claudeoverlay.capture

import android.graphics.Bitmap
import io.github.gaiser147.claudeoverlay.cli.Attachment
import io.github.gaiser147.claudeoverlay.cli.ShotFingerprint
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A screenshot ready to attach: the JPEG, its fingerprint for "is the screen unchanged?", and
 * the image size, which is the coordinate space Claude's drawing and touch tools work in.
 */
class Shot(val attachment: Attachment, val fingerprint: LongArray?, val width: Int, val height: Int)

/** Somewhere a screenshot can come from: the accessibility service or a MediaProjection. */
interface ScreenshotSource {
    /**
     * Captures the screen as it looks no earlier than [notBeforeNanos] (System.nanoTime), the
     * moment the overlay was hidden, so a frame still showing the overlay is never used.
     */
    suspend fun capture(notBeforeNanos: Long, maxEdge: Int = ShotEncoder.MAX_EDGE): Shot?
}

/** What a capture needs from the overlay window. */
interface CaptureWindow {
    /** Closes the overlay's own keyboard and returns once it's off the screen (or gave up). */
    suspend fun dismissKeyboard()
    /**
     * Hides (or restores) the overlay's windows: the chat panel/orb and, unless
     * [keepDrawings], Claude's drawings.
     */
    suspend fun setHidden(hidden: Boolean, keepDrawings: Boolean = false)
}

/**
 * The order that keeps the overlay out of its own screenshots: keyboard away first (it's a
 * separate window that hiding ours doesn't remove), then our windows, then capture, and always
 * show the overlay again, even if the capture failed.
 */
class CaptureSequence(
    private val window: CaptureWindow,
    private val clock: () -> Long = System::nanoTime,
) {
    /** @param keepDrawings leave Claude's drawings in the picture, so it can check them. */
    suspend fun run(source: ScreenshotSource, keepDrawings: Boolean = false, maxEdge: Int = ShotEncoder.MAX_EDGE): Shot? {
        window.dismissKeyboard()
        window.setHidden(true, keepDrawings)
        try {
            return source.capture(clock(), maxEdge)
        } finally {
            window.setHidden(false, keepDrawings)
        }
    }
}

object ShotEncoder {
    /** Long edge of what's sent, like the desktop overlay: Claude downsamples beyond it anyway. */
    const val MAX_EDGE = 1568
    const val JPEG_QUALITY = 82

    /** Scales (long edge at most [maxEdge]), fingerprints and JPEG-encodes [bitmap]; recycles it. */
    fun encode(bitmap: Bitmap, maxEdge: Int = MAX_EDGE): Shot {
        val scale = minOf(1f, maxEdge.toFloat() / max(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true,
            ).also { if (it !== bitmap) bitmap.recycle() }
        } else bitmap
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val fingerprint = runCatching { ShotFingerprint.of(pixels, scaled.width, scaled.height) }.getOrNull()
        val jpeg = ByteArrayOutputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            it.toByteArray()
        }
        val shot = Shot(Attachment("screenshot.jpg", "image/jpeg", jpeg), fingerprint, scaled.width, scaled.height)
        scaled.recycle()
        return shot
    }
}
