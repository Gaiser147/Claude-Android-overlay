package io.github.gaiser147.claudeoverlay.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.gaiser147.claudeoverlay.R
import io.github.gaiser147.claudeoverlay.tools.Suggestion
import kotlin.math.roundToInt

/**
 * Claude's suggestions over the Claude Notes page: each one's preview (its own drawing layer,
 * so it stays when Claude's other drawings are cleared) and a small ✓/✗ bar below it. ✓ hands
 * the text to [accept]; the suggestion goes away once that succeeds, or on ✗.
 */
class SuggestionLayer(
    private val context: Context,
    private val owner: Any,
    /** Inserts the text into the note; returns an error message, or null when it worked. */
    private val accept: suspend (Suggestion) -> String?,
    /** Runs [accept] off the click (the service's scope). */
    private val launch: (suspend () -> Unit) -> Unit,
    /** The user decided: ✓ worked (true) or ✗ (false). */
    private val decided: (Suggestion, Boolean) -> Unit,
) {
    private class Shown(val suggestion: Suggestion, val layer: AnnotationLayer, val bar: View, val params: WindowManager.LayoutParams)

    private val wm = context.getSystemService(WindowManager::class.java)
    private val shown = mutableListOf<Shown>()
    private var hidden = false

    fun show(suggestion: Suggestion) {
        val layer = AnnotationLayer(context, owner).apply {
            show(suggestion.preview, clearPrevious = true)
            if (hidden) setHidden(true)
        }
        val (bar, params) = bar(suggestion)
        runCatching { wm.addView(bar, params) }
        shown += Shown(suggestion, layer, bar, params)
    }

    /** Out of screenshots and gestures, like the overlay's other windows. */
    fun setHidden(hidden: Boolean, keepPreviews: Boolean = false) {
        this.hidden = hidden
        for (s in shown) {
            if (!keepPreviews) s.layer.setHidden(hidden)
            s.params.alpha = if (hidden) 0f else 1f
            s.params.flags = if (hidden) s.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else s.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(s.bar, s.params) }
        }
    }

    fun clear() = shown.toList().forEach(::remove)

    private fun remove(s: Shown) {
        shown.remove(s)
        s.layer.clear()
        runCatching { wm.removeView(s.bar) }
    }

    private fun bar(suggestion: Suggestion): Pair<View, WindowManager.LayoutParams> {
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = suggestion.at.x.roundToInt()
            y = (suggestion.bottom + 6 * density).roundToInt()
            alpha = if (hidden) 0f else 1f
        }
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        lateinit var view: ComposeView
        view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner as LifecycleOwner)
            setViewTreeSavedStateRegistryOwner(owner as SavedStateRegistryOwner)
            setContent {
                Row(
                    Modifier
                        .shadow(4.dp, RoundedCornerShape(18.dp))
                        .background(Color(0xFFF7F9FF), RoundedCornerShape(18.dp))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val blue = Color(Suggestion.PREVIEW_COLOR)
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        error = null
                        launch {
                            val failed = accept(suggestion)
                            busy = false
                            if (failed == null) {
                                shown.firstOrNull { it.bar === view }?.let(::remove)
                                decided(suggestion, true)
                            } else error = failed
                        }
                    }) {
                        if (busy) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(16.dp), color = blue, strokeWidth = 2.dp)
                        else Icon(Icons.Default.Check, null, tint = blue)
                        Text(stringResource(R.string.suggestion_accept), color = blue, fontSize = 13.sp)
                    }
                    TextButton(enabled = !busy, onClick = {
                        shown.firstOrNull { it.bar === view }?.let(::remove)
                        decided(suggestion, false)
                    }) {
                        Icon(Icons.Default.Close, stringResource(R.string.suggestion_dismiss), tint = Color(0xFF6B6F76))
                    }
                    error?.let { Text(it, color = Color(0xFFB3261E), fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp)) }
                }
            }
        }
        return view to params
    }
}
