package io.github.gaiser147.claudeoverlay.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Dictation through the system speech recognizer (Google's or Samsung's), with live partial
 * results. Must be used from the main thread, as SpeechRecognizer requires.
 */
class VoiceInput(private val context: Context) {

    interface Listener {
        fun onPartial(text: String)
        /** 0..1, for the mic button's level ring. */
        fun onLevel(level: Float)
        fun onFinal(text: String)
        fun onError(reason: Reason)
    }

    enum class Reason { NoPermission, NothingHeard, Busy, Unavailable, Other }

    private var recognizer: SpeechRecognizer? = null

    val hasPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(listener: Listener) {
        if (!hasPermission) return listener.onError(Reason.NoPermission)
        if (!available) return listener.onError(Reason.Unavailable)
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onRmsChanged(rmsdB: Float) =
                listener.onLevel(((rmsdB - RMS_FLOOR) / RMS_RANGE).coerceIn(0f, 1f))

            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let(listener::onPartial)
            }

            override fun onResults(results: Bundle?) {
                release(r)
                listener.onFinal(best(results).orEmpty())
            }

            override fun onError(error: Int) {
                release(r)
                listener.onError(
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Reason.NoPermission
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Reason.NothingHeard
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Reason.Busy
                        else -> Reason.Other
                    }
                )
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        )
    }

    /** Stop listening and deliver what was heard so far as the final result. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() {
        recognizer?.let(::release)
    }

    private fun release(r: SpeechRecognizer) {
        if (recognizer === r) recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        // Recognizers report roughly -2..10 dB.
        const val RMS_FLOOR = -2f
        const val RMS_RANGE = 12f
    }
}
