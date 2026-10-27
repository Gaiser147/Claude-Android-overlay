package io.github.gaiser147.claudeoverlay.notes

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import io.github.gaiser147.claudeoverlay.tools.PenStroke
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.google.mlkit.vision.digitalink.recognition.Ink as MlInk

/**
 * Reads handwriting from the user's pen strokes on the device (ML Kit Digital Ink: the strokes
 * themselves, far more reliable for handwriting than reading pixels). The language model is
 * downloaded once, about 20 MB: German, else English.
 */
class InkReader(private val languages: List<String> = listOf("de-DE", "en-US")) {
    private var recognizer: DigitalInkRecognizer? = null
    private var preparing = false

    /** Downloads the model if needed; false while it isn't ready (no network yet, …). */
    suspend fun ready(): Boolean {
        if (recognizer != null) return true
        if (preparing) return false
        preparing = true
        try {
            for (tag in languages) {
                val id = runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull() ?: continue
                val model = DigitalInkRecognitionModel.builder(id).build()
                val manager = RemoteModelManager.getInstance()
                val have = await(manager.isModelDownloaded(model)) == true
                if (!have && await(manager.download(model, DownloadConditions.Builder().build())) == null &&
                    await(manager.isModelDownloaded(model)) != true
                ) {
                    Log.w(TAG, "handwriting model $tag not downloaded")
                    continue
                }
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
                Log.i(TAG, "handwriting model $tag ready")
                return true
            }
            return false
        } finally {
            preparing = false
        }
    }

    /** The likeliest readings of [strokes] (best first), or empty. */
    suspend fun read(strokes: List<PenStroke>): List<String> {
        val r = recognizer ?: return emptyList()
        val ink = MlInk.builder()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = 0f; var maxY = 0f
        var clock = 0L
        for (s in strokes) {
            val b = MlInk.Stroke.builder()
            s.points.forEachIndexed { i, p ->
                clock = maxOf(clock + 1, s.times.getOrNull(i) ?: (clock + 8))
                b.addPoint(MlInk.Point.create(p.x, p.y, clock))
                minX = minOf(minX, p.x); minY = minOf(minY, p.y); maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
            }
            ink.addStroke(b.build())
        }
        val context = RecognitionContext.builder()
            .setPreContext("")
            .setWritingArea(WritingArea((maxX - minX).coerceAtLeast(1f), (maxY - minY).coerceAtLeast(1f)))
            .build()
        val result = await(r.recognize(ink.build(), context)) ?: return emptyList()
        return result.candidates.map { it.text }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }

    private suspend fun <T> await(task: Task<T>): T? = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }.addOnFailureListener {
            Log.w(TAG, "ML Kit task failed", it)
            cont.resume(null)
        }
    }

    private companion object {
        const val TAG = "NotesInk"
    }
}
