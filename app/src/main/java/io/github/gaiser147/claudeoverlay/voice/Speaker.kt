package io.github.gaiser147.claudeoverlay.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Reads replies aloud with the system text-to-speech engine, one queued sentence at a time. */
class Speaker(context: Context) {
    private val pending = AtomicInteger()
    private val ids = AtomicInteger()
    @Volatile private var ready = false
    /** Sentences that arrived before the engine finished starting. */
    private val early = mutableListOf<String>()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            runCatching { tts.language = Locale.getDefault() }
            ready = true
            synchronized(early) {
                early.forEach(::enqueue)
                early.clear()
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
    }

    fun speak(sentence: String) {
        if (!ready) {
            synchronized(early) { early += sentence }
            return
        }
        enqueue(sentence)
    }

    fun stop() {
        synchronized(early) { early.clear() }
        pending.set(0)
        _speaking.value = false
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun enqueue(sentence: String) {
        pending.incrementAndGet()
        _speaking.value = true
        tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, "s${ids.incrementAndGet()}")
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            _speaking.value = false
        }
    }
}
