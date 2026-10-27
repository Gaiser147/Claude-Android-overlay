package io.github.gaiser147.claudeoverlay.notes

import android.graphics.Bitmap
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.gaiser147.claudeoverlay.CrashLog
import io.github.gaiser147.claudeoverlay.access.ControlService
import io.github.gaiser147.claudeoverlay.tools.Box
import io.github.gaiser147.claudeoverlay.tools.PenStroke
import android.os.SystemClock
import android.util.Log
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
    private val handwriting = InkReader()
    /** The user's strokes since the pen last rested, and when the last one came. */
    private val batch = ArrayDeque<PenStroke>()
    private var lastStrokeAt = 0L
    /** Questions already sent, so a scroll or redraw doesn't send one again. */
    private val asked = LinkedHashSet<String>()

    fun start() {
        if (job != null) return
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
        runCatching { recognizer.close() }
        handwriting.close()
    }

    private suspend fun watch() {
        var before: PageGrid? = null
        var dirty: Box? = null
        var waiting: LiveTrigger.Question? = null
        suspend fun found(question: LiveTrigger.Question) {
            if (!asked.add(LiveTrigger.key(NotesLink.state.value.noteId, question))) return
            if (asked.size > MAX_REMEMBERED) asked.remove(asked.first())
            Log.i(TAG, "question on the page: ${question.text}")
            if (isBusy()) waiting = question else withContext(Dispatchers.Main) { ask(question) }
        }
        while (true) {
            val link = NotesLink.status()
            // With a stroke-reading bridge: the user's pen strokes, read as handwriting.
            val byStrokes = link.ink && link.editorOpen
            delay(if (byStrokes) INK_POLL_MS else POLL_MS)
            if (!enabled()) {
                before = null; dirty = null; waiting = null; batch.clear()
                continue
            }
            waiting?.let { q ->
                if (!isBusy()) { waiting = null; withContext(Dispatchers.Main) { ask(q) } }
            }
            if (byStrokes) {
                before = null; dirty = null
                fromStrokes()?.let { found(it) }
                continue
            }
            // Otherwise: what changed on screen, read with text recognition (Android 11+).
            val service = ControlService.instance.value
            if (Build.VERSION.SDK_INT < 30 || service == null || !onNotesPage(service)) {
                before = null; dirty = null; waiting = null
                continue
            }
            val shot = (if (Build.VERSION.SDK_INT >= 30) service.screenshot() else null) ?: continue
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
                LiveTrigger.find(recognize(shot, region))?.let { found(it) }
            } finally {
                shot.recycle()
            }
        }
    }

    /**
     * New strokes from the bridge; once the pen has rested a moment, the batch is read as
     * handwriting and checked for the trigger.
     */
    private suspend fun fromStrokes(): LiveTrigger.Question? {
        if (!handwriting.ready()) return null
        val fresh = NotesLink.newStrokes().getOrElse {
            Log.w(TAG, "strokes: ${it.message}")
            return null
        }
        val now = SystemClock.uptimeMillis()
        if (fresh.isNotEmpty()) {
            batch.addAll(fresh)
            while (batch.size > MAX_BATCH) batch.removeFirst()
            lastStrokeAt = now
            return null
        }
        if (batch.isEmpty() || now - lastStrokeAt < PEN_REST_MS) return null
        val strokes = batch.toList()
        batch.clear()
        val readings = handwriting.read(strokes)
        Log.d(TAG, "read ${strokes.size} strokes as $readings")
        val pts = strokes.flatMap { it.points }
        val box = Box(
            pts.minOf { it.x }.toInt(), pts.minOf { it.y }.toInt(),
            pts.maxOf { it.x }.toInt(), pts.maxOf { it.y }.toInt(),
        )
        return readings.firstNotNullOfOrNull { LiveTrigger.findIn(it, box) }
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
        private const val TAG = "NotesWatcher"
        /** How often the page is looked at; one unchanged look means the pen rests. */
        const val POLL_MS = 1200L
        /** How often the bridge is asked for new strokes, and how long the pen must rest. */
        const val INK_POLL_MS = 500L
        const val PEN_REST_MS = 1300L
        const val MAX_BATCH = 400
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
