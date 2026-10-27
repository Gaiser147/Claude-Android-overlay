package io.github.gaiser147.claudeoverlay.notes

import android.graphics.Bitmap
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.gaiser147.claudeoverlay.CrashLog
import io.github.gaiser147.claudeoverlay.access.ControlService
import io.github.gaiser147.claudeoverlay.tools.Box
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * The live assistant's eyes: while a Claude Notes page is in front, looks at the screen every
 * moment (without hiding the overlay, so nothing flickers) and, once the user pauses writing,
 * reads only what changed with on-device text recognition. A question marked with the trigger
 * ("@c …", [LiveTrigger]) goes to Claude; everything else stays on the phone and costs nothing.
 */
class NotesWatcher(
    private val scope: CoroutineScope,
    /** The user wants the live assistant (overlay menu). */
    private val enabled: () -> Boolean,
    /** The overlay's own windows and previews (screen pixels; read on the main thread). */
    private val ownWindows: () -> List<Box>,
    private val isBusy: () -> Boolean,
    /** A question was found on the page; called on the main thread. */
    private val ask: (LiveTrigger.Question) -> Unit,
) {
    private var job: Job? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    /** Questions already sent, so a scroll or redraw doesn't send one again. */
    private val asked = LinkedHashSet<String>()

    fun start() {
        if (job != null || Build.VERSION.SDK_INT < 30) return
        job = scope.launch(Dispatchers.Default) {
            try {
                watch()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) CrashLog.nonFatal(e, "notes watcher")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (Build.VERSION.SDK_INT >= 30) runCatching { recognizer.close() }
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun watch() {
        var before: PageGrid? = null
        var dirty: Box? = null
        var waiting: LiveTrigger.Question? = null
        while (true) {
            delay(POLL_MS)
            val service = ControlService.instance.value
            if (!enabled() || service == null || !onNotesPage(service)) {
                before = null; dirty = null; waiting = null
                continue
            }
            waiting?.let { q ->
                if (!isBusy()) { waiting = null; withContext(Dispatchers.Main) { ask(q) } }
            }
            val shot = service.screenshot() ?: continue
            try {
                val (sw, sh) = shot.width to shot.height
                val small = Bitmap.createScaledBitmap(shot, sw / DOWNSCALE, sh / DOWNSCALE, true)
                val pixels = IntArray(small.width * small.height)
                small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
                val grid = PageGrid.of(pixels, small.width, small.height, scale = sw.toFloat() / small.width)
                if (small !== shot) small.recycle()
                val own = withContext(Dispatchers.Main) { ownWindows() }
                val changes = grid.changedSince(before, own)
                before = grid
                if (changes.isNotEmpty()) {
                    // Still writing: collect where, and read it once the pen rests.
                    dirty = (listOfNotNull(dirty) + changes).reduce(::union)
                    continue
                }
                val region = dirty ?: continue
                dirty = null
                val question = LiveTrigger.find(recognize(shot, region)) ?: continue
                if (!asked.add(LiveTrigger.key(NotesLink.state.value.noteId, question))) continue
                if (asked.size > MAX_REMEMBERED) asked.remove(asked.first())
                if (isBusy()) waiting = question else withContext(Dispatchers.Main) { ask(question) }
            } finally {
                shot.recycle()
            }
        }
    }

    /** The clone's editor is in front (the bridge says so, or the focused window is the clone's). */
    private fun onNotesPage(service: ControlService): Boolean {
        val link = NotesLink.state.value
        if (link.editorOpen) return true
        val pkg = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: return false
        return pkg == (link.pin?.packageName ?: DEFAULT_CLONE)
    }

    /**
     * The changed rows of the page, full width (a question's first words may be older than its
     * last ones), as text lines in screen pixels.
     */
    private suspend fun recognize(shot: Bitmap, region: Box): List<OcrLine> {
        val top = (region.top - MARGIN_PX).coerceAtLeast(0)
        val bottom = (region.bottom + MARGIN_PX).coerceAtMost(shot.height)
        if (bottom - top < MIN_HEIGHT_PX) return emptyList()
        val crop = Bitmap.createBitmap(shot, 0, top, shot.width, bottom - top)
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
        if (crop !== shot) crop.recycle()
        text ?: return emptyList()
        return text.textBlocks.flatMapIndexed { b, block ->
            block.lines.mapNotNull { line ->
                val r = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, Box(r.left, r.top + top, r.right, r.bottom + top), b)
            }
        }
    }

    private fun union(a: Box, b: Box) = Box(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    companion object {
        /** How often the page is looked at; one unchanged look means the pen rests. */
        const val POLL_MS = 1200L
        /** The change check runs on a screenshot this many times smaller (cheap). */
        const val DOWNSCALE = 2
        const val MARGIN_PX = 48
        const val MIN_HEIGHT_PX = 16
        const val MAX_REMEMBERED = 200
        /** The clone's package when patch.sh ran with its default NEW_ID. */
        const val DEFAULT_CLONE = "io.github.gaiser147.notesclaude"

        /**
         * What Claude is told along with the screenshot: the question as recognized and where it
         * is, in that screenshot's pixels.
         */
        fun message(q: LiveTrigger.Question, shotSize: Pair<Int, Int>?, screenSize: Pair<Int, Int>): String {
            val where = shotSize?.let { (w, h) ->
                val sx = w.toFloat() / screenSize.first
                val sy = h.toFloat() / screenSize.second
                val b = q.box
                " It is at [${(b.left * sx).roundToInt()}, ${(b.top * sy).roundToInt()}, " +
                    "${(b.right * sx).roundToInt()}, ${(b.bottom * sy).roundToInt()}] in this screenshot's pixels."
            } ?: ""
            return "[Live question from the Claude Notes page, handwritten and marked with @c. On-device recognition " +
                "read it as \"${q.text}\" — trust the handwriting in the screenshot over that.$where Answer it on the " +
                "page with notes_suggest (around = that box).]"
        }
    }
}
