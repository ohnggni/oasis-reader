package com.oasisreader.app.reader

import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.epub.EpubPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Screen off / another app in front: the page cannot load the next chapter then (the UI is
 * paused), so the next chapters are read from their files here and the place is saved directly.
 * Back in front, the page opens where the voice is and takes over again.
 */
internal class AloudBackground { var chapter = -1; var items: List<String> = emptyList() }

/** What reading aloud needs from the reader screen. A new one is handed over on every composition. */
internal class AloudHost(
    val context: Context,
    val scope: CoroutineScope,
    val db: AppDatabase,
    val store: BookStore,
    val lifecycleOwner: LifecycleOwner,
    val webView: () -> WebView?,
    val pkg: () -> EpubPackage?,
    val book: () -> BookEntity?,
    val curIndex: () -> Int,
    val goChapter: (Int, Float) -> Unit,
    val touchActive: () -> Unit,
    /** the page makes room for the reader: settings panel away, bars away */
    val clearForReading: () -> Unit,
)

/**
 * 소리 내 읽기: a TTS engine reads from the page on screen, chapter after chapter.
 * The state lives here (one instance, remembered by the reader); [host] is replaced on every composition.
 */
internal class ReaderAloud(private val app: Context, private val bookId: String) {
    val readAloud = ReadAloud(app).also { it.useBook(bookId) }
    var active by mutableStateOf(false)
    // 82: opened with 🔊 from the 서재, or it was being read aloud when last closed → start when the page is ready
    var auto by mutableStateOf(ReadAloudStart.take(bookId) || readAloud.shouldResume())
    var voiceOpen by mutableStateOf(false)
    // 장 넘김 (83): the sound and the title to give when the next chapter starts being read
    val turn = ChapterTurn()
    val bg = AloudBackground()
    lateinit var host: AloudHost

    private fun inFront() = host.lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    val player = object : ReadAloudService.Player {
        override val playing get() = readAloud.playing
        override fun toggle() = readAloud.toggle()
        override fun skip(d: Int) = readAloud.skip(d)
        override fun close() { aloudClose?.invoke() }
        override fun dropResume() = readAloud.dropResume()
    }

    fun stop() {
        active = false
        readAloud.viewPicked = false
        readAloud.markAloud(false)
        bg.chapter = -1
        turn.title = null; turn.cue = false
        readAloud.stop()
        ReadAloudService.hide(app)
        host.webView()?.evaluateJavascript("window.Reader && Reader.ttsClear && Reader.ttsClear()", null)
    }

    /** (Re)start at the first sentence on the page on screen, or at [match] (text, place in the chapter). */
    private fun fromHere(match: Pair<String, Float>? = null) {
        val wv = host.webView() ?: return
        val args = if (match == null) "" else JSONObject.quote(match.first) + "," + match.second
        wv.evaluateJavascript("window.Reader && Reader.ttsList ? Reader.ttsList($args) : null") { r ->
            val o = runCatching { JSONObject(r) }.getOrNull()
            val arr = o?.optJSONArray("items")
            if (o == null || arr == null) {
                stop()
                Toast.makeText(host.context, "이 화면에서는 소리 내 읽기를 할 수 없습니다.", Toast.LENGTH_SHORT).show()
            } else if (active) {
                readAloud.play(List(arr.length()) { arr.getString(it) }, o.optInt("start", 0), intro = turn.title, cue = turn.cue)
                turn.title = null; turn.cue = false
            }
        }
    }

    fun start() {
        host.clearForReading(); readAloud.viewPicked = false; active = true
        readAloud.markAloud(true)
        readAloud.language = host.pkg()?.language
        ReadAloudService.show(app, player, host.book()?.title.orEmpty(), host.pkg()?.let { chapterTitleFor(it, host.curIndex()) }.orEmpty())
        fromHere()
    }

    /** The player in the notification shade needs the notification permission (Android 13+); reading works without it. */
    fun request(launchPermission: (String) -> Unit) {
        val need = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(host.context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (need) launchPermission(android.Manifest.permission.POST_NOTIFICATIONS) else start()
    }

    /** Hands the engine's events to this reader (assigned on every composition, like the screen's own state). */
    fun bind() {
        readAloud.onStateChanged = { ReadAloudService.update() }
        readAloud.onSentence = { i ->
            host.touchActive()
            val bgIdx = bg.chapter
            val pp = host.pkg()
            if (bgIdx >= 0 && pp != null) {
                // read from the file: save the place every few sentences
                if (i % 4 == 0 && bg.items.isNotEmpty()) {
                    val f = (i.toFloat() / bg.items.size).coerceIn(0f, 1f)
                    host.scope.launch { host.db.books().savePosition(bookId, bgIdx, f, pp.overall(bgIdx, f), System.currentTimeMillis()) }
                }
            } else {
                host.webView()?.evaluateJavascript("window.Reader && Reader.ttsMark && Reader.ttsMark($i)", null)
            }
        }
        readAloud.onFinished = {
            val pp = host.pkg()
            val from = if (bg.chapter >= 0) bg.chapter else host.curIndex()
            when {
                !active || pp == null -> {}
                (from + 1) !in pp.spine.indices -> { stop(); Toast.makeText(host.context, "책 끝까지 읽었습니다.", Toast.LENGTH_SHORT).show() }
                inFront() && bg.chapter < 0 -> {
                    turn.cue = true; turn.title = turnTitle(pp, from, from + 1)
                    host.goChapter(from + 1, 0f)   // goes on when the page is ready
                }
                else -> host.scope.launch {
                    val next = from + 1
                    val list = withContext(Dispatchers.IO) {
                        runCatching { ReadAloudText.sentences(File(host.store.contentDir(bookId), pp.spine[next].path).readText()) }.getOrDefault(emptyList())
                    }
                    if (!active) return@launch
                    bg.chapter = next; bg.items = list
                    host.db.books().savePosition(bookId, next, 0f, pp.overall(next, 0f), System.currentTimeMillis())
                    ReadAloudService.update(chapter = chapterTitleFor(pp, next))
                    readAloud.play(list, 0, intro = turnTitle(pp, from, next), cue = true)
                }
            }
        }
        readAloud.onUnavailable = {
            stop()
            com.oasisreader.app.ui.Notice.show(host.context, "소리 내 읽기를 쓸 수 없습니다. 휴대폰 설정의 '텍스트 음성 변환'에서 엔진을 확인해 주세요.")
        }
    }

    /** Back in front after reading on with the screen off: open the page where the voice is. */
    fun onStart() {
        if (active && bg.chapter >= 0) {
            val n = bg.items.size
            val f = if (n > 0) (readAloud.sentence.toFloat() / n).coerceIn(0f, 1f) else 0f
            host.goChapter(bg.chapter, f)
        }
    }

    /** A chapter opened while reading aloud: go on there (at the voice's sentence after reading from a file). [request] starts it when 🔊 asked for it at opening. */
    fun onReady(request: () -> Unit) {
        if (!active && auto) { auto = false; request(); return }
        if (!active) return
        if (bg.chapter >= 0) {
            val items = bg.items
            val i = readAloud.sentence
            val match = items.getOrNull(i)?.let { it to (if (items.isEmpty()) 0f else i.toFloat() / items.size) }
            bg.chapter = -1; bg.items = emptyList()
            fromHere(match)
        } else fromHere()
        ReadAloudService.update(chapter = host.pkg()?.let { chapterTitleFor(it, host.curIndex()) })
    }
}
