package io.github.gaiser147.claudeoverlay.stylus

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's process, which has the shell user's right to inject input (what
 * `adb shell input` uses). It plays pen strokes back as a stylus — TOOL_TYPE_STYLUS from a
 * stylus source — so pen apps such as Samsung Notes draw instead of scrolling.
 *
 * Shizuku starts it through the no-argument constructor; nothing from the app process is
 * available here.
 */
class StylusService : IStylusService.Stub() {

    /** InputManager's injectInputEvent: on InputManagerGlobal since Android 14, InputManager before. */
    private val injector: Pair<Any, Method> by lazy {
        val owner = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
            ?: Class.forName("android.hardware.input.InputManager")
        val instance = owner.getMethod("getInstance").invoke(null)!!
        instance to owner.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    /** A real stylus's device id when there is one: some apps check where events come from. */
    private val deviceId: Int by lazy {
        InputDevice.getDeviceIds().firstOrNull { id ->
            InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_STYLUS) == true
        } ?: 0
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun inject(actions: IntArray, xs: FloatArray, ys: FloatArray, pressures: FloatArray, times: LongArray): Boolean {
        val (target, method) = injector
        val start = SystemClock.uptimeMillis()
        var downTime = start
        for (i in actions.indices) {
            // Paced here rather than by the system, so strokes keep the speed they were planned at.
            val due = start + times[i]
            val wait = due - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val action = when (actions[i]) {
                StylusPlan.DOWN -> MotionEvent.ACTION_DOWN.also { downTime = due }
                StylusPlan.UP -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_STYLUS
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = xs[i]
                y = ys[i]
                pressure = pressures[i]
                size = PEN_SIZE
            })
            val event = MotionEvent.obtain(
                downTime, due, action, 1, props, coords, 0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_STYLUS, 0,
            )
            val accepted = try {
                method.invoke(target, event, INJECT_ASYNC) as Boolean
            } finally {
                event.recycle()
            }
            if (!accepted) return false
        }
        return true
    }

    private companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC: pacing is done above. */
        const val INJECT_ASYNC = 0
        const val PEN_SIZE = 0.01f
    }
}
