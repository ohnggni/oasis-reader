package com.oasisreader.app.reader

import com.oasisreader.app.ui.EmojiText
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.oasisreader.app.ui.theme.isAppDark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.FontManager
import com.oasisreader.app.data.FontOption
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.HighlightEntity
import com.oasisreader.app.data.db.ReadingTimeEntity
import com.oasisreader.app.epub.EpubPackage
import com.oasisreader.app.epub.TocItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

internal const val ASSET_HOST = BookStore.ASSET_HOST
private const val BAR_DP = 52
/** Height of the top bar itself; the page keeps reserving [BAR_DP] + 10 so text does not reflow. */
private const val TOP_BAR_DP = 60
private const val RUNNING_HEAD_DP = 30
private const val SIDEWAYS_PAD_DP = 24
private const val SIDEWAYS_EXTRA_BOTTOM = 14   // a little more air above the page label
internal val CompactPad = PaddingValues(horizontal = 8.dp)

/** Where to open: chapter + fraction, or an element id inside it. [token] forces a reload. */
private data class Target(
    val index: Int,
    val start: Float,
    val anchor: String? = null,
    val focus: String? = null,
    val find: String? = null,
    val findNth: Int = 0,
    val token: Long = System.nanoTime(),
)

internal sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val html: String, val baseUrl: String, val token: Long) : LoadState
    data class Failed(val message: String) : LoadState
}

/** The reader in its page colours: menus and panels take the theme's light or dark look. */
@Composable
fun ReaderScreen(
    bookId: String, focusHighlight: Pair<Int, String>? = null, onBack: () -> Unit,
    onOpenBook: (String) -> Unit = {}, onOpenFolder: (com.oasisreader.app.data.db.BookEntity) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext
    // a book in a 컬렉션 or a series group shares its settings with the rest of it
    val prefs = remember(bookId) { ReaderPrefs(app, bookId, com.oasisreader.app.data.SettingsGroup.of(app, bookId)) }
    var theme by remember(bookId) { mutableStateOf(prefs.theme) }
    val rTheme = theme.resolve(isAppDark())
    com.oasisreader.app.ui.theme.OasisTheme(darkTheme = rTheme.dark, scaleText = false) {
        ReaderBody(bookId, focusHighlight, onBack, prefs, theme, rTheme, onOpenBook, onOpenFolder) { t -> theme = t; prefs.theme = t }
    }
}

@Composable
private fun ReaderBody(
    bookId: String, focusHighlight: Pair<Int, String>?, onBack: () -> Unit,
    prefs: ReaderPrefs, theme: ReaderTheme, rTheme: ReaderTheme,
    onOpenBook: (String) -> Unit, onOpenFolder: (com.oasisreader.app.data.db.BookEntity) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
) {
    val context = LocalContext.current
    // 상태바·내비게이션 바 숨기기 (읽기 기본값), and the camera hole for the top line
    var readingChrome by remember { mutableStateOf(ReadingChrome.of(prefs)) }
    fun setChrome(c: ReadingChrome) { readingChrome = c; prefs.saveChrome(c) }
    val cut = rememberCutoutRoom()
    val app = context.applicationContext
    val store = remember { BookStore.get(app) }
    val db = remember { AppDatabase.get(app) }
    val fonts = remember { FontManager(app) }
    val scope = rememberCoroutineScope()

    var book by remember { mutableStateOf<BookEntity?>(null) }
    var pkg by remember { mutableStateOf<EpubPackage?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }     // files not on this phone (restored without books)
    var refetching by remember { mutableStateOf(false) }
    var openAttempt by remember { mutableIntStateOf(0) }

    var target by remember { mutableStateOf<Target?>(null) }
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var curIndex by remember { mutableIntStateOf(0) }
    // 지금 소제목 (2.9): the 목차 entry the page on screen is in, when one file holds several
    var section by remember { mutableStateOf<TocItem?>(null) }
    var curFraction by remember { mutableFloatStateOf(0f) }

    var barsVisible by remember { mutableStateOf(true) }
    var pageLabel by remember { mutableStateOf("") }
    var pageCovered by remember { mutableStateOf(false) }   // the page's own strip (multi-page highlight) is over the number
    var pageTotal by remember { mutableIntStateOf(0) }
    var fontPx by remember { mutableIntStateOf(prefs.fontPx) }
    var lineHeight by remember { mutableIntStateOf(prefs.lineHeight) }
    var mode by remember { mutableStateOf(prefs.mode) }
    var menuOpen by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var hlListOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var viewerFile by remember { mutableStateOf<File?>(null) }
    var turnStyle by remember { mutableStateOf(prefs.turnStyle) }
    // E-INK mode: pages change at once, whatever turn is chosen
    val effTurn = if (com.oasisreader.app.ui.theme.ThemeMode.eink) com.oasisreader.app.ui.TurnStyle.NONE else turnStyle
    var turnShot by remember { mutableStateOf<com.oasisreader.app.ui.TurnShot?>(null) }
    var turnPicker by remember { mutableStateOf(false) }
    var turnSpeed by remember { mutableStateOf(prefs.turnSpeed) }
    var turnHold by remember { mutableStateOf(false) }   // chapter change: old page waits on top until the new one is ready
    var dragShot by remember { mutableStateOf<com.oasisreader.app.ui.TurnShot?>(null) }   // curl under the finger
    var dragCssW by remember { mutableFloatStateOf(0f) }
    // the picture is taken asynchronously: finger moves / release that arrive before it are kept here
    var dragFinger by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var dragRelease by remember { mutableStateOf<Boolean?>(null) }
    var dragForward by remember { mutableStateOf(true) }
    LaunchedEffect(turnHold) { if (turnHold) { delay(2500); turnHold = false } }
    var mTop by remember { mutableIntStateOf(prefs.marginTop) }
    var mBottom by remember { mutableIntStateOf(prefs.marginBottom) }
    var mLeft by remember { mutableIntStateOf(prefs.marginLeft) }
    var mRight by remember { mutableIntStateOf(prefs.marginRight) }
    var publisherStyle by remember { mutableStateOf(prefs.publisherStyle) }
    var liftHeads by remember { mutableStateOf(prefs.liftHeads) }
    var fontId by remember { mutableStateOf(prefs.fontId) }
    var fontBusy by remember { mutableStateOf<String?>(null) }
    var fontProgress by remember { mutableFloatStateOf(0f) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var aaMenu by remember { mutableStateOf(false) }          // 90: Aa's small menu
    val lastAa = remember { arrayOf(Panel.TEXT) }             // the card keeps its panel while it slides away
    var brightness by remember { mutableFloatStateOf(prefs.brightness) }
    var weight by remember { mutableIntStateOf(prefs.weight) }
    var keepOn by remember { mutableStateOf(prefs.keepScreenOn) }
    var autoHide by remember { mutableStateOf(prefs.autoHideBars) }
    var barsShownAt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // ── open the book at its saved position ──
    LaunchedEffect(bookId, openAttempt) {
        try {
            val b = db.books().get(bookId) ?: throw IllegalStateException("서재에 없는 책입니다")
            book = b
            if (!store.hasFiles(bookId)) { missing = true; return@LaunchedEffect }
            missing = false; openError = null
            val p = withContext(Dispatchers.IO) { store.readPackage(bookId) }
            book = b; pkg = p
            com.oasisreader.app.data.Downloads.prefetchNext(app, b)   // 다음 회차 미리 받기 (TODO 78)
            val idx = (focusHighlight?.first ?: b.spineIndex).coerceIn(0, p.spine.size - 1)
            curIndex = idx; curFraction = b.chapterFraction
            target = Target(idx, if (focusHighlight != null) 0f else b.chapterFraction, focus = focusHighlight?.second)
        } catch (e: Exception) {
            openError = e.message ?: "책을 열 수 없습니다"
        }
    }

    // ── active reading time: counted only while the reader is in front and the
    //    reader has turned/scrolled/tapped within the last 3 minutes ──
    var lastActive by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(bookId) {
        var pending = 0L
        suspend fun flush() {
            if (pending <= 0) return
            val add = pending; pending = 0
            val day = LocalDate.now().toString()
            val dao = db.readingTime()
            val cur = dao.get(day, bookId)?.seconds ?: 0L
            dao.upsert(ReadingTimeEntity(day, bookId, cur + add))
        }
        try {
            while (true) {
                delay(5_000)
                val front = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                val active = System.currentTimeMillis() - lastActive < 180_000
                if (front && active && state is LoadState.Ready) pending += 5
                if (pending >= 30) flush()
            }
        } finally {
            withContext(NonCancellable) { flush() }
        }
    }

    // brightness only for this screen; back to the system level when leaving the reader
    val window = remember(context) { context.findActivity()?.window }

    // keep the screen on while reading; released after 10 minutes without any reading activity
    LaunchedEffect(keepOn, window) {
        val w = window ?: return@LaunchedEffect
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (!keepOn) { w.clearFlags(flag); return@LaunchedEffect }
        try {
            while (true) {
                if (System.currentTimeMillis() - lastActive < 600_000) w.addFlags(flag) else w.clearFlags(flag)
                delay(15_000)
            }
        } finally {
            w.clearFlags(flag)
        }
    }
    LaunchedEffect(brightness) {
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (brightness < 0f) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            window?.let { w ->
                w.attributes = w.attributes.apply {
                    screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    // facing pages only when the inner screen is open with the hinge vertical (book posture);
    // rotated (hinge horizontal) or the cover screen -> one page
    var spreadMode by remember { mutableIntStateOf(prefs.spreadMode) }
    val spread = rememberSpread(spreadMode)
    // Fold opened and turned sideways (hinge horizontal): the inner screen's punch hole
    // sits on a side edge. Samsung does not report it as a display cutout, so keep the
    // text clear of it with a fixed extra margin on both sides (only in this posture).
    val sideways = rememberSideways()
    val sidePad = if (sideways) SIDEWAYS_PAD_DP.dp else 0.dp
    // status bar hidden and nothing on the top line / no page number: the text takes that room
    val topFree = readingChrome.hideStatus && !readingChrome.statusLine
    val padTop = ((if (topFree) 26 else BAR_DP + 10) + mTop).coerceAtLeast(16)
    val bottomBase = if (readingChrome.pageNum) 34 else 16
    val padBottom = ((if (sideways) bottomBase + SIDEWAYS_EXTRA_BOTTOM else bottomBase) + mBottom).coerceAtLeast(12)
    val padLeft = (24 + mLeft).coerceAtLeast(6)
    val padRight = (24 + mRight).coerceAtLeast(6)

    // status bar icons follow the page background
    val dark = rTheme.dark
    val view = LocalView.current
    SideEffect {
        context.findActivity()?.window?.let {
            WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !dark
        }
    }

    // theme switched while a chapter is open: recolour it in place
    // colours changed while a chapter is open: recolour it in place
    LaunchedEffect(rTheme) {
        webView?.evaluateJavascript("window.Reader && Reader.setTheme(${ReaderHtml.themeJson(rTheme)}, ${rTheme.dark})", null)
    }

    fun fontHref(f: FontOption) = if (f.googleFamily != null) "https://$ASSET_HOST${fonts.cssPath(f)}" else null

    // ── build the page for the current target ──
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        val p = pkg ?: return@LaunchedEffect
        state = LoadState.Loading
        state = try {
            val item = p.spine[t.index]
            val doc = withContext(Dispatchers.IO) { ChapterLoader.load(store, bookId, item.path) }
            val hls = db.highlights().forChapter(bookId, t.index).map {
                PageHighlight(it.id, it.text, it.color, it.anchor)
            }
            val font = prefs.font.takeIf { fonts.isReady(it) } ?: FontManager.byId(null)
            val html = ReaderHtml.build(
                doc = doc,
                config = ReaderConfig(
                    paged = mode == ReaderMode.PAGE,
                    fontPx = fontPx,
                    lineHeight = lineHeight / 10f,
                    start = t.start,
                    anchor = t.anchor,
                    padTop = padTop,
                    padBottom = padBottom,
                    padLeft = padLeft,
                    padRight = padRight,
                    turn = effTurn.id,
                    fontFamily = font.cssStack(),
                    highlights = hls,
                    focus = t.focus,
                    find = t.find,
                    findNth = t.findNth,
                    animate = effTurn != com.oasisreader.app.ui.TurnStyle.NONE,
                    spread = spread,
                    weight = weight,
                    colors = ReaderHtml.COLORS,
                    docUrl = doc.url,
                    head = chapterTitleFor(p, t.index).ifBlank { book?.title.orEmpty() },
                    headShow = readingChrome.head && !readingChrome.hideStatus,
                    hasPrev = t.index > 0,
                    hasNext = t.index < p.spine.size - 1,
                    liftHeads = liftHeads,
                ),
                fontCssHref = fontHref(font),
                publisherStyle = publisherStyle,
                theme = rTheme,
            )
            curIndex = t.index
            pageLabel = ""
            pageCovered = false
            LoadState.Ready(html, doc.url, t.token)
        } catch (e: Exception) {
            LoadState.Failed("이 장을 여는 중 문제가 생겼습니다: ${e.message}")
        }
    }

    LaunchedEffect(barsVisible, barsShownAt, panel, menuOpen, tocOpen, hlListOpen, autoHide, aaMenu, state is LoadState.Ready) {
        // Aa's menu goes with the bars, so it does not pop up again with them (2.10.x)
        if (!barsVisible) aaMenu = false
        if (!autoHide || !barsVisible || state !is LoadState.Ready) return@LaunchedEffect
        if (panel != Panel.NONE || menuOpen || tocOpen || hlListOpen || aaMenu) return@LaunchedEffect
        delay(2_500)
        barsVisible = false
    }

    LaunchedEffect(spread, webView) {
        webView?.evaluateJavascript("window.Reader && Reader.setSpread($spread)", null)
    }
    // insets can change (rotation, fold/unfold)
    // the running head in the page: off, or drawn by the app on the status line instead
    val headInPage = readingChrome.head && !readingChrome.hideStatus
    LaunchedEffect(headInPage, webView) { webView?.evaluateJavascript("window.Reader && Reader.setHead($headInPage)", null) }
    LaunchedEffect(padTop, padBottom, padLeft, padRight, webView) {
        webView?.evaluateJavascript("window.Reader && Reader.setInsets($padTop, $padBottom, $padLeft, $padRight)", null)
    }

    fun reloadHere() { target = Target(curIndex, curFraction) }

    fun setMode(m: ReaderMode) {
        if (m == mode) return
        mode = m; prefs.mode = m
        pageLabel = ""   // the page reports again in the new mode
        webView?.evaluateJavascript("Reader.setMode('${if (m == ReaderMode.PAGE) "page" else "scroll"}')", null)
    }

    fun saveAsDefaults() {
        prefs.saveAsDefaults()
        com.oasisreader.app.ui.Notice.show(context, "이 책의 글꼴·크기·여백·배경·넘김 설정을 기본값으로 저장했습니다.")
    }

    /**
     * Where we were before following a link in the book (a note's "본문으로", a cross reference):
     * a pill offers the way back until it is used or closed. Chained jumps keep the first place.
     */
    var jumpBack by remember { mutableStateOf<Pair<Target, Int>?>(null) }
    fun rememberJump() {
        val p = pkg ?: return
        if (jumpBack == null) jumpBack = Target(curIndex, curFraction) to (p.overall(curIndex, curFraction) * 100).roundToInt()
    }

    fun goChapter(index: Int, start: Float = 0f, anchor: String? = null) {
        val p = pkg ?: return
        if (index !in p.spine.indices) return
        target = Target(index, start, anchor)
    }

    // ── 소리 내 읽기: a TTS engine reads from the page on screen, chapter after chapter ──
    val readAloud = remember { ReadAloud(app).also { it.useBook(bookId) } }
    var aloud by remember { mutableStateOf(false) }
    // 82: opened with 🔊 from the 서재, or it was being read aloud when last closed → start when the page is ready
    // 장 넘김 (83): the sound and the title to give when the next chapter starts being read
    val turn = remember { ChapterTurn() }
    var autoAloud by remember { mutableStateOf(ReadAloudStart.take(bookId) || readAloud.shouldResume()) }
    var voiceOpen by remember { mutableStateOf(false) }
    if (voiceOpen) VoiceDialog(readAloud) { voiceOpen = false }
    /**
     * Screen off / another app in front: the page cannot load the next chapter then (the UI is
     * paused), so the next chapters are read from their files here and the place is saved directly.
     * Back in front, the page opens where the voice is and takes over again.
     */
    class AloudBackground { var chapter = -1; var items: List<String> = emptyList() }
    val aloudBg = remember { AloudBackground() }
    fun inFront() = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    val aloudPlayer = remember {
        object : ReadAloudService.Player {
            override val playing get() = readAloud.playing
            override fun toggle() = readAloud.toggle()
            override fun skip(d: Int) = readAloud.skip(d)
            override fun close() { aloudClose?.invoke() }
            override fun dropResume() = readAloud.dropResume()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            readAloud.shutdown()
            if (ReadAloudService.player === aloudPlayer) ReadAloudService.hide(app)
            aloudClose = null
        }
    }
    fun stopAloud() {
        aloud = false
        readAloud.markAloud(false)
        aloudBg.chapter = -1
        turn.title = null; turn.cue = false
        readAloud.stop()
        ReadAloudService.hide(app)
        webView?.evaluateJavascript("window.Reader && Reader.ttsClear && Reader.ttsClear()", null)
    }
    aloudClose = { stopAloud() }
    /** 지금 소제목 (2.9): which 목차 entry of this file the page on screen is in. */
    fun refreshSection() {
        val pp = pkg ?: return
        val idx = curIndex
        val here = pp.toc.filter { pp.spineIndexOf(it.path) == idx }
        if (here.size <= 1) { section = here.firstOrNull(); return }
        val ids = org.json.JSONArray(here.map { it.fragment.orEmpty() }).toString()
        webView?.evaluateJavascript("window.Reader && Reader.sectionAt ? Reader.sectionAt(" + ids + ") : -1") { r ->
            if (idx == curIndex) section = here.getOrNull(r?.trim()?.toIntOrNull() ?: -1) ?: here.first()
        }
    }
    /** (Re)start at the first sentence on the page on screen, or at [match] (text, place in the chapter). */
    fun aloudFromHere(match: Pair<String, Float>? = null) {
        val wv = webView ?: return
        val args = if (match == null) "" else JSONObject.quote(match.first) + "," + match.second
        wv.evaluateJavascript("window.Reader && Reader.ttsList ? Reader.ttsList($args) : null") { r ->
            val o = runCatching { JSONObject(r) }.getOrNull()
            val arr = o?.optJSONArray("items")
            if (o == null || arr == null) {
                stopAloud()
                Toast.makeText(context, "이 화면에서는 소리 내 읽기를 할 수 없습니다.", Toast.LENGTH_SHORT).show()
            } else if (aloud) {
                readAloud.play(List(arr.length()) { arr.getString(it) }, o.optInt("start", 0), intro = turn.title, cue = turn.cue)
                turn.title = null; turn.cue = false
            }
        }
    }
    fun startAloud() {
        panel = Panel.NONE; barsVisible = false; aloud = true
        readAloud.markAloud(true)
        readAloud.language = pkg?.language
        ReadAloudService.show(app, aloudPlayer, book?.title.orEmpty(), pkg?.let { chapterTitleFor(it, curIndex) }.orEmpty())
        aloudFromHere()
    }
    // the player in the notification shade needs the notification permission (Android 13+); reading works without it
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { startAloud() }
    fun requestAloud() {
        val need = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (need) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else startAloud()
    }
    readAloud.onStateChanged = { ReadAloudService.update() }
    readAloud.onSentence = { i ->
        lastActive = System.currentTimeMillis()
        val bgIdx = aloudBg.chapter
        val pp = pkg
        if (bgIdx >= 0 && pp != null) {
            // read from the file: save the place every few sentences
            if (i % 4 == 0 && aloudBg.items.isNotEmpty()) {
                val f = (i.toFloat() / aloudBg.items.size).coerceIn(0f, 1f)
                scope.launch { db.books().savePosition(bookId, bgIdx, f, pp.overall(bgIdx, f), System.currentTimeMillis()) }
            }
        } else {
            webView?.evaluateJavascript("window.Reader && Reader.ttsMark && Reader.ttsMark($i)", null)
        }
    }
    readAloud.onFinished = {
        val pp = pkg
        val from = if (aloudBg.chapter >= 0) aloudBg.chapter else curIndex
        when {
            !aloud || pp == null -> {}
            (from + 1) !in pp.spine.indices -> { stopAloud(); Toast.makeText(context, "책 끝까지 읽었습니다.", Toast.LENGTH_SHORT).show() }
            inFront() && aloudBg.chapter < 0 -> {
                turn.cue = true; turn.title = turnTitle(pp, from, from + 1)
                goChapter(from + 1, 0f)   // goes on when the page is ready
            }
            else -> scope.launch {
                val next = from + 1
                val list = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ReadAloudText.sentences(File(store.contentDir(bookId), pp.spine[next].path).readText()) }.getOrDefault(emptyList())
                }
                if (!aloud) return@launch
                aloudBg.chapter = next; aloudBg.items = list
                db.books().savePosition(bookId, next, 0f, pp.overall(next, 0f), System.currentTimeMillis())
                ReadAloudService.update(chapter = chapterTitleFor(pp, next))
                readAloud.play(list, 0, intro = turnTitle(pp, from, next), cue = true)
            }
        }
    }
    readAloud.onUnavailable = {
        stopAloud()
        com.oasisreader.app.ui.Notice.show(context, "소리 내 읽기를 쓸 수 없습니다. 휴대폰 설정의 '텍스트 음성 변환'에서 엔진을 확인해 주세요.")
    }
    // back in front after reading on with the screen off: open the page where the voice is
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START && aloud && aloudBg.chapter >= 0) {
                val n = aloudBg.items.size
                val f = if (n > 0) (readAloud.sentence.toFloat() / n).coerceIn(0f, 1f) else 0f
                goChapter(aloudBg.chapter, f)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    /** A chapter opened while reading aloud: go on there (at the voice's sentence after reading from a file). */
    fun aloudOnReady() {
        if (!aloud && autoAloud) { autoAloud = false; requestAloud(); return }
        if (!aloud) return
        if (aloudBg.chapter >= 0) {
            val items = aloudBg.items
            val i = readAloud.sentence
            val match = items.getOrNull(i)?.let { it to (if (items.isEmpty()) 0f else i.toFloat() / items.size) }
            aloudBg.chapter = -1; aloudBg.items = emptyList()
            aloudFromHere(match)
        } else aloudFromHere()
        ReadAloudService.update(chapter = pkg?.let { chapterTitleFor(it, curIndex) })
    }

    // 다음 권: looked up once the last page is reached (the 컬렉션, then the folder it came from)
    var atBookEnd by remember { mutableStateOf(false) }
    var nextVol by remember { mutableStateOf<com.oasisreader.app.data.NextVolume?>(null) }
    var nextAsked by remember { mutableStateOf(false) }
    var nextClosed by remember { mutableStateOf(false) }
    LaunchedEffect(atBookEnd) {
        if (atBookEnd && !nextAsked) {
            nextAsked = true
            book?.let { b -> nextVol = runCatching { com.oasisreader.app.data.NextVolumes.find(app, b) }.getOrNull() }
        }
    }

    // The place is written at most every 1.5 s: each write also wakes every screen that watches
    // the 서재, and pages can turn much faster than that. The last place is written on leaving
    // (in the app's own scope, so it finishes though this screen is gone) and when the app goes
    // to the background.
    val pendingPlace = remember { arrayOfNulls<Triple<Int, Float, Float>>(1) }
    val placeJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    fun flushPlace() {
        val place = pendingPlace[0] ?: return
        pendingPlace[0] = null
        com.oasisreader.app.ui.ShelfActions.scope.launch {
            db.books().savePosition(bookId, place.first, place.second, place.third, System.currentTimeMillis())
        }
    }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) flushPlace() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); flushPlace() }
    }

    fun savePosition(fraction: Float, end: Boolean) {
        val p = pkg ?: return
        curFraction = fraction
        val idx = curIndex
        atBookEnd = end && idx == p.spine.size - 1
        val overall = p.overall(idx, fraction)
        pendingPlace[0] = Triple(idx, fraction, overall)
        if (placeJob[0]?.isActive != true) placeJob[0] = scope.launch { delay(1_500); flushPlace() }
        scope.launch {
            if (end && idx == p.spine.size - 1 && book?.finishedAt == null) {
                val now = System.currentTimeMillis()
                db.books().setFinished(bookId, now)
                book = book?.copy(finishedAt = now)
            }
        }
    }

    fun openLink(url: String) {
        val u = Uri.parse(url)
        if (u.host == ASSET_HOST) {
            val prefix = "/books/$bookId/content/"
            val path = u.path ?: return
            if (!path.startsWith(prefix)) return
            val rel = path.removePrefix(prefix)
            val idx = pkg?.spineIndexOf(rel) ?: -1
            if (idx >= 0) { rememberJump(); goChapter(idx, 0f, u.fragment) }
            return
        }
        if (u.scheme == "http" || u.scheme == "https" || u.scheme == "mailto") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u)) }
        }
    }

    fun imageFile(src: String): File? {
        val u = Uri.parse(src)
        if (u.host != ASSET_HOST) return null
        val path = u.path ?: return null
        if (!path.startsWith("/books/")) return null
        val f = File(store.root, path.removePrefix("/books/"))
        return f.takeIf { it.isFile && it.canonicalPath.startsWith(store.root.canonicalPath + File.separator) }
    }

    fun applyFont(f: FontOption) {
        fontId = f.id; prefs.fontId = f.id
        val href = fontHref(f).orEmpty()
        val stack = f.cssStack().replace("\\", "").replace("\"", "")
        webView?.evaluateJavascript("Reader.setFontFamily(" + JSONObject.quote(stack) + "," + JSONObject.quote(href) + ")", null)
    }

    val fontFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val f = fonts.importFont(app, uri)
                applyFont(f)
                Toast.makeText(context, "「${f.label}」 글꼴을 추가했습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                com.oasisreader.app.ui.Notice.show(context, "글꼴 추가 실패: ${e.message}")
            }
        }
    }

    fun pickFont(f: FontOption) {
        if (fontBusy != null) return
        if (fonts.isReady(f)) { applyFont(f); return }
        fontBusy = f.id; fontProgress = 0f
        scope.launch {
            try {
                fonts.download(f) { d, t -> fontProgress = d.toFloat() / t }
                applyFont(f)
            } catch (e: Exception) {
                com.oasisreader.app.ui.Notice.show(context, "글꼴 받기 실패: ${e.message}")
            } finally {
                fontBusy = null
            }
        }
    }

    fun resetBookSettings() {
        prefs.resetBook()
        readingChrome = ReadingChrome.of(prefs)
        fontPx = prefs.fontPx; lineHeight = prefs.lineHeight; mode = prefs.mode
        weight = prefs.weight; fontId = prefs.fontId; publisherStyle = prefs.publisherStyle; liftHeads = prefs.liftHeads
        turnStyle = prefs.turnStyle; turnSpeed = prefs.turnSpeed
        mTop = prefs.marginTop; mBottom = prefs.marginBottom; mLeft = prefs.marginLeft; mRight = prefs.marginRight
        onTheme(prefs.theme)
        prefs.resetBook()   // onTheme wrote the theme back for this book
        reloadHere()
        Toast.makeText(context, "이 책은 다시 기본 설정을 따릅니다.", Toast.LENGTH_SHORT).show()
    }

    // ── search & bookmarks ──
    val findState = remember { FindState() }
    var findOpen by remember { mutableStateOf(false) }
    var marksOpen by remember { mutableStateOf(false) }
    val bookmarks by remember(bookId) { db.bookmarks().observeFor(bookId) }.collectAsState(initial = emptyList())
    val hereMark = bookmarks.firstOrNull {
        bookmarkHere(it, curIndex, curFraction, if (mode == ReaderMode.PAGE) pageTotal else null)
    }
    fun toggleBookmark() {
        val m = hereMark
        if (m != null) {
            scope.launch { db.bookmarks().delete(m.id) }
            Toast.makeText(context, "책갈피를 지웠습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val idx = curIndex; val f = curFraction
        fun save(snippet: String) {
            scope.launch {
                db.bookmarks().insert(com.oasisreader.app.data.db.BookmarkEntity(
                    id = System.currentTimeMillis().toString(36) + (Math.random() * 1e6).toLong().toString(36),
                    bookId = bookId, spineIndex = idx, fraction = f, snippet = snippet, createdAt = System.currentTimeMillis(),
                ))
            }
            Toast.makeText(context, "책갈피를 저장했습니다.", Toast.LENGTH_SHORT).show()
        }
        val wv = webView
        if (wv == null) save("") else wv.evaluateJavascript("window.Reader && Reader.pageText()") { r ->
            save(runCatching { org.json.JSONTokener(r).nextValue() as? String }.getOrNull().orEmpty())
        }
    }
    if (findOpen) pkg?.let { pp ->
        FindDialog(
            state = findState, store = store, bookId = bookId, pkg = pp,
            onPick = { h, q -> findOpen = false; panel = Panel.NONE; target = Target(h.spineIndex, h.fraction, find = q, findNth = h.nth) },
            onDismiss = { findOpen = false },
        )
    }
    if (marksOpen) BookmarksDialog(
        list = bookmarks,
        where = { b ->
            val pp = pkg
            if (pp == null) "" else listOfNotNull(
                chapterTitleFor(pp, b.spineIndex).ifBlank { null },
                "${(pp.overall(b.spineIndex, b.fraction) * 100).roundToInt()}%",
            ).joinToString(" · ")
        },
        onPick = { b -> marksOpen = false; panel = Panel.NONE; goChapter(b.spineIndex, b.fraction) },
        onDelete = { b -> scope.launch { db.bookmarks().delete(b.id) } },
        onDismiss = { marksOpen = false },
    )

    var lookupText by remember { mutableStateOf<String?>(null) }
    lookupText?.let { t -> com.oasisreader.app.ui.LookupDialog(t) { lookupText = null } }

    var exportOpen by remember { mutableStateOf(false) }
    if (exportOpen) com.oasisreader.app.ui.HighlightExportDialog(bookId) { exportOpen = false }

    // bookoasis progress sync: offer another device's newer place, upload ours
    // the page picture fades out (instead of vanishing) when a curl is let go and the page stays
    val overlayAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    var remote by remember { mutableStateOf<com.oasisreader.app.data.KoSync.Remote?>(null) }
    com.oasisreader.app.ui.ProgressSync(
        bookId = bookId,
        document = book?.kosyncDoc,
        lastReadAt = book?.lastReadAt,
        ready = state is LoadState.Ready && pkg != null,
        positionKey = curIndex to curFraction,
        place = { "/body/DocFragment[${curIndex + 1}]/body" to (pkg?.overall(curIndex, curFraction) ?: 0f) },
        differs = { r ->
            val pp = pkg
            val t = pp?.let { remoteTarget(it, r) }
            pp != null && t != null &&
                (t.first != curIndex || kotlin.math.abs(pp.overall(t.first, t.second) - pp.overall(curIndex, curFraction)) > 0.005f)
        },
        onRemote = { remote = it },
    )
    remote?.let { r ->
        val pct = r.percentage?.let { "${(it * 100).roundToInt()}%" } ?: "다른 위치"
        com.oasisreader.app.ui.RemoteProgressDialog(
            where = "${com.oasisreader.app.ui.remoteDeviceLabel(r)}에서 ${pct}까지 읽었습니다.",
            onGo = {
                remote = null
                pkg?.let { pp -> remoteTarget(pp, r)?.let { (i, f) -> goChapter(i, f) } }
            },
            onStay = { remote = null },
        )
    }

    // page-turner remotes / keyboards: the same turn as a tap on the page edge
    com.oasisreader.app.ui.PageTurnKeys(enabled = state is LoadState.Ready) { dir, _ ->
        lastActive = System.currentTimeMillis()
        // with a settings panel open, the first press only closes it
        if (panel != Panel.NONE) { panel = Panel.NONE; return@PageTurnKeys }
        webView?.evaluateJavascript(if (dir > 0) "Reader.next()" else "Reader.prev()", null)
    }

    BackHandler {
        when {
            panel != Panel.NONE -> panel = Panel.NONE
            else -> onBack()
        }
    }

    val p = pkg
    val title = book?.title.orEmpty()
    val fileTitle = remember(p, curIndex) { p?.let { chapterTitleFor(it, curIndex) } ?: "" }
    val chapterTitle = section?.takeIf { p?.spineIndexOf(it.path) == curIndex }?.title?.trim()?.takeIf { it.isNotEmpty() } ?: fileTitle
    val overall = p?.overall(curIndex, curFraction) ?: 0f
    val paper = Color(rTheme.bg)
    val chrome = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (com.oasisreader.app.ui.theme.ThemeMode.eink) 1f else 0.75f)
    // bars sit two shades off the page so they read as separate from the text
    val barColor = com.oasisreader.app.ui.theme.barColorFor(dark)
    val showBars = barsVisible || state !is LoadState.Ready

    Box(Modifier.fillMaxSize().background(paper)) {
        if (showBars && !readingChrome.hideStatus) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(barColor))
        }
        Box(Modifier.fillMaxSize().readingInsets(readingChrome, cut)) {
            val s = state
            when {
                missing -> MissingFiles(
                    canFetch = book?.sourceUrl != null,
                    auto = book?.pinned == false,
                    busy = refetching,
                    onFetch = {
                        val url = book?.sourceUrl ?: return@MissingFiles
                        refetching = true
                        scope.launch {
                            try {
                                com.oasisreader.app.data.Downloads.receiveMissing(app, bookId, url)
                                openAttempt++
                            } catch (e: Exception) {
                                com.oasisreader.app.ui.Notice.show(context, "다시 받기 실패: ${e.message}")
                            } finally {
                                refetching = false
                            }
                        }
                    },
                    onBack = onBack,
                )
                openError != null -> Failure(openError!!, onRetry = null, onBack = onBack)
                s is LoadState.Failed -> Failure(s.message, onRetry = { reloadHere() }, onBack = onBack)
                else -> {
                    /**
                     * Picture of the page now on screen as the turn overlay, then [then] moves the
                     * text underneath. Copies the screen's pixels (WebView.draw() can render at
                     * another scale); draw() is only the fallback.
                     */
                    val densityF = androidx.compose.ui.platform.LocalDensity.current.density
                    fun captureTurn(forward: Boolean, tapY: Float, interactive: Boolean = false, then: () -> Unit) {
                        val wv = webView
                        if (wv == null || wv.width <= 0) { then(); return }
                        val bmp = android.graphics.Bitmap.createBitmap(wv.width, wv.height, android.graphics.Bitmap.Config.ARGB_8888)
                        fun show() {
                            // the page number is drawn live on top of the turn: blank it out of the
                            // picture so it does not show twice (the running head is part of the page)
                            val cv = android.graphics.Canvas(bmp)
                            val pnt = android.graphics.Paint().apply { color = paper.toArgb() }
                            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
                            cv.drawRect(w * 0.2f, h - 34 * densityF, w * 0.8f, h, pnt)
                            val shot = com.oasisreader.app.ui.TurnShot(bmp.asImageBitmap(), forward, effTurn, tapY, turnSpeed, interactive)
                            if (interactive) {
                                shot.finger = dragFinger
                                val early = dragRelease
                                if (early != null) shot.release = early else dragShot = shot
                            }
                            turnShot = shot
                            then()
                        }
                        fun drawFallback() { val c = android.graphics.Canvas(bmp); c.drawColor(paper.toArgb()); wv.draw(c) }
                        val win = context.findActivity()?.window
                        val loc = IntArray(2).also { wv.getLocationInWindow(it) }
                        if (win == null) { drawFallback(); show(); return }
                        android.view.PixelCopy.request(
                            win, android.graphics.Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height), bmp,
                            { result -> if (result != android.view.PixelCopy.SUCCESS) drawFallback(); show() },
                            android.os.Handler(android.os.Looper.getMainLooper()),
                        )
                    }
                    ReaderWebView(
                        store = store,
                        fonts = fonts,
                        page = s as? LoadState.Ready,
                        onCreated = { webView = it },
                        onPage = { cur, total ->
                            pageLabel = "$cur / $total"; pageTotal = total; lastActive = System.currentTimeMillis()
                            if (turnHold && state is LoadState.Ready) scope.launch {
                                // the new chapter has laid out: let it paint, then turn
                                androidx.compose.runtime.withFrameNanos { }
                                androidx.compose.runtime.withFrameNanos { }
                                turnHold = false
                            }
                        },
                        onProgress = { f, end ->
                            lastActive = System.currentTimeMillis()
                            savePosition(f, end)
                            refreshSection()
                        },
                        onTapCenter = {
                            lastActive = System.currentTimeMillis()
                            if (panel != Panel.NONE) panel = Panel.NONE
                            else { barsVisible = !barsVisible; if (barsVisible) barsShownAt = System.currentTimeMillis() }
                        },
                        onAddHighlight = { id, text, color, anchor ->
                            val idx = curIndex
                            scope.launch {
                                db.highlights().upsert(
                                    HighlightEntity(id, bookId, idx, text, color, anchor, null, System.currentTimeMillis())
                                )
                            }
                        },
                        onTapHighlight = { id -> deleteTarget = id },
                        onLookup = { t -> lookupText = t },
                        onOpenImage = { src -> imageFile(src)?.let { viewerFile = it } },
                        onEdge = { dir, tapY ->
                            lastActive = System.currentTimeMillis()
                            fun go() { if (dir > 0) goChapter(curIndex + 1, 0f) else goChapter(curIndex - 1, 1f) }
                            val p = pkg
                            val exists = p != null && (curIndex + dir) in p.spine.indices
                            if (exists && (turnStyle == com.oasisreader.app.ui.TurnStyle.CURL || turnStyle == com.oasisreader.app.ui.TurnStyle.COVER)) {
                                // next chapter: the old page waits on top while it loads, then turns away
                                captureTurn(dir > 0, tapY) { turnHold = true; go() }
                            } else go()
                        },
                        onLink = { url -> openLink(url) },
                        onJump = { rememberJump() },
                        onReady = { aloudOnReady() },   // a chapter opened while reading aloud
                        onCover = { on -> pageCovered = on },
                        onCopy = { t ->
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("text", t))
                            // Android 13+ shows its own copied notice
                            if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, "복사했습니다", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onDrag = { phase, x, y, extra ->
                            // a curl dragged with the finger: x, y in page CSS px (scaled to the view)
                            val wv = webView
                            val k = if (wv != null && dragCssW > 0f) wv.width / dragCssW else densityF
                            when (phase) {
                                0 -> {   // start: extra = direction, y = start height (0..1), x = page width (css)
                                    dragCssW = x
                                    val forward = extra > 0
                                    dragShot = null; dragFinger = null; dragRelease = null; dragForward = forward
                                    captureTurn(forward, y.coerceIn(0f, 1f), interactive = true) {
                                        scope.launch {
                                            androidx.compose.runtime.withFrameNanos { }
                                            androidx.compose.runtime.withFrameNanos { }
                                            webView?.evaluateJavascript("Reader.commitTurn()", null)
                                        }
                                    }
                                }
                                1 -> {
                                    val p = androidx.compose.ui.geometry.Offset(x * k, y * k)
                                    dragFinger = p
                                    dragShot?.finger = p
                                }
                                2 -> {   // end: x = horizontal distance moved (css), extra = speed (css px / ms)
                                    val moved = if (dragForward) -x else x
                                    val fling = if (dragForward) extra < -0.45f else extra > 0.45f
                                    val done = fling || moved > dragCssW * 0.25f
                                    val s = dragShot
                                    if (s != null) s.release = done else dragRelease = done
                                    dragShot = null
                                }
                            }
                            lastActive = System.currentTimeMillis()
                        },
                        onTurn = { forward, tapY ->
                            lastActive = System.currentTimeMillis()
                            captureTurn(forward, tapY) {
                                scope.launch {
                                    androidx.compose.runtime.withFrameNanos { }
                                    androidx.compose.runtime.withFrameNanos { }
                                    webView?.evaluateJavascript("Reader.commitTurn()", null)
                                }
                            }
                        },
                        sidePad = sidePad,
                    )
                    if (turnPicker) com.oasisreader.app.ui.TurnStyleDialog(
                        current = turnStyle, speed = turnSpeed,
                        onPick = { st ->
                            turnStyle = st; prefs.turnStyle = st
                            webView?.evaluateJavascript("Reader.setTurn('${st.id}')", null)
                        },
                        onSpeed = { sp -> turnSpeed = sp; prefs.turnSpeed = sp },
                        onDismiss = { turnPicker = false },
                    )
                    if (s !is LoadState.Ready) {
                        Box(Modifier.fillMaxSize().background(paper), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    com.oasisreader.app.ui.TurnOverlay(
                        shot = turnShot, paper = paper,
                        started = !turnHold,
                        onDone = { done, ok ->
                            if (ok) { if (turnShot === done) turnShot = null }
                            else scope.launch {
                                // let go early: the old page lies flat again on top; put it back underneath, then lift the picture.
                                // The WebView draws on its own thread: wait until it has really drawn the old page again
                                // (a fixed few frames was sometimes too early and the next page flashed), then fade the picture out.
                                val wv = webView
                                if (wv != null) kotlinx.coroutines.withTimeoutOrNull(600) {
                                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
                                        wv.evaluateJavascript("Reader.jumpBy(${if (done.forward) -1 else 1})") {
                                            wv.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                                                override fun onComplete(requestId: Long) { if (c.isActive) c.resumeWith(Result.success(Unit)) }
                                            })
                                        }
                                    }
                                }
                                androidx.compose.runtime.withFrameNanos { }
                                if (com.oasisreader.app.ui.theme.ThemeMode.eink) overlayAlpha.snapTo(0f) else overlayAlpha.animateTo(0f, androidx.compose.animation.core.tween(140))
                                if (turnShot === done) turnShot = null
                                overlayAlpha.snapTo(1f)
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(horizontal = sidePad).graphicsLayer { alpha = overlayAlpha.value },
                    )
                }
            }

            // a settings panel is open: a tap (or swipe) on the page only closes it — the page stays
            if (panel != Panel.NONE) Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                        panel = Panel.NONE
                        barsShownAt = System.currentTimeMillis()
                    }
                },
            )

            // where the status bar was: clock (left) and battery (right) on the chapter-title line
            // (it appears only once the bar has gone, not under it while it fades)
            AnimatedVisibility(
                visible = state is LoadState.Ready && !showBars,
                enter = com.oasisreader.app.ui.theme.calm(fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 250))), exit = fadeOut(androidx.compose.animation.core.tween(0)),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                StatusLine(readingChrome, cut, chrome.copy(alpha = 0.6f), title = chapterTitle.ifBlank { title })
            }

            // with the bars up, a tap or swipe on the page only puts them away (no page turn)
            if (panel == Panel.NONE && barsVisible && state is LoadState.Ready) Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                        barsVisible = false
                    }
                },
            )

            // the running head (chapter title) is drawn inside the page, so it turns with it
            com.oasisreader.app.ui.NoEdgeBackGesture(active = !barsVisible)
            ImmersiveBars(readingChrome, appBarsUp = barsVisible && panel !in AA_PANELS)

            // the panels' insides: ☰ under the top bar, Aa's three in the card at the bottom (90)
            val panelBody: @Composable ColumnScope.(Panel) -> Unit = { which ->
                    when (which) {
                        Panel.SCREEN -> {
                            BrightnessPanel(value = brightness, onChange = { brightness = it; prefs.brightness = it })
                            ThemeRow(theme, onTheme)
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                com.oasisreader.app.ui.PanelSwitch("화면 켜짐 유지", null, keepOn, { keepOn = it; prefs.keepScreenOn = it }, Modifier.weight(1f))
                                com.oasisreader.app.ui.PanelSwitch("바 자동 숨김", null, autoHide, {
                                    autoHide = it; prefs.autoHideBars = it; barsShownAt = System.currentTimeMillis()
                                }, Modifier.weight(1f))
                            }
                            // 🔊 볼륨 버튼으로 쪽 넘기기 (the same setting as in 설정, easier to find here)
                            var volKeys by remember { mutableStateOf(com.oasisreader.app.ui.PageKeys.volumeKeys(context)) }
                            com.oasisreader.app.ui.PanelSwitch("🔊 볼륨 버튼으로 쪽 넘기기", null, volKeys, { volKeys = it; com.oasisreader.app.ui.PageKeys.setVolumeKeys(context, it) })
                        }
                        Panel.TEXT -> {
                            FontPanel(
                                fonts = fonts,
                                selectedId = fontId,
                                busyId = fontBusy,
                                progress = fontProgress,
                                onPick = { f -> pickFont(f) },
                                onAdd = {
                                    fontFilePicker.launch(arrayOf("font/ttf", "font/otf", "font/woff", "font/woff2", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream"))
                                },
                                onRemove = { f ->
                                    fonts.removeCustom(f)
                                    applyFont(FontManager.byId(null))
                                    Toast.makeText(context, "「${f.label}」 글꼴을 삭제했습니다.", Toast.LENGTH_SHORT).show()
                                },
                            )
                            SizePanel(
                                weight = weight,
                                onWeight = { w ->
                                    weight = w; prefs.weight = w
                                    webView?.evaluateJavascript("Reader.setWeight($w)", null)
                                },
                                value = fontPx,
                                onChange = { v ->
                                    if (v != fontPx) {
                                        fontPx = v; prefs.fontPx = v
                                        webView?.evaluateJavascript("Reader.setFont($v)", null)
                                    }
                                },
                                lineHeight = lineHeight,
                                onLineHeight = { lh ->
                                    lineHeight = lh; prefs.lineHeight = lh
                                    webView?.evaluateJavascript("Reader.setLineHeight(${lh / 10f})", null)
                                },
                            )
                            com.oasisreader.app.ui.PanelSwitch("책 디자인 사용 (제목·문단 모양)", null, publisherStyle, {
                                publisherStyle = it; prefs.publisherStyle = it; reloadHere()
                            })
                            com.oasisreader.app.ui.PanelSwitch("작은 소제목 키우기", null, liftHeads, {
                                liftHeads = it; prefs.liftHeads = it; reloadHere()
                            })
                            com.oasisreader.app.ui.BookSettingsRow(prefs.hasOwn, ::saveAsDefaults, ::resetBookSettings)
                        }
                        Panel.PAGE -> {
                            com.oasisreader.app.ui.PanelChoice("보기", listOf("책처럼 넘기기", "스크롤"), if (mode == ReaderMode.PAGE) 0 else 1) { i ->
                                setMode(if (i == 0) ReaderMode.PAGE else ReaderMode.SCROLL)
                            }
                            if (mode == ReaderMode.PAGE) com.oasisreader.app.ui.PanelChoice("두 쪽 보기", SPREAD_LABELS, spreadMode) { i ->
                                spreadMode = i; prefs.spreadMode = i
                            }
                            if (mode == ReaderMode.PAGE) com.oasisreader.app.ui.TurnStylePicker(
                                current = turnStyle, speed = turnSpeed,
                                onPick = { st ->
                                    turnStyle = st; prefs.turnStyle = st
                                    webView?.evaluateJavascript("Reader.setTurn('${st.id}')", null)
                                },
                                onSpeed = { sp -> turnSpeed = sp; prefs.turnSpeed = sp },
                            )
                            MarginPanel(
                                top = mTop, bottom = mBottom, left = mLeft, right = mRight,
                                onChange = { t, b, l, r ->
                                    mTop = t; mBottom = b; mLeft = l; mRight = r
                                    prefs.marginTop = t; prefs.marginBottom = b; prefs.marginLeft = l; prefs.marginRight = r
                                },
                            )
                            ChromeSwitches(readingChrome, onChange = ::setChrome)
                            com.oasisreader.app.ui.BookSettingsRow(prefs.hasOwn, ::saveAsDefaults, ::resetBookSettings)
                        }
                        Panel.NAV -> {
                            val finished = book?.finishedAt != null
                            fun T(e: String, l: String, f: () -> Unit) = com.oasisreader.app.ui.PanelTile(e, l, f)
                            com.oasisreader.app.ui.PanelGrid(buildList {
                                if (p != null) add(T("📑", "목차") { panel = Panel.NONE; tocOpen = true })
                                if (p != null) add(T("🔍", "검색") { findOpen = true })
                                if (p != null) add(T("🔊", if (aloud) "읽기 끝" else "소리 읽기") { if (aloud) stopAloud() else requestAloud() })
                                add(T("🔖", if (bookmarks.isEmpty()) "책갈피" else "책갈피 ${bookmarks.size}") { marksOpen = true })
                                add(T("🖍️", "하이라이트") { panel = Panel.NONE; hlListOpen = true })
                                add(T("📝", "내보내기") { panel = Panel.NONE; exportOpen = true })
                                add(T(if (finished) "↩️" else "✅", if (finished) "읽음 취소" else "다 읽음") {
                                    val at = if (finished) null else System.currentTimeMillis()
                                    book = book?.copy(finishedAt = at)
                                    scope.launch { db.books().setFinished(bookId, at) }
                                })
                                add(T("⏭️", "다음 권") {
                                    panel = Panel.NONE
                                    scope.launch { findAndOpenNext(context, book, nextVol, onOpenBook) }
                                })
                                if (canOpenOrigin(book)) add(T("📂", "있던 폴더") {
                                    panel = Panel.NONE
                                    book?.let(onOpenFolder)
                                })
                            })
                        }
                        else -> {}
                    }
            }

            // compact overlay bar: it only ever covers the top margin, never text
            // 96: while Aa's card is open the bars go, so the page shows the change clearly
            AnimatedVisibility(visible = showBars && panel !in AA_PANELS, enter = com.oasisreader.app.ui.theme.calm(fadeIn()), exit = com.oasisreader.app.ui.theme.calm(fadeOut())) {
                Surface(
                    color = barColor, contentColor = chrome, border = com.oasisreader.app.ui.theme.einkBorder(),
                    modifier = Modifier.fillMaxWidth().let { if (readingChrome.hideStatus) it.padding(start = cut.left, end = cut.right) else it },
                ) {
                    Column {
                        Row(
                            Modifier.height(TOP_BAR_DP.dp).padding(end = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                color = chrome,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            // 90: 🎧 소리 내 읽기 · Aa 보기 (부메뉴 → 아래 카드) · ☰ 이동 — three roomy buttons
                            val on = MaterialTheme.colorScheme.primary
                            if (p != null) IconButton(onClick = { if (aloud) stopAloud() else requestAloud() }) {
                                EmojiText("🎧", fontSize = 20.sp, color = if (aloud) on else chrome)
                            }
                            Box {
                                IconButton(onClick = { aaMenu = true }) {
                                    Text("Aa", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (panel in AA_PANELS) on else chrome)
                                }
                                com.oasisreader.app.ui.OgMenu(expanded = aaMenu, onDismissRequest = { aaMenu = false }) {
                                    listOf(Triple(Panel.SCREEN, "☀️", "화면"), Triple(Panel.TEXT, "가", "글자"), Triple(Panel.PAGE, "▤", "쪽")).forEach { (pp, e, l) ->
                                        DropdownMenuItem(
                                            text = { Text(l) },
                                            leadingIcon = { EmojiText(e, fontSize = 16.sp) },
                                            onClick = { aaMenu = false; panel = pp },
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { panel = panel.toggle(Panel.NAV) }) {
                                EmojiText("☰", fontSize = 20.sp, color = if (panel == Panel.NAV) on else chrome)
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        AnimatedVisibility(
                            visible = panel == Panel.NAV,
                            enter = com.oasisreader.app.ui.theme.calm(expandVertically() + fadeIn()),
                            exit = com.oasisreader.app.ui.theme.calm(shrinkVertically() + fadeOut()),
                        ) {
                            Column {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.42f).dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    panelBody(panel)
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }

            // after following a link: the way back to where the reading was
            jumpBack?.let { (from, pct) ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 6.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(bottom = if (showBars) 104.dp else 44.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "↩️ 원래 보던 곳으로 · $pct%",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clickable { jumpBack = null; goChapter(from.index, from.start) }
                                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        )
                        Text(
                            "✕",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clickable { jumpBack = null }.padding(start = 6.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
                                .semantics { contentDescription = "여기서 계속 읽기" },
                        )
                    }
                }
            }

            // the end of the book: the next volume, one tap away
            nextVol?.let { n ->
                if (atBookEnd && !nextClosed && jumpBack == null) NextVolumeCard(
                    n, onOpen = { openNextVolume(context, n, onOpenBook) }, onDismiss = { nextClosed = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = if (showBars) 104.dp else 44.dp),
                )
            }

            // 91: the top-right corner puts a bookmark in / takes it out (where the ribbon shows)
            if (state is LoadState.Ready && !showBars && panel == Panel.NONE) Box(
                Modifier.align(Alignment.TopEnd).let { if (readingChrome.hideStatus) it else it.statusBarsPadding() }
                    .size(56.dp)
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { toggleBookmark() }
                    .semantics { contentDescription = if (hereMark != null) "책갈피 빼기" else "책갈피 꽂기" },
            )

            // this page is bookmarked: a small ribbon in the top corner
            if (hereMark != null && state is LoadState.Ready && !showBars) BookmarkGlyph(
                filled = true, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), width = 11.dp,
                modifier = Modifier.align(Alignment.TopEnd).let { if (readingChrome.hideStatus) it else it.statusBarsPadding() }.padding(end = 18.dp + sidePad),
            )

            // page indicator — book mode: "3 / 34 · 12%", scroll mode: "12%"
            if (readingChrome.pageNum && !pageCovered && (state is LoadState.Ready || turnShot != null)) {
                val pct = "${(overall * 100).roundToInt()}%"
                val label = if (mode == ReaderMode.PAGE && pageLabel.isNotEmpty()) "$pageLabel  ·  $pct" else pct
                if (mode == ReaderMode.SCROLL) {
                    // the text runs under the label: give it a solid strip of page colour
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp).background(paper))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = chrome.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                )
            }

            // reading aloud: the small player above the page number (and above the slider when shown)
            if (aloud && state is LoadState.Ready) ReadAloudBar(
                ra = readAloud,
                fill = barColor,
                onVoice = { voiceOpen = true },
                onClose = { stopAloud() },
                title = title, chapter = chapterTitle,
                cover = remember(bookId) { store.coverFile(bookId) },
                // 82: the bottom-left edge, clear of the page number in the middle
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = if (showBars) 70.dp else 10.dp),
            )

            // with the top bar: a bottom bar whose thin slider spans the whole book
            if (p != null) AnimatedVisibility(
                visible = showBars && state is LoadState.Ready && panel !in AA_PANELS,
                enter = com.oasisreader.app.ui.theme.calm(fadeIn()), exit = com.oasisreader.app.ui.theme.calm(fadeOut()),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ReaderSeekBar(
                    pkg = p, overall = overall, barColor = barColor, chrome = chrome,
                    onDrag = { barsShownAt = System.currentTimeMillis(); lastActive = System.currentTimeMillis() },
                    onSeek = { v ->
                        val (i, f) = seekTarget(p, v)
                        goChapter(i, f)
                    },
                )
            }

            // 90: Aa's panels (화면 · 글자 · 쪽) in a card at the bottom, the page above left as it is
            // (no dimming) so a change shows while it is made; tabs move between the three
            if (panel in AA_PANELS) lastAa[0] = panel
            // 97: setting the bottom margin, the card moves to the top (out of the way of what changes)
            val cardTop = lastAa[0] == Panel.PAGE && MarginEdit.side == 1
            AnimatedVisibility(
                visible = panel in AA_PANELS,
                enter = com.oasisreader.app.ui.theme.calm(fadeIn()),
                exit = com.oasisreader.app.ui.theme.calm(fadeOut()),
                modifier = Modifier.align(if (cardTop) Alignment.TopCenter else Alignment.BottomCenter),
            ) {
                val which = lastAa[0]
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = barColor.copy(alpha = if (com.oasisreader.app.ui.theme.ThemeMode.eink) 1f else 0.94f), contentColor = chrome,
                    border = com.oasisreader.app.ui.theme.einkBorder(), shadowElevation = 6.dp,
                    modifier = (if (cardTop) Modifier.statusBarsPadding() else Modifier.navigationBarsPadding()).padding(10.dp).fillMaxWidth(),
                ) {
                    Column(
                        Modifier.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.5f).dp)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            listOf(Triple(Panel.SCREEN, "☀️", "화면"), Triple(Panel.TEXT, "가", "글자"), Triple(Panel.PAGE, "▤", "쪽")).forEach { (pp, e, l) ->
                                com.oasisreader.app.ui.OgChip(
                                    selected = which == pp, onClick = { panel = pp },
                                    label = { EmojiText("$e $l", fontSize = 14.sp) },
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { panel = Panel.NONE; barsVisible = true; barsShownAt = System.currentTimeMillis() }) { Text("✕", color = chrome) }
                        }
                        Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { panelBody(which) }
                    }
                }
            }
        }
    }

    if (tocOpen && p != null) {
        TocDialog(
            pkg = p,
            currentIndex = curIndex,
            current = section,
            onPick = { item ->
                tocOpen = false
                val idx = p.spineIndexOf(item.path)
                if (idx >= 0) goChapter(idx, 0f, item.fragment)
            },
            onDismiss = { tocOpen = false },
        )
    }

    if (hlListOpen) {
        BookHighlightsDialog(
            bookId = bookId,
            db = db,
            onPick = { h ->
                hlListOpen = false
                target = Target(h.spineIndex, 0f, focus = h.id)
            },
            onDismiss = { hlListOpen = false },
        )
    }

    viewerFile?.let { f -> ImageViewer(file = f, onClose = { viewerFile = null }) }

    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("하이라이트 삭제") },
            text = { Text("이 하이라이트를 지우시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    webView?.evaluateJavascript("Reader.removeHighlight('${id.replace("'", "")}')", null)
                    scope.launch { db.highlights().delete(id) }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }
}
