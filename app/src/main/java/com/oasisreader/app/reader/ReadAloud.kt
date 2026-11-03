package com.oasisreader.app.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 소리 내 읽기: a text-to-speech engine (the phone's default or one the reader picked) reads a
 * chapter sentence by sentence. The reader hands over the sentences and a start index;
 * [onSentence] is told which sentence is being spoken (the page marks it and turns to it),
 * [onFinished] when the last one is done (the reader goes on with the next chapter).
 *
 * Plays on while the screen is off: [ReadAloudService] keeps the app alive and shows the player
 * in the notification shade; headset buttons and other apps' sound (audio focus) control it.
 */
class ReadAloud(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val audio = app.getSystemService(AudioManager::class.java)

    var onSentence: (Int) -> Unit = {}
    var onFinished: () -> Unit = {}
    var onUnavailable: () -> Unit = {}
    var onStateChanged: () -> Unit = {}

    /** Engine status: null while starting, then true / false. */
    var available by mutableStateOf<Boolean?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var index by mutableIntStateOf(0)
        private set
    var count by mutableIntStateOf(0)
        private set
    var rate by mutableFloatStateOf(prefs.getFloat("rate", 1f))
        private set
    var pitch by mutableFloatStateOf(prefs.getFloat("pitch", 1f))
        private set
    /** Sleep timer: stop at this time (ms), or at the end of the chapter. */
    var sleepAt by mutableLongStateOf(0L)
        private set
    var sleepAtChapterEnd by mutableStateOf(false)
        private set

    /** The book's language (EPUB dc:language), used when no voice was picked. */
    var language: String? = null

    private var items: List<String> = emptyList()
    private var session = 0          // utterances of an older queue are ignored
    private var marked = -1
    private var pending: (() -> Unit)? = null
    private var tts: TextToSpeech = create()

    private fun create(): TextToSpeech {
        available = null
        val engine = prefs.getString("engine", null)
        lateinit var t: TextToSpeech
        val init = TextToSpeech.OnInitListener { status ->
            main.post {
                if (t !== tts) return@post
                available = status == TextToSpeech.SUCCESS
                if (available == true) {
                    t.setAudioAttributes(ATTRS)
                    applyVoice(t)
                    pending?.invoke()
                } else if (pending != null) onUnavailable()
                pending = null
            }
        }
        t = if (engine != null) TextToSpeech(app, init, engine) else TextToSpeech(app, init)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = post(id) { s, i -> if (s == session) mark(i) }
            // some engines report onStart late or not at all: the next sentence starts when this one is done
            override fun onDone(id: String) = post(id) { s, i ->
                if (s != session) return@post
                if (i >= items.lastIndex) finish() else mark(i + 1)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = post(id) { s, i ->
                if (s != session) return@post
                if (i >= items.lastIndex) finish() else mark(i + 1)
            }
            override fun onError(id: String, errorCode: Int) = onError(id)
        })
        return t
    }

    private fun applyVoice(t: TextToSpeech) {
        val name = prefs.getString("voice", null)
        val v = name?.let { n -> runCatching { t.voices }.getOrNull()?.firstOrNull { it.name == n } }
        if (v != null) { t.voice = v; return }
        val loc = language?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) } ?: Locale.KOREAN
        if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.language = loc
    }

    private fun mark(i: Int) {
        if (i == marked) return
        marked = i; index = i
        onSentence(i)
    }

    private fun post(id: String, f: (Int, Int) -> Unit) {
        val s = id.substringBefore(':').toIntOrNull() ?: return
        val i = id.substringAfter(':').toIntOrNull() ?: return
        main.post { f(s, i) }
    }

    private fun finish() {
        if (sleepAtChapterEnd) { sleepAtChapterEnd = false; pause(); return }
        playing = false
        onFinished()
    }

    /** Read [sentences] from [from]. An empty chapter (a cover picture) finishes at once. */
    fun play(sentences: List<String>, from: Int) {
        items = sentences.map { if (it.length > MAX_LEN) it.take(MAX_LEN) else it }
        count = items.size
        index = from.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        marked = -1
        playing = true
        if (items.isEmpty() || from >= items.size) { session++; main.post { finish() }; return }
        when (available) {
            true -> speakFrom(index)
            null -> pending = { speakFrom(index) }
            false -> { playing = false; onUnavailable() }
        }
        onStateChanged()
    }

    private fun speakFrom(i: Int) {
        if (!focus()) { playing = false; onStateChanged(); return }
        session++
        marked = -1
        tts.stop()
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        for (k in i until items.size) {
            tts.speak(items[k], if (k == i) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$session:$k")
        }
        mark(i)
    }

    fun pause() {
        session++; tts.stop(); playing = false
        onStateChanged()
    }

    fun resume() {
        if (items.isEmpty()) return
        playing = true; speakFrom(index)
        onStateChanged()
    }

    fun toggle() { if (playing) pause() else resume() }

    /** Previous / next sentence. */
    fun skip(d: Int) {
        if (items.isEmpty()) return
        val to = (index + d).coerceIn(0, items.lastIndex)
        if (playing) speakFrom(to) else { marked = -1; mark(to) }
    }

    fun changeRate(r: Float) {
        rate = r
        prefs.edit().putFloat("rate", r).apply()
        if (playing) speakFrom(index)
    }

    fun changePitch(p: Float) {
        pitch = p
        prefs.edit().putFloat("pitch", p).apply()
        if (playing) speakFrom(index)
    }

    /** Engines on the phone (Samsung, Google …): package name to label. */
    fun engines(): List<Pair<String, String>> = runCatching { tts.engines.map { it.name to it.label } }.getOrDefault(emptyList())
    fun engine(): String? = prefs.getString("engine", null) ?: runCatching { tts.defaultEngine }.getOrNull()

    fun setEngine(pkg: String) {
        if (pkg == engine() && prefs.getString("engine", null) != null) return
        val wasPlaying = playing
        session++
        runCatching { tts.stop(); tts.shutdown() }
        prefs.edit().putString("engine", pkg).remove("voice").apply()
        tts = create()
        if (wasPlaying) pending = { speakFrom(index) }
    }

    /** Voices of the engine for the book's language (all voices if [all]). */
    fun voices(all: Boolean = false): List<Voice> {
        val vs = runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val lang = (language?.takeIf { it.isNotBlank() } ?: "ko").substringBefore('-').lowercase()
        val mine = if (all) vs else vs.filter { it.locale.language.lowercase() == lang }
        return mine.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
    }
    fun voiceName(): String? = prefs.getString("voice", null)

    fun setVoice(v: Voice?) {
        prefs.edit().apply { if (v == null) remove("voice") else putString("voice", v.name) }.apply()
        applyVoice(tts)
        if (playing) speakFrom(index)
    }

    /** A sample sentence in the chosen voice (stops reading first). */
    fun preview(v: Voice?, text: String) {
        if (playing) pause()
        session++
        if (v != null) tts.voice = v else applyVoice(tts)
        tts.setSpeechRate(rate); tts.setPitch(pitch)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
        if (v != null) main.postDelayed({ applyVoice(tts) }, 50)
    }

    // ── sleep timer ──
    private val sleepTask = Runnable { sleepAt = 0L; if (playing) pause() }
    fun sleepIn(minutes: Int) {
        main.removeCallbacks(sleepTask)
        sleepAtChapterEnd = false
        sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        if (minutes > 0) main.postDelayed(sleepTask, minutes * 60_000L)
        onStateChanged()
    }
    fun sleepAtEndOfChapter() {
        main.removeCallbacks(sleepTask)
        sleepAt = 0L; sleepAtChapterEnd = true
        onStateChanged()
    }

    // ── audio focus: a call or another player pauses the reading ──
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { resumeOnGain = playing && change != AudioManager.AUDIOFOCUS_LOSS; if (playing) pause() }
                AudioManager.AUDIOFOCUS_GAIN -> { if (resumeOnGain) { resumeOnGain = false; resume() } }
            }
        }
    }
    private var resumeOnGain = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(ATTRS)
        .setOnAudioFocusChangeListener(focusListener, main)
        .build()
    private fun focus(): Boolean =
        audio == null || audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_FAILED

    fun stop() {
        session++; pending = null; tts.stop(); playing = false; items = emptyList(); count = 0
        main.removeCallbacks(sleepTask); sleepAt = 0L; sleepAtChapterEnd = false
        audio?.abandonAudioFocusRequest(focusRequest)
        onStateChanged()
    }

    fun shutdown() { stop(); runCatching { tts.shutdown() } }

    companion object {
        const val PREFS = "read_aloud"
        const val MAX_LEN = 3000      // under the engines' input limit (4000)
        val RATES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val PITCHES = listOf(0.8f to "낮게", 1f to "보통", 1.2f to "높게")
        val SLEEP = listOf(15, 30, 45, 60)
        private val ATTRS = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        fun rateLabel(r: Float) = (if (r == r.toInt().toFloat()) "${r.toInt()}.0" else "$r") + "배"
    }
}
