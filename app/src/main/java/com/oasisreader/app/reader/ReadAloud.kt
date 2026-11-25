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
import kotlinx.coroutines.launch

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
    private val prefs = TtsPrefs.of(app)
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

    /** 장 넘김 (83): how many lines at the front are not the book's (the chapter title read first). */
    private var lead = 0
    private var cueAt = 0L
    /** The sentence being read, counted in the book's own list (the title read first is not one). */
    val sentence: Int get() = (index - lead).coerceAtLeast(0)

    /** 장 넘어갈 때 (83): 0 없음 · 1 소리 · 2 제목 · 3 소리 + 제목. */
    var chapterCue by mutableIntStateOf(prefs.getInt(TtsPrefs.CHAPTER_CUE, CUE_BOTH))
        private set
    fun changeChapterCue(v: Int) { chapterCue = v; prefs.edit().putInt(TtsPrefs.CHAPTER_CUE, v).apply() }
    var count by mutableIntStateOf(0)
        private set
    var rate by mutableFloatStateOf(prefs.getFloat(TtsPrefs.RATE, 1f))
        private set
    var pitch by mutableFloatStateOf(prefs.getFloat(TtsPrefs.PITCH, 1f))
        private set

    /** 이 책만 따로 (82): the book open now, and whether it keeps its own 빠르기 · 목소리. */
    var bookId: String? = null
        private set
    var own by mutableStateOf(false)
        private set
    private fun rateKey() = bookId.let { id -> if (own && id != null) TtsPrefs.rateOf(id) else TtsPrefs.RATE }
    private fun voiceKey() = bookId.let { id -> if (own && id != null) TtsPrefs.voiceOf(id) else TtsPrefs.VOICE }

    /** The book being read: its own 빠르기 · 목소리 when it has them, else the shared ones. */
    fun useBook(id: String) {
        bookId = id
        own = prefs.getBoolean(TtsPrefs.ownOf(id), false)
        rate = prefs.getFloat(rateKey(), prefs.getFloat(TtsPrefs.RATE, 1f))
        runCatching { applyVoice(tts) }
    }

    /** Turn 이 책만 따로 on (starting from the shared settings) or off (back to them). */
    fun changeOwn(on: Boolean) {
        val id = bookId ?: return
        if (on == own) return
        prefs.edit().apply {
            if (on) {
                putBoolean(TtsPrefs.ownOf(id), true).putFloat(TtsPrefs.rateOf(id), rate)
                prefs.getString(TtsPrefs.VOICE, null)?.let { putString(TtsPrefs.voiceOf(id), it) }
            } else remove(TtsPrefs.ownOf(id)).remove(TtsPrefs.rateOf(id)).remove(TtsPrefs.voiceOf(id))
        }.apply()
        own = on
        rate = prefs.getFloat(rateKey(), 1f)
        runCatching { applyVoice(tts) }
        if (playing) speakFrom(index)
    }

    /** 소리로 읽던 책 (82): it was being read aloud when last closed. */
    fun markAloud(on: Boolean) { bookId?.let { prefs.edit().putBoolean(TtsPrefs.aloudOf(it), on).apply() } }
    fun shouldResume(): Boolean = bookId?.let { resumeOn(app) && prefs.getBoolean(TtsPrefs.aloudOf(it), false) } ?: false
    /** Numbers and signs written out in Hangul before speaking ([ReadAloudNormalize]). */
    var normalize by mutableStateOf(prefs.getBoolean(TtsPrefs.NORMALIZE, true))
        private set
    /** Sleep timer: stop at this time (ms), or at the end of the chapter. */
    var sleepAt by mutableLongStateOf(0L)
        private set
    var sleepAtChapterEnd by mutableStateOf(false)
        private set

    /** The book's language (EPUB dc:language), used when no voice was picked. */
    var language: String? = null

    private var raw: List<String> = emptyList()     // as in the book
    private var items: List<String> = emptyList()   // as spoken
    private var session = 0          // utterances of an older queue are ignored
    private var marked = -1
    private var pending: (() -> Unit)? = null
    private var tts: TextToSpeech = create()

    init {
        // the dictionary is read once per app run; until then sentences go without it
        com.oasisreader.app.ui.ShelfActions.scope.launch { runCatching { PronDict.load(app) } }
    }

    private fun create(): TextToSpeech {
        available = null
        val engine = prefs.getString(TtsPrefs.ENGINE, null)
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
            override fun onStart(id: String) = post(id) { s, i -> started(s, i) }
            // some engines report onStart late or not at all: the next sentence starts when this one is done
            override fun onDone(id: String) = post(id) { s, i -> done(s, i) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = post(id) { s, i -> done(s, i) }
            override fun onError(id: String, errorCode: Int) = onError(id)
        })
        return t
    }

    private fun applyVoice(t: TextToSpeech) {
        val name = prefs.getString(voiceKey(), null)
        val v = name?.let { n -> runCatching { t.voices }.getOrNull()?.firstOrNull { it.name == n } }
        if (v != null) { t.voice = v; return }
        val loc = language?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) } ?: Locale.KOREAN
        if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.language = loc
    }

    /** A speaker began sentence [i] of queue [s] (main thread). */
    private fun started(s: Int, i: Int) { if (s == session) mark(i) }

    /** A speaker finished (or could not say) sentence [i] of queue [s] (main thread). */
    private fun done(s: Int, i: Int) {
        if (s != session) return
        if (i >= items.lastIndex) finish() else mark(i + 1)
    }

    /**
     * What turns the sentences into sound. [system] is the phone's TextToSpeech (the default and the
     * fallback); other speakers (network voices) follow the same contract: say the items from index `from` on in
     * order, report each sentence to [started] / [done] with the queue number, and stop on [stop].
     */
    interface Speaker {
        fun speak(items: List<String>, from: Int, session: Int, rate: Float, pitch: Float)
        fun stop()
        fun shutdown()
    }

    private val system = object : Speaker {
        override fun speak(items: List<String>, from: Int, session: Int, rate: Float, pitch: Float) {
            tts.stop()
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
            for (k in from until items.size) {
                tts.speak(engineText(items[k]), if (k == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$session:$k")
                // the pause between sentences (its id is not "session:index", so the callbacks skip it)
                if (k < items.lastIndex) gapFor(items[k], rate).takeIf { it > 0 }?.let {
                    tts.playSilentUtterance(it, TextToSpeech.QUEUE_ADD, "gap-$session-$k")
                }
            }
        }
        override fun stop() { tts.stop() }
        override fun shutdown() { tts.shutdown() }
    }
    private var speaker: Speaker = system

    /** Azure 음성 ([AzureVoice]), made when first used. */
    private var azure: AzureSpeaker? = null
    /** Azure failed while reading: the phone's voice for the rest of this reading. */
    private var azureDown = false

    /** True when sentences go to Azure (set up, turned on, not failed in this reading). */
    val usesAzure: Boolean get() = !azureDown && AzureVoice.on(app)

    private fun azureSpeaker(): AzureSpeaker =
        azure ?: AzureSpeaker(app, ::started, ::done, ::azureFailed, ::gapFor).also { azure = it }

    private fun azureFailed(s: Int, i: Int, reason: String) {
        if (s != session) return
        azureDown = true
        com.oasisreader.app.ui.Notice.show(app, "Azure 음성: $reason. 휴대폰 목소리로 이어 읽습니다.")
        when (available) {
            true -> speakFrom(i)
            null -> pending = { speakFrom(i) }
            false -> { playing = false; onStateChanged(); onUnavailable() }
        }
    }

    /** Azure was turned on / off or set up again: try it afresh, from this sentence if reading. */
    fun azureChanged() {
        azureDown = false
        if (playing) speakFrom(index)
    }

    /** A sample in an Azure voice (stops reading first). */
    fun previewAzure(voice: String, text: String) {
        if (playing) pause()
        session++
        system.stop()
        azureSpeaker().preview(text, voice, rate, pitch) { reason -> com.oasisreader.app.ui.Notice.show(app, "Azure 음성: $reason") }
    }

    private fun mark(i: Int) {
        if (i == marked) return
        marked = i; index = i
        if (i >= lead) onSentence(i - lead)
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

    /**
     * Read [sentences] from [from]. An empty chapter (a cover picture) finishes at once.
     * A new chapter (83): [cue] plays the page-turn sound first, and [intro] (its title) is read
     * before the first sentence — each when 장 넘어갈 때 says so, the title only when the chapter's
     * own first line is not already that title.
     */
    fun play(sentences: List<String>, from: Int, intro: String? = null, cue: Boolean = false) {
        // a title page with no text (a picture) still gets its title read (#223)
        val head = intro?.trim()?.takeIf {
            it.isNotEmpty() && (chapterCue and CUE_TITLE) != 0 &&
                (sentences.isEmpty() || (from == 0 && !sameStart(it, sentences.first())))
        }
        // 89: a heading's pause after the title, and after its chapter number (4장 · 목표에 미칠 때)
        val heads = head?.let { h -> ReadAloudText.splitLead(h).map { it + ReadAloudText.HEAD } }.orEmpty()
        lead = heads.size
        // one sentence in, one out: the page marks sentences by their place in the book's list
        raw = heads + sentences
        items = raw.map { spoken(it) }
        count = sentences.size
        index = if (head != null) 0 else from.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        marked = -1
        playing = true
        if (head == null && (sentences.isEmpty() || from >= sentences.size)) { session++; main.post { finish() }; return }
        val start = start@{
            if (usesAzure) { speakFrom(index); onStateChanged(); return@start }
            when (available) {
                true -> speakFrom(index)
                null -> pending = { speakFrom(index) }
                false -> { playing = false; onUnavailable() }
            }
            onStateChanged()
        }
        // one sound per turn: a title page and the text after it are two files but one chapter
        val now = android.os.SystemClock.elapsedRealtime()
        if (cue && (chapterCue and CUE_SOUND) != 0 && now - cueAt > CUE_GAP_MS) {
            cueAt = now
            val s = ++session
            speaker.stop()
            ChapterCue.play()
            onStateChanged()
            main.postDelayed({ if (s == session && playing) start() }, ChapterCue.MS)
        } else start()
    }

    private fun speakFrom(i: Int) {
        if (!focus()) { playing = false; onStateChanged(); return }
        session++
        marked = -1
        val next = if (usesAzure) azureSpeaker() else system
        if (next !== speaker) speaker.stop()
        speaker = next
        speaker.speak(items, i, session, rate, pitch)
        mark(i)
    }

    fun pause() {
        session++; speaker.stop(); playing = false
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

    fun changeRate(r0: Float) {
        val r = snapRate(r0)
        if (r == rate) return
        rate = r
        prefs.edit().putFloat(rateKey(), r).apply()
        if (playing) speakFrom(index)
    }

    /** A sentence as spoken: 발음 사전, then numbers and signs in Hangul (when on). */
    fun spoken(s: String): String {
        val t = runCatching { ReadAloudNormalize.speak(s, PronDict.entries, normalize) }.getOrDefault(s)
        return if (t.length > MAX_LEN) t.take(MAX_LEN) else t
    }

    /** Turn number reading on / off; the rest of the chapter is read the new way. */
    fun changeNormalize(on: Boolean) {
        normalize = on
        prefs.edit().putBoolean(TtsPrefs.NORMALIZE, on).apply()
        respeak()
    }

    /** The dictionary or the number setting changed: read the chapter again from here. */
    fun respeak() {
        if (items.isEmpty() || raw.isEmpty()) return
        items = raw.map { spoken(it) }
        if (playing) speakFrom(index)
    }

    fun changePitch(p: Float) {
        pitch = p
        prefs.edit().putFloat(TtsPrefs.PITCH, p).apply()
        if (playing) speakFrom(index)
    }

    /** Engines on the phone (Samsung, Google …): package name to label. */
    fun engines(): List<Pair<String, String>> = runCatching { tts.engines.map { it.name to it.label } }.getOrDefault(emptyList())
    fun engine(): String? = prefs.getString(TtsPrefs.ENGINE, null) ?: runCatching { tts.defaultEngine }.getOrNull()

    /** 문장 사이 쉼 (84, 2.9): kept per engine (Azure has its own) — engines add their own silence, BookFusion a lot. */
    private fun gapKey() = TtsPrefs.gap(usesAzure, engine())
    fun gapLevel(): Int = prefs.getInt(gapKey(), GAP_NORMAL)
    fun changeGap(level: Int) {
        prefs.edit().putInt(gapKey(), level).apply()
        if (playing) speakFrom(index)   // the queued sentences carry the old pauses
    }
    fun gapFor(s: String, rate: Float): Long = gapAfter(s, rate, gapLevel())

    fun setEngine(pkg: String) {
        if (pkg == engine() && prefs.getString(TtsPrefs.ENGINE, null) != null) return
        val wasPlaying = playing
        session++
        runCatching { tts.stop(); tts.shutdown() }
        prefs.edit().putString(TtsPrefs.ENGINE, pkg).remove(TtsPrefs.VOICE).apply { bookId?.let { remove(TtsPrefs.voiceOf(it)) } }.apply()
        tts = create()
        if (wasPlaying) pending = { speakFrom(index) }
    }

    /** Reconnect to the engine: it may have new voices after downloading some in its own app. */
    fun reload() {
        if (playing) return
        session++
        runCatching { tts.stop(); tts.shutdown() }
        tts = create()
    }

    /** Voices of the engine for the book's language (all voices if [all]). */
    fun voices(all: Boolean = false): List<Voice> = filterVoices(
        runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) },
        all,
    )

    /** [vs] for the book's language (all if [all]), best first — the same order every time. */
    fun filterVoices(vs: List<Voice>, all: Boolean): List<Voice> {
        val lang = (language?.takeIf { it.isNotBlank() } ?: "ko").substringBefore('-').lowercase()
        val mine = if (all) vs else vs.filter { it.locale.language.lowercase() == lang }
        return mine.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
    }
    fun voiceName(): String? = prefs.getString(voiceKey(), null)

    /**
     * Voice [name] of engine [pkg] (88, the voice dialog lists every engine): Azure goes off, another
     * engine is switched to first and the voice is put on when it is up (create → applyVoice).
     */
    fun chooseVoice(pkg: String, name: String) {
        if (AzureVoice.on(app)) { AzureVoice.setOn(app, false); azureChanged() }
        if (pkg != engine() || prefs.getString(TtsPrefs.ENGINE, null) == null) {
            setEngine(pkg)
            prefs.edit().putString(voiceKey(), name).apply()
        } else {
            prefs.edit().putString(voiceKey(), name).apply()
            applyVoice(tts)
            if (playing) speakFrom(index)
        }
    }

    /** Before a sample from another engine (88): reading pauses, nothing else plays. */
    fun quietForPreview() {
        if (playing) pause()
        session++
        azure?.stop()
        runCatching { tts.stop() }
    }

    /** Engines left out of the voice dialog (88, "azure" for Azure). */
    fun hiddenEngines(): Set<String> = prefs.getStringSet(TtsPrefs.HIDDEN_ENGINES, emptySet()).orEmpty()
    fun setHidden(pkg: String, hide: Boolean) {
        val now = hiddenEngines().toMutableSet().apply { if (hide) add(pkg) else remove(pkg) }
        prefs.edit().putStringSet(TtsPrefs.HIDDEN_ENGINES, now).apply()
    }

    fun setVoice(v: Voice?) {
        prefs.edit().apply { if (v == null) remove(voiceKey()) else putString(voiceKey(), v.name) }.apply()
        applyVoice(tts)
        if (playing) speakFrom(index)
    }

    /** A sample sentence in the chosen voice (stops reading first). */
    fun preview(v: Voice?, text: String) {
        if (playing) pause()
        session++
        azure?.stop()
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

    /** Forget "resume when focus comes back" (the car or headset went away while paused by a call). */
    fun dropResume() { resumeOnGain = false }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(ATTRS)
        .setOnAudioFocusChangeListener(focusListener, main)
        .build()
    private fun focus(): Boolean =
        audio == null || audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_FAILED

    fun stop() {
        session++; pending = null; speaker.stop(); playing = false; items = emptyList(); raw = emptyList(); count = 0; lead = 0
        main.removeCallbacks(sleepTask); sleepAt = 0L; sleepAtChapterEnd = false
        audio?.abandonAudioFocusRequest(focusRequest)
        onStateChanged()
    }

    fun shutdown() {
        stop()
        azure?.let { runCatching { it.shutdown() } }
        azure = null
        runCatching { system.shutdown() }
    }

    companion object {
        /**
         * Silence after a sentence, in ms (2.4). Engines (Azure most) end a sentence with almost no
         * silence, so a full stop sounded shorter than a comma inside a sentence. A sentence that
         * ends at a comma or a space (a long sentence cut in two, ReadAloudText) gets a short one.
         */
        fun gapAfter(s: String, rate: Float, level: Int = GAP_NORMAL): Long {
            val t = s.trimEnd()
            val end = t.lastOrNull() ?: return 0
            val full = GAP_MS[level.coerceIn(0, GAP_MS.lastIndex)]
            val ms = when {
                end == ReadAloudText.HEAD -> HEAD_MS[level.coerceIn(0, HEAD_MS.lastIndex)]   // after a heading (85)
                end in ".?!…。？！\"'”’」』)]" -> full
                end in ",，、" -> 0          // a long sentence cut at its comma: go straight on (2.9)
                else -> minOf(150, full)     // a line with no end mark (a 텍본 line)
            }
            return (ms / rate.coerceIn(0.5f, 3f)).toLong()
        }

        /** 문장 사이: 없음 · 짧게 · 보통(2.4의 350ms) · 길게. */
        val GAP_MS = TtsTuning.GAP_MS
        /** After a heading or a chapter number line (85): a step longer than after a sentence. */
        val HEAD_MS = TtsTuning.HEAD_MS

        /** What the engine gets: the heading mark off (85). */
        fun engineText(s: String): String = s.replace(ReadAloudText.HEAD.toString(), "")
        const val GAP_NORMAL = TtsTuning.GAP_NORMAL

        const val PREFS = TtsPrefs.NAME

        const val CUE_SOUND = 1
        const val CUE_TITLE = 2
        const val CUE_BOTH = 3
        private const val CUE_GAP_MS = TtsTuning.CUE_GAP_MS

        /** The chapter's first line already is its title ("3장" / "3장 멘토와의 만남"): don't read it twice. */
        fun sameStart(title: String, first: String): Boolean {
            fun key(x: String) = x.filter { it.isLetterOrDigit() }.lowercase()
            val a = key(title); val b = key(first)
            if (a.length < 2 || b.length < 2) return a == b
            return a.startsWith(b) || b.startsWith(a)
        }

        /** 소리로 읽던 책은 다시 열면 이어서 소리로 (82, 설정 › 읽기). On by default. */
        fun resumeOn(c: Context) = TtsPrefs.of(c).getBoolean(TtsPrefs.RESUME_ALOUD, true)
        fun setResumeOn(c: Context, on: Boolean) = TtsPrefs.of(c).edit().putBoolean(TtsPrefs.RESUME_ALOUD, on).apply()
        const val MAX_LEN = TtsTuning.MAX_LEN
        /** 빠르기 (87): 0.5 ~ 2.0 by 0.05, on a slider (was a list of 12, 2.6). */
        const val RATE_MIN = TtsTuning.RATE_MIN
        const val RATE_MAX = TtsTuning.RATE_MAX
        const val RATE_STEP = TtsTuning.RATE_STEP
        /** To the nearest step, inside the range. */
        fun snapRate(r: Float): Float = (Math.round(r / RATE_STEP) * RATE_STEP).coerceIn(RATE_MIN, RATE_MAX).let { Math.round(it * 100) / 100f }
        val PITCHES = TtsTuning.PITCHES
        val SLEEP = TtsTuning.SLEEP
        private val ATTRS = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        fun rateLabel(r: Float): String {
            val c = Math.round(snapRate(r) * 100)
            return "${c / 100}." + (if (c % 10 == 0) "${c / 10 % 10}" else "%02d".format(c % 100)) + "배"
        }
    }
}
