package com.oasisreader.app.reader

import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import com.oasisreader.app.ui.theme.isAppDark
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
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
import com.oasisreader.app.epub.EpubPackage
import com.oasisreader.app.epub.TocItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

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
    val pt = remember { PageTurnState(prefs) }
    // E-INK mode: pages change at once, whatever turn is chosen
    val effTurn = if (com.oasisreader.app.ui.theme.ThemeMode.eink) com.oasisreader.app.ui.TurnStyle.NONE else pt.turnStyle
    LaunchedEffect(pt.turnHold) { if (pt.turnHold) { delay(2500); pt.turnHold = false } }
    var mTop by remember { mutableIntStateOf(prefs.marginTop) }
    var mBottom by remember { mutableIntStateOf(prefs.marginBottom) }
    var mLeft by remember { mutableIntStateOf(prefs.marginLeft) }
    var mRight by remember { mutableIntStateOf(prefs.marginRight) }
    var publisherStyle by remember { mutableStateOf(prefs.publisherStyle) }
    var liftHeads by remember { mutableStateOf(prefs.liftHeads) }
    val fs = remember { FontState(prefs) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var aaMenu by remember { mutableStateOf(false) }          // 90: Aa's small menu
    val lastAa = remember { arrayOf(Panel.TEXT) }             // the card keeps its panel while it slides away
    var brightness by remember { mutableFloatStateOf(prefs.brightness) }
    var weight by remember { mutableIntStateOf(prefs.weight) }
    var keepOn by remember { mutableStateOf(prefs.keepScreenOn) }
    var autoHide by remember { mutableStateOf(prefs.autoHideBars) }
    var barsShownAt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // ── 소리 내 읽기: a TTS engine reads from the page on screen, chapter after chapter ──
    val al = remember { ReaderAloud(app, bookId) }
    val readAloud = al.readAloud
    // 106: reading aloud with 스크롤 보기 on shows the book in scroll view — for the time being only,
    // the book's (or group's) saved view is not touched
    fun tempScroll() = al.active && readAloud.scrollView && !readAloud.viewPicked
    fun viewMode() = if (tempScroll()) ReaderMode.SCROLL else mode
    val shownMode = viewMode()
    /** The open page into [viewMode], at the same place; nothing when it already is. */
    fun syncView() {
        webView?.evaluateJavascript("window.Reader && Reader.setView && Reader.setView('${if (viewMode() == ReaderMode.PAGE) "page" else "scroll"}')", null)
    }
    LaunchedEffect(shownMode, webView) { syncView() }

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
    ReadingTimeTracker(bookId, db, lifecycleOwner, lastActive = { lastActive }, ready = { state is LoadState.Ready })

    // brightness only for this screen; back to the system level when leaving the reader
    val window = remember(context) { context.findActivity()?.window }

    ReaderWindowEffects(window, keepOn, brightness, lastActive = { lastActive })

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
            val ready = buildChapterPage(store, db, fonts, prefs, bookId, p, t, fontHref = ::fontHref) {
                PageInputs(
                    paged = viewMode() == ReaderMode.PAGE, fontPx = fontPx, lineHeight = lineHeight,
                    padTop = padTop, padBottom = padBottom, padLeft = padLeft, padRight = padRight,
                    turn = effTurn, spread = spread, weight = weight,
                    headShow = readingChrome.head && !readingChrome.hideStatus, liftHeads = liftHeads,
                    publisherStyle = publisherStyle, theme = rTheme, bookTitle = book?.title.orEmpty(),
                )
            }
            curIndex = t.index
            pageLabel = ""
            pageCovered = false
            ready
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
        if (m == viewMode()) return
        if (tempScroll()) readAloud.viewPicked = true
        if (m != mode) { mode = m; prefs.mode = m }
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

    // ── 소리 내 읽기: what it needs from this screen ──
    al.host = AloudHost(
        context = context, scope = scope, db = db, store = store, lifecycleOwner = lifecycleOwner,
        webView = { webView }, pkg = { pkg }, book = { book }, curIndex = { curIndex },
        goChapter = { i, f -> goChapter(i, f) }, touchActive = { lastActive = System.currentTimeMillis() },
        clearForReading = { panel = Panel.NONE; barsVisible = false },
    )
    if (al.voiceOpen) VoiceDialog(readAloud) { al.voiceOpen = false }
    DisposableEffect(Unit) {
        onDispose {
            readAloud.shutdown()
            if (ReadAloudService.player === al.player) ReadAloudService.hide(app)
            aloudClose = null
        }
    }
    aloudClose = { al.stop() }
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
    // the player in the notification shade needs the notification permission (Android 13+); reading works without it
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { al.start() }
    fun requestAloud() = al.request { notifPermission.launch(it) }
    al.bind()
    // back in front after reading on with the screen off: open the page where the voice is
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) al.onStart()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
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
    val places = remember { PlaceSaver(db, bookId) }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) places.flush() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); places.flush() }
    }

    fun savePosition(fraction: Float, end: Boolean) {
        val p = pkg ?: return
        curFraction = fraction
        val idx = curIndex
        atBookEnd = end && idx == p.spine.size - 1
        val overall = p.overall(idx, fraction)
        places.note(idx, fraction, overall, scope)
        scope.launch {
            if (end && idx == p.spine.size - 1 && book?.finishedAt == null) {
                val now = System.currentTimeMillis()
                db.books().setFinished(bookId, now)
                book = book?.copy(finishedAt = now)
            }
        }
    }

    fun applyFont(f: FontOption) = fs.apply(f, prefs, fontHref(f), webView)

    val fontFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        fs.importPicked(uri, scope, app, context, fonts, ::applyFont)
    }

    fun pickFont(f: FontOption) = fs.pick(f, scope, context, fonts, ::applyFont)

    fun resetBookSettings() {
        prefs.resetBook()
        readingChrome = ReadingChrome.of(prefs)
        fontPx = prefs.fontPx; lineHeight = prefs.lineHeight; mode = prefs.mode
        weight = prefs.weight; fs.fontId = prefs.fontId; publisherStyle = prefs.publisherStyle; liftHeads = prefs.liftHeads
        pt.turnStyle = prefs.turnStyle; pt.turnSpeed = prefs.turnSpeed
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
        bookmarkHere(it, curIndex, curFraction, if (shownMode == ReaderMode.PAGE) pageTotal else null)
    }
    fun toggleBookmark() = toggleBookmarkHere(hereMark, bookId, curIndex, curFraction, db, scope, context, webView)
    ReaderSearchMarkDialogs(
        findOpen = findOpen, findState = findState, store = store, bookId = bookId, pkg = pkg,
        onFindPick = { h, q -> findOpen = false; panel = Panel.NONE; target = Target(h.spineIndex, h.fraction, find = q, findNth = h.nth) },
        onFindDismiss = { findOpen = false },
        marksOpen = marksOpen, bookmarks = bookmarks,
        onMarkPick = { b -> marksOpen = false; panel = Panel.NONE; goChapter(b.spineIndex, b.fraction) },
        onMarkDelete = { b -> scope.launch { db.bookmarks().delete(b.id) } },
        onMarksDismiss = { marksOpen = false },
    )

    var lookupText by remember { mutableStateOf<String?>(null) }
    lookupText?.let { t -> com.oasisreader.app.ui.LookupDialog(t) { lookupText = null } }

    var exportOpen by remember { mutableStateOf(false) }
    if (exportOpen) com.oasisreader.app.ui.HighlightExportDialog(bookId) { exportOpen = false }

    // bookoasis progress sync: offer another device's newer place, upload ours
    // the page picture fades out (instead of vanishing) when a curl is let go and the page stays
    val overlayAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    var remote by remember { mutableStateOf<com.oasisreader.app.data.KoSync.Remote?>(null) }
    ReaderRemoteSync(
        bookId = bookId, document = book?.kosyncDoc, lastReadAt = book?.lastReadAt, ready = state is LoadState.Ready && pkg != null,
        curIndex = curIndex, curFraction = curFraction, pkg = pkg,
        remote = remote, onRemote = { remote = it }, onGo = { i, f -> goChapter(i, f) },
    )

    // page-turner remotes / keyboards: the same turn as a tap on the page edge
    com.oasisreader.app.ui.PageTurnKeys(enabled = state is LoadState.Ready) { dir, _ ->
        lastActive = System.currentTimeMillis()
        // with a settings panel open, the first press only closes it
        if (panel != Panel.NONE) { panel = Panel.NONE; return@PageTurnKeys }
        webView?.evaluateJavascript(if (dir > 0) "Reader.next()" else "Reader.prev()", null)
    }

    // 104: read full screen (bars away), back does not leave the book — it brings the bars; with
    // them up it leaves. What is open closes first: a panel, then the page's selection or note popup.
    BackHandler {
        fun barsThenLeave() {
            if (barsVisible) onBack() else { barsVisible = true; barsShownAt = System.currentTimeMillis() }
        }
        val wv = webView
        when {
            panel != Panel.NONE -> panel = Panel.NONE
            // no page to read (not opened, files missing, failed): leave as before
            pkg == null || missing || openError != null || state is LoadState.Failed -> onBack()
            wv == null || state !is LoadState.Ready -> barsThenLeave()
            else -> wv.evaluateJavascript("window.Reader && Reader.back ? Reader.back() : false") { closed ->
                if (closed != "true") barsThenLeave()
            }
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
                        com.oasisreader.app.ui.WifiAsk.run(context) {
                            refetching = true
                            fetchMissingBook(scope, app, context, bookId, url, onRetry = { openAttempt++ }, onEnd = { refetching = false })
                        }
                    },
                    onBack = onBack,
                )
                openError != null -> Failure(openError!!, onRetry = null, onBack = onBack)
                s is LoadState.Failed -> Failure(s.message, onRetry = { reloadHere() }, onBack = onBack)
                else -> {
                    val densityF = androidx.compose.ui.platform.LocalDensity.current.density
                    fun captureTurn(forward: Boolean, tapY: Float, interactive: Boolean = false, then: () -> Unit) =
                        pt.capture(webView, context, densityF, paper, effTurn, forward, tapY, interactive, then)
                    fun commitTurn() {
                        scope.launch {
                            androidx.compose.runtime.withFrameNanos { }
                            androidx.compose.runtime.withFrameNanos { }
                            webView?.evaluateJavascript("Reader.commitTurn()", null)
                        }
                    }
                    ReaderWebView(
                        store = store,
                        fonts = fonts,
                        page = s as? LoadState.Ready,
                        onCreated = { webView = it },
                        onPage = { cur, total ->
                            pageLabel = "$cur / $total"; pageTotal = total; lastActive = System.currentTimeMillis()
                            if (pt.turnHold && state is LoadState.Ready) scope.launch {
                                // the new chapter has laid out: let it paint, then turn
                                androidx.compose.runtime.withFrameNanos { }
                                androidx.compose.runtime.withFrameNanos { }
                                pt.turnHold = false
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
                        onOpenImage = { src -> bookImageFile(src, store)?.let { viewerFile = it } },
                        onEdge = { dir, tapY ->
                            lastActive = System.currentTimeMillis()
                            fun go() { if (dir > 0) goChapter(curIndex + 1, 0f) else goChapter(curIndex - 1, 1f) }
                            val p = pkg
                            val exists = p != null && (curIndex + dir) in p.spine.indices
                            if (exists && (pt.turnStyle == com.oasisreader.app.ui.TurnStyle.CURL || pt.turnStyle == com.oasisreader.app.ui.TurnStyle.COVER)) {
                                // next chapter: the old page waits on top while it loads, then turns away
                                captureTurn(dir > 0, tapY) { pt.turnHold = true; go() }
                            } else go()
                        },
                        onLink = { url -> openBookLink(url, bookId, pkg, context) { idx, frag -> rememberJump(); goChapter(idx, 0f, frag) } },
                        onJump = { rememberJump() },
                        onReady = { syncView(); al.onReady { requestAloud() } },   // a chapter opened while reading aloud
                        onCover = { on -> pageCovered = on },
                        onCopy = { t -> copyToClipboard(context, t) },
                        onDrag = { phase, x, y, extra ->
                            pt.drag(phase, x, y, extra, webView, densityF, { fwd, ty, then -> captureTurn(fwd, ty, interactive = true, then) }, ::commitTurn)
                            lastActive = System.currentTimeMillis()
                        },
                        onTurn = { forward, tapY ->
                            lastActive = System.currentTimeMillis()
                            captureTurn(forward, tapY) { commitTurn() }
                        },
                        sidePad = sidePad,
                    )
                    if (pt.turnPicker) com.oasisreader.app.ui.TurnStyleDialog(
                        current = pt.turnStyle, speed = pt.turnSpeed,
                        onPick = { st ->
                            pt.turnStyle = st; prefs.turnStyle = st
                            webView?.evaluateJavascript("Reader.setTurn('${st.id}')", null)
                        },
                        onSpeed = { sp -> pt.turnSpeed = sp; prefs.turnSpeed = sp },
                        onDismiss = { pt.turnPicker = false },
                    )
                    if (s !is LoadState.Ready) {
                        Box(Modifier.fillMaxSize().background(paper), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    com.oasisreader.app.ui.TurnOverlay(
                        shot = pt.turnShot, paper = paper,
                        started = !pt.turnHold,
                        onDone = { done, ok -> pt.overlayDone(done, ok, scope, { webView }, overlayAlpha) },
                        modifier = Modifier.fillMaxSize().padding(horizontal = sidePad).graphicsLayer { alpha = overlayAlpha.value },
                    )
                }
            }

            // a settings panel is open: a tap (or swipe) on the page only closes it — the page stays
            if (panel != Panel.NONE) Box(
                Modifier.fillMaxSize().swallowTouch { panel = Panel.NONE; barsShownAt = System.currentTimeMillis() },
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
                Modifier.fillMaxSize().swallowTouch { barsVisible = false },
            )

            // the running head (chapter title) is drawn inside the page, so it turns with it
            com.oasisreader.app.ui.NoEdgeBackGesture(active = !barsVisible)
            ImmersiveBars(readingChrome, appBarsUp = barsVisible && panel !in AA_PANELS)

            // the panels' insides: ☰ under the top bar, Aa's three in the card at the bottom (90)
            val panelBody: @Composable ColumnScope.(Panel) -> Unit = { which ->
                    when (which) {
                        Panel.SCREEN -> ScreenPanelBody(
                            brightness = brightness, onBrightness = { brightness = it; prefs.brightness = it },
                            theme = theme, onTheme = onTheme,
                            keepOn = keepOn, onKeepOn = { keepOn = it; prefs.keepScreenOn = it },
                            autoHide = autoHide, onAutoHide = { autoHide = it; prefs.autoHideBars = it; barsShownAt = System.currentTimeMillis() },
                        )
                        Panel.TEXT -> TextPanelBody(
                            prefs = prefs, fonts = fonts, webView = { webView },
                            fontId = fs.fontId, fontBusy = fs.fontBusy, fontProgress = fs.fontProgress,
                            onPickFont = { f -> pickFont(f) },
                            onAddFont = {
                                fontFilePicker.launch(arrayOf("font/ttf", "font/otf", "font/woff", "font/woff2", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream"))
                            },
                            onRemoveFont = { f ->
                                fonts.removeCustom(f)
                                applyFont(FontManager.byId(null))
                                Toast.makeText(context, "「${f.label}」 글꼴을 삭제했습니다.", Toast.LENGTH_SHORT).show()
                            },
                            weight = weight, onWeight = { weight = it },
                            fontPx = fontPx, onFontPx = { fontPx = it },
                            lineHeight = lineHeight, onLineHeight = { lineHeight = it },
                            publisherStyle = publisherStyle, onPublisherStyle = { publisherStyle = it },
                            liftHeads = liftHeads, onLiftHeads = { liftHeads = it },
                            onReload = ::reloadHere, onSaveDefaults = ::saveAsDefaults, onReset = ::resetBookSettings,
                        )
                        Panel.PAGE -> PagePanelBody(
                            prefs = prefs, webView = { webView },
                            mode = shownMode, onMode = { setMode(it) },
                            spreadMode = spreadMode, onSpreadMode = { spreadMode = it },
                            turnStyle = pt.turnStyle, turnSpeed = pt.turnSpeed, onTurnStyle = { pt.turnStyle = it }, onTurnSpeed = { pt.turnSpeed = it },
                            mTop = mTop, mBottom = mBottom, mLeft = mLeft, mRight = mRight,
                            onMargins = { t, bt, l, r -> mTop = t; mBottom = bt; mLeft = l; mRight = r },
                            readingChrome = readingChrome, onChrome = ::setChrome,
                            onSaveDefaults = ::saveAsDefaults, onReset = ::resetBookSettings,
                        )
                        Panel.NAV -> NavPanelBody(
                            hasBook = p != null, aloud = al.active, bookmarkCount = bookmarks.size,
                            finished = book?.finishedAt != null, canOrigin = canOpenOrigin(book),
                            onToc = { panel = Panel.NONE; tocOpen = true },
                            onSearch = { findOpen = true },
                            onAloud = { if (al.active) al.stop() else requestAloud() },
                            onMarks = { marksOpen = true },
                            onHighlights = { panel = Panel.NONE; hlListOpen = true },
                            onExport = { panel = Panel.NONE; exportOpen = true },
                            onFinished = { finished ->
                                val at = if (finished) null else System.currentTimeMillis()
                                book = book?.copy(finishedAt = at)
                                scope.launch { db.books().setFinished(bookId, at) }
                            },
                            onNext = {
                                panel = Panel.NONE
                                scope.launch { findAndOpenNext(context, book, nextVol, onOpenBook) }
                            },
                            onFolder = {
                                panel = Panel.NONE
                                book?.let(onOpenFolder)
                            },
                        )
                        else -> {}
                    }
            }

            ReaderTopBar(
                showBars = showBars, panel = panel, barColor = barColor, chrome = chrome,
                readingChrome = readingChrome, cut = cut, title = title,
                hasBook = p != null, aloud = al.active, aaMenu = aaMenu,
                onBack = onBack, onAloud = { if (al.active) al.stop() else requestAloud() },
                onAaMenu = { aaMenu = it }, onPanel = { panel = it },
                panelBody = panelBody,
            )

            ReaderJumpPill(jumpBack, showBars, onGo = { from -> goChapter(from.index, from.start) }, onClose = { jumpBack = null })

            // the end of the book: the next volume, one tap away
            nextVol?.let { n ->
                if (atBookEnd && !nextClosed && jumpBack == null) NextVolumeCard(
                    n, onOpen = { openNextVolume(context, n, onOpenBook) }, onDismiss = { nextClosed = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = if (showBars) 104.dp else 44.dp),
                )
            }

            ReaderBookmarkCorner(
                ready = state is LoadState.Ready, showBars = showBars, panelNone = panel == Panel.NONE, marked = hereMark != null,
                readingChrome = readingChrome, sidePad = sidePad, onToggle = { toggleBookmark() },
            )

            ReaderPageLabel(
                readingChrome = readingChrome, pageCovered = pageCovered, ready = state is LoadState.Ready, turnShown = pt.turnShot != null,
                mode = shownMode, pageLabel = pageLabel, overall = overall, paper = paper, chrome = chrome,
            )

            // reading aloud: the small player above the page number (and above the slider when shown)
            if (al.active && state is LoadState.Ready) ReadAloudBar(
                ra = readAloud,
                fill = barColor,
                onVoice = { al.voiceOpen = true },
                onClose = { al.stop() },
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
            ReaderAaCard(
                visible = panel in AA_PANELS, which = lastAa[0], cardTop = cardTop, barColor = barColor, chrome = chrome,
                modifier = Modifier.align(if (cardTop) Alignment.TopCenter else Alignment.BottomCenter),
                onPanel = { panel = it },
                onClose = { panel = Panel.NONE; barsVisible = true; barsShownAt = System.currentTimeMillis() },
                panelBody = panelBody,
            )
        }
    }

    ReaderTailDialogs(
        pkg = p, curIndex = curIndex, section = section,
        tocOpen = tocOpen,
        onTocPick = { item ->
            tocOpen = false
            val idx = p?.spineIndexOf(item.path) ?: -1
            if (idx >= 0) goChapter(idx, 0f, item.fragment)
        },
        onTocDismiss = { tocOpen = false },
        hlListOpen = hlListOpen, bookId = bookId, db = db,
        onHlPick = { h ->
            hlListOpen = false
            target = Target(h.spineIndex, 0f, focus = h.id)
        },
        onHlDismiss = { hlListOpen = false },
        viewerFile = viewerFile, onViewerClose = { viewerFile = null },
        deleteTarget = deleteTarget, onDeleteDismiss = { deleteTarget = null },
        onDeleteConfirm = { id ->
            deleteTarget = null
            webView?.evaluateJavascript("Reader.removeHighlight('${id.replace("'", "")}')", null)
            scope.launch { db.highlights().delete(id) }
        },
    )
}
