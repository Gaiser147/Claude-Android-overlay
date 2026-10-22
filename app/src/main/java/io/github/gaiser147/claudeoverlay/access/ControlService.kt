package io.github.gaiser147.claudeoverlay.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import io.github.gaiser147.claudeoverlay.capture.ScreenshotSource
import io.github.gaiser147.claudeoverlay.capture.Shot
import io.github.gaiser147.claudeoverlay.capture.ShotEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * The accessibility service: screenshots without a MediaProjection prompt on every start, and
 * (when the user allows it) the hands Claude uses to operate other apps.
 */
class ControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    override fun onDestroy() {
        if (_instance.value === this) _instance.value = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Off the main thread: copying a full-resolution hardware bitmap takes a few ms. */
    private val captureExecutor: Executor = Dispatchers.Default.asExecutor()

    /** The screen as a software bitmap, or null on failure. */
    @RequiresApi(30)
    suspend fun screenshot(): Bitmap? {
        repeat(2) { attempt ->
            val result = suspendCancellableCoroutine<Result<Bitmap?>> { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, captureExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(shot: ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        shot.hardwareBuffer.close()
                        cont.resume(Result.success(soft))
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(Result.failure(IllegalStateException("screenshot error $errorCode")))
                    }
                })
            }
            result.getOrNull()?.let { return it }
            // The service may take one screenshot per ~333 ms; wait out the limit once.
            if (attempt == 0) delay(SCREENSHOT_INTERVAL_MS)
        }
        return null
    }

    companion object {
        private const val SCREENSHOT_INTERVAL_MS = 350L

        private val _instance = MutableStateFlow<ControlService?>(null)
        /** The connected service, or null while it's off in the system's accessibility settings. */
        val instance: StateFlow<ControlService?> = _instance

        /** Screenshots through the service need Android 11+. */
        val canScreenshot: Boolean get() = Build.VERSION.SDK_INT >= 30
    }
}

/** Screenshots via [ControlService]: taken at call time, so only the compositor needs to settle. */
@RequiresApi(30)
class AccessibilityShots(private val service: ControlService) : ScreenshotSource {
    override suspend fun capture(notBeforeNanos: Long): Shot? {
        // Two vsyncs for the hidden overlay to leave the composited screen.
        val settle = (notBeforeNanos + SETTLE_NANOS - System.nanoTime()) / 1_000_000
        if (settle > 0) delay(settle)
        val bitmap = service.screenshot() ?: return null
        return withContext(Dispatchers.Default) { ShotEncoder.encode(bitmap) }
    }

    private companion object {
        const val SETTLE_NANOS = 50_000_000L
    }
}
