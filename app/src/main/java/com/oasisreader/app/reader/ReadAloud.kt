package com.oasisreader.app.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 소리 내 읽기: the phone's own text-to-speech engine reads a chapter sentence by sentence.
 * The reader hands over the chapter's sentences (from the page script) and a start index;
 * [onSentence] is told which sentence is being spoken (the page marks it and turns to it),
 * [onFinished] when the last one is done (the reader opens the next chapter and goes on).
 */
class ReadAloud(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.applicationContext.getSharedPreferences("read_aloud", Context.MODE_PRIVATE)

    var onSentence: (Int) -> Unit = {}
    var onFinished: () -> Unit = {}
    var onUnavailable: () -> Unit = {}

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

    private var items: List<String> = emptyList()
    private var session = 0          // utterances of an older queue are ignored
    private var pending: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            available = status == TextToSpeech.SUCCESS
            if (available == true) { pending?.invoke() } else if (pending != null) onUnavailable()
            pending = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = post(id) { s, i -> if (s == session) { index = i; onSentence(i) } }
            override fun onDone(id: String) = post(id) { s, i -> if (s == session && i >= items.lastIndex) finish() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = post(id) { s, i -> if (s == session && i >= items.lastIndex) finish() }
            override fun onError(id: String, errorCode: Int) = onError(id)
        })
    }

    private fun post(id: String, f: (Int, Int) -> Unit) {
        val s = id.substringBefore(':').toIntOrNull() ?: return
        val i = id.substringAfter(':').toIntOrNull() ?: return
        main.post { f(s, i) }
    }

    private fun finish() {
        playing = false
        onFinished()
    }

    /** Read [sentences] from [from]. An empty chapter (a cover picture) finishes at once. */
    fun play(sentences: List<String>, from: Int) {
        items = sentences.map { if (it.length > MAX_LEN) it.take(MAX_LEN) else it }
        count = items.size
        index = from.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        playing = true
        if (items.isEmpty() || from >= items.size) { session++; main.post { finish() }; return }
        when (available) {
            true -> speakFrom(index)
            null -> pending = { speakFrom(index) }
            false -> { playing = false; onUnavailable() }
        }
    }

    private fun speakFrom(i: Int) {
        session++
        tts.stop()
        tts.setSpeechRate(rate)
        for (k in i until items.size) {
            tts.speak(items[k], if (k == i) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$session:$k")
        }
    }

    fun pause() { session++; tts.stop(); playing = false }

    fun resume() { if (items.isEmpty()) return; playing = true; speakFrom(index) }

    /** Previous / next sentence. */
    fun skip(d: Int) {
        if (items.isEmpty()) return
        index = (index + d).coerceIn(0, items.lastIndex)
        if (playing) speakFrom(index) else onSentence(index)
    }

    fun changeRate(r: Float) {
        rate = r
        prefs.edit().putFloat("rate", r).apply()
        if (playing) speakFrom(index)
    }

    fun stop() { session++; pending = null; tts.stop(); playing = false; items = emptyList(); count = 0 }

    fun shutdown() { stop(); tts.shutdown() }

    companion object {
        const val MAX_LEN = 3000      // under the engines' input limit (4000)
        val RATES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        fun rateLabel(r: Float) = (if (r == r.toInt().toFloat()) "${r.toInt()}.0" else "$r") + "배"
    }
}

/** The small player shown while reading aloud: previous / play-pause / next sentence, speed, stop. */
@Composable
fun ReadAloudBar(ra: ReadAloud, fill: Color, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = fill,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔊", fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp, end = 2.dp))
            BarButton("⏮", "이전 문장") { ra.skip(-1) }
            BarButton(if (ra.playing) "⏸" else "▶", if (ra.playing) "일시정지" else "계속 읽기") {
                if (ra.playing) ra.pause() else ra.resume()
            }
            BarButton("⏭", "다음 문장") { ra.skip(1) }
            var speedOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { speedOpen = true }) { Text(ReadAloud.rateLabel(ra.rate), style = MaterialTheme.typography.labelLarge) }
                DropdownMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                    ReadAloud.RATES.forEach { r ->
                        DropdownMenuItem(
                            text = { Text((if (r == ra.rate) "✓ " else "    ") + ReadAloud.rateLabel(r)) },
                            onClick = { speedOpen = false; ra.changeRate(r) },
                        )
                    }
                }
            }
            BarButton("✕", "소리 내 읽기 끝내기", onClose)
        }
    }
}

@Composable
private fun BarButton(glyph: String, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp).semantics { contentDescription = label }) {
        Text(glyph, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
