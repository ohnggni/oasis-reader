package com.oasisreader.app.reader

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.mutableStateMapOf

/**
 * The voices of the engines that are not in use (88), so the voice dialog lists every engine at
 * once and plays samples from any of them without switching. One connection per engine while the
 * dialog is open; [close] when it goes.
 */
class VoiceCatalog(private val app: Context) {
    /** Engine package → its installed voices (all languages). */
    val voices = mutableStateMapOf<String, List<Voice>>()
    /** Engines that would not open. */
    val failed = mutableStateMapOf<String, Boolean>()

    private val conns = HashMap<String, TextToSpeech>()
    private val main = Handler(Looper.getMainLooper())

    fun load(pkg: String) {
        if (conns.containsKey(pkg)) return
        lateinit var t: TextToSpeech
        t = TextToSpeech(app, { status ->
            main.post {
                if (conns[pkg] !== t) return@post
                if (status == TextToSpeech.SUCCESS) {
                    runCatching { t.setAudioAttributes(ATTRS) }
                    voices[pkg] = runCatching { t.voices?.toList() }.getOrNull().orEmpty()
                        .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                } else failed[pkg] = true
            }
        }, pkg)
        conns[pkg] = t
    }

    fun preview(pkg: String, v: Voice, text: String, rate: Float, pitch: Float) {
        stop()
        val t = conns[pkg] ?: return
        runCatching {
            t.voice = v
            t.setSpeechRate(rate); t.setPitch(pitch)
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "catalog")
        }
    }

    fun stop() { conns.values.forEach { runCatching { it.stop() } } }

    /** Drop everything (the dialog closed, or voices may have changed in an engine's own app). */
    fun close() {
        conns.values.forEach { runCatching { it.stop(); it.shutdown() } }
        conns.clear(); voices.clear(); failed.clear()
    }

    private companion object {
        val ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
