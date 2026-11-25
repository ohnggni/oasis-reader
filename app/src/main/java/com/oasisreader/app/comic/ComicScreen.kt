package com.oasisreader.app.comic

import com.oasisreader.app.data.ComicPackage
import com.oasisreader.app.data.PdfPages
import com.oasisreader.app.ui.EmojiText
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import com.oasisreader.app.ui.theme.isAppDark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.oasisreader.app.reader.readingInsets
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Vertical, gap-free webtoon viewer for CBZ/ZIP books. */
@Composable
fun ComicScreen(bookId: String, onBack: () -> Unit, onOpenBook: (String) -> Unit = {}, onOpenFolder: (BookEntity) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext
    val store = remember { BookStore.get(app) }
    val db = remember { AppDatabase.get(app) }

    var book by remember { mutableStateOf<BookEntity?>(null) }
    var pkg by remember { mutableStateOf<ComicPackage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }
    var refetching by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(bookId, attempt) {
        try {
            val b = db.books().get(bookId) ?: throw IllegalStateException("서재에 없는 책입니다")
            book = b
            if (!store.hasFiles(bookId)) { missing = true; return@LaunchedEffect }
            missing = false
            pkg = withContext(Dispatchers.IO) { store.readComic(bookId) }
            com.oasisreader.app.data.Downloads.prefetchNext(app, b)   // 다음 회차 미리 받기 (TODO 78)
        } catch (e: Exception) {
            error = e.message ?: "열 수 없습니다"
        }
    }

    BackHandler(onBack = onBack)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Thread { PdfPages.close() }.start() } }
    val b = book
    val p = pkg
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            error != null -> Message(error!!, onBack)
            missing -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmojiText("📦", fontSize = 40.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    // 바로 읽기 · 등록만 (TODO 78): received without asking — say so
                    if (b?.sourceUrl != null && b.pinned == false && refetching) "서버에서 받는 중입니다…"
                    else if (b?.sourceUrl != null) "이 웹툰 파일이 이 폰에 없습니다.\n서버에서 다시 받을 수 있습니다."
                    else "이 웹툰 파일이 이 폰에 없습니다.\n파일에서 다시 가져와 주세요.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                val url = b?.sourceUrl
                if (url != null) {
                    fun fetch() {
                        refetching = true
                        scope.launch {
                            try {
                                com.oasisreader.app.data.Downloads.receiveMissing(app, bookId, url)
                                attempt++
                            } catch (e: Exception) {
                                com.oasisreader.app.ui.Notice.show(context, "다시 받기 실패: ${e.message}")
                            } finally {
                                refetching = false
                            }
                        }
                    }
                    // a "바로 읽기" book whose file was dropped from the cache: fetch it without asking
                    LaunchedEffect(Unit) { if (b?.pinned == false && !refetching) fetch() }
                    if (refetching) CircularProgressIndicator()
                    else Button(onClick = { fetch() }) { EmojiText("⬇️ 다시 받기") }
                }
                TextButton(onClick = onBack) { Text("뒤로") }
            }
            b == null || p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> ComicViewer(b, p, store, db, onBack, onOpenBook, onOpenFolder)
        }
    }
}

@Composable
private fun Message(text: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        TextButton(onClick = onBack) { Text("뒤로") }
    }
}

enum class ComicMode { PAGE, SCROLL }

/** Spread groups: which pages share a screen. Wide pages (already a double-page scan) stay alone. */
internal fun groupsOf(pkg: ComicPackage, spread: Boolean, coverAlone: Boolean): List<List<Int>> {
    if (!spread) return pkg.pages.indices.map { listOf(it) }
    val out = ArrayList<List<Int>>()
    var i = 0
    val n = pkg.pages.size
    if (coverAlone && n > 0) { out.add(listOf(0)); i = 1 }
    while (i < n) {
        val wide = pkg.pages[i].w > pkg.pages[i].h
        val nextWide = i + 1 < n && pkg.pages[i + 1].w > pkg.pages[i + 1].h
        if (wide || i + 1 >= n || nextWide) { out.add(listOf(i)); i++ }
        else { out.add(listOf(i, i + 1)); i += 2 }
    }
    return out
}

@Composable
private fun ComicViewer(
    book: BookEntity, pkg: ComicPackage, store: BookStore, db: AppDatabase, onBack: () -> Unit,
    onOpenBook: (String) -> Unit, onOpenFolder: (BookEntity) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val prefs = remember { app.getSharedPreferences("comic", android.content.Context.MODE_PRIVATE) }
    // a comic in a 컬렉션 or a series group (웹툰 episodes) shares its view with the rest of it
    val group = remember(book.id) { com.oasisreader.app.data.SettingsGroup.of(app, book.id) }
    val modeKey = "mode_" + (group ?: book.id)
    val readerPrefs = remember { com.oasisreader.app.reader.ReaderPrefs(app, book.id, group) }
    // 상태바·내비게이션 바 숨기기 (읽기 기본값, shared with books)
    var readingChrome by remember { mutableStateOf(com.oasisreader.app.reader.ReadingChrome.of(readerPrefs)) }
    val cut = com.oasisreader.app.reader.rememberCutoutRoom()
    val scope = rememberCoroutineScope()
    val content = remember(book.id) { store.contentDir(book.id) }
    val pageCount = pkg.pages.size
    val isPdf = remember(pkg) { pkg.pages.firstOrNull()?.path?.let { PdfPages.isPdfPage(it) } == true }

    var mode by remember {
        // this book's own choice, else 읽기 기본값 › 만화·PDF › 보기. There is no guessing by page
        // shape any more (TODO 42: webtoons cut to book-sized pages cannot be told from comics);
        // an old "AUTO" default reads as 책처럼 넘기기
        mutableStateOf(
            runCatching { ComicMode.valueOf(prefs.getString(modeKey, null) ?: "") }.getOrNull()
                ?: runCatching { ComicMode.valueOf(prefs.getString("mode_${book.id}", null) ?: "") }.getOrNull()
                ?: runCatching { ComicMode.valueOf(prefs.getString("default_mode", null) ?: "") }.getOrNull()
                ?: ComicMode.PAGE
        )
    }
    var rtl by remember { mutableStateOf(prefs.getBoolean("rtl", false)) }
    var coverAlone by remember { mutableStateOf(prefs.getBoolean("cover_alone", true)) }
    var narrow by remember { mutableStateOf(prefs.getBoolean("narrow", false)) }
    var keepOn by remember { mutableStateOf(readerPrefs.keepScreenOn) }
    // same brightness setting as the book reader; only while this screen is open
    var brightness by remember { androidx.compose.runtime.mutableFloatStateOf(readerPrefs.brightness) }
    // top-bar panels, the same four as the book reader: 화면 · 보기 · 넘김 · 이동
    val panelState = remember { mutableStateOf(com.oasisreader.app.ui.ReadPanel.NONE) }
    var panel by panelState
    var aaMenu by remember { mutableStateOf(false) }          // 98: Aa's small menu
    val lastAa = remember { arrayOf(com.oasisreader.app.ui.ReadPanel.TEXT) }
    var autoHide by remember { mutableStateOf(readerPrefs.autoHideBars) }
    val turnStyleState = remember { mutableStateOf(readerPrefs.turnStyle) }
    var turnStyle by turnStyleState
    // E-INK mode: pages change at once, whatever turn is chosen
    val effTurn = if (com.oasisreader.app.ui.theme.ThemeMode.eink) com.oasisreader.app.ui.TurnStyle.NONE else turnStyle
    val turnShotState = remember { mutableStateOf<com.oasisreader.app.ui.TurnShot?>(null) }
    var turnShot by turnShotState
    var turnPicker by remember { mutableStateOf(false) }
    val turnSpeedState = remember { mutableStateOf(readerPrefs.turnSpeed) }
    var turnSpeed by turnSpeedState
    var finished by remember { mutableStateOf(book.finishedAt != null) }
    // 다음 권 (looked up at the last page)
    var nextVol by remember { mutableStateOf<com.oasisreader.app.data.NextVolume?>(null) }
    // 이전 권 (웹툰 스크롤: asked after pulling down at the top)
    var prevVol by remember { mutableStateOf<com.oasisreader.app.data.NextVolume?>(null) }
    var prevAsked by remember { mutableStateOf(false) }
    var prevSearching by remember { mutableStateOf(false) }
    var prevAsk by remember { mutableStateOf(false) }
    fun askPrevious() {
        if (prevAsk) return
        prevAsk = true
        if (!prevAsked) {
            prevAsked = true; prevSearching = true
            scope.launch {
                prevVol = runCatching { com.oasisreader.app.data.NextVolumes.findPrevious(app, book) }.getOrNull()
                prevSearching = false
            }
        }
    }
    var nextAsked by remember { mutableStateOf(false) }
    var nextClosed by remember { mutableStateOf(false) }
    var nextSearching by remember { mutableStateOf(false) }
    // webtoon scroll: the "— 끝 —" tail came on screen (the next volume lives there, not in a card)
    var tailSeen by remember { mutableStateOf(false) }

    // position, shared by both modes: page index + fraction inside that page (scroll mode)
    val curPageState = remember { mutableIntStateOf(book.spineIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) }
    var curPage by curPageState
    val curFracState = remember { mutableStateOf(book.chapterFraction.coerceIn(0f, 1f)) }
    var curFrac by curFracState
    val lastPageShownState = remember { mutableIntStateOf(curPage) }   // last page on screen (spreads)
    var lastPageShown by lastPageShownState
    var viewKey by remember { mutableIntStateOf(0) }                // bump to rebuild the view at curPage

    var barsVisible by remember { mutableStateOf(true) }
    var barsShownAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var menuOpen by remember { mutableStateOf(false) }
    val lastActiveState = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastActive by lastActiveState

    val progress = if (pageCount <= 1) 1f else ((lastPageShown + 1).toFloat() / pageCount).coerceIn(0f, 1f)

    // save (debounced) whenever the position changes
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(curPage, curFrac, lastPageShown) }.collectLatest { (pg, fr, last) ->
            lastActive = System.currentTimeMillis()
            delay(400)
            val prog = if (pageCount <= 1) 1f else ((last + 1).toFloat() / pageCount).coerceIn(0f, 1f)
            db.books().savePosition(book.id, pg, fr, prog, System.currentTimeMillis())
            if (last >= pageCount - 1 && !finished) {
                finished = true
                db.books().setFinished(book.id, System.currentTimeMillis())
            }
        }
    }

    // bookoasis progress sync: KOReader page numbers (1-based) — the web viewer uses them too
    val overlayAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    // bookmarks: a page each
    val bookmarks by remember(book.id) { db.bookmarks().observeFor(book.id) }.collectAsState(initial = emptyList())
    val hereMark = bookmarks.firstOrNull { it.spineIndex == curPage || it.spineIndex == lastPageShown }
    var marksOpen by remember { mutableStateOf(false) }
    fun toggleBookmark() {
        val m = hereMark
        if (m != null) {
            scope.launch { db.bookmarks().delete(m.id) }
            android.widget.Toast.makeText(context, "책갈피를 지웠습니다.", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val pg = curPage
            scope.launch {
                db.bookmarks().insert(com.oasisreader.app.data.db.BookmarkEntity(
                    id = System.currentTimeMillis().toString(36) + (Math.random() * 1e6).toLong().toString(36),
                    bookId = book.id, spineIndex = pg, fraction = 0f, snippet = "${pg + 1}쪽", createdAt = System.currentTimeMillis(),
                ))
            }
            android.widget.Toast.makeText(context, "책갈피를 저장했습니다.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    if (marksOpen) com.oasisreader.app.reader.BookmarksDialog(
        list = bookmarks,
        where = { b -> "${b.spineIndex + 1} / $pageCount" },
        onPick = { b -> marksOpen = false; panel = com.oasisreader.app.ui.ReadPanel.NONE; curPage = b.spineIndex; curFrac = 0f; lastPageShown = b.spineIndex; viewKey++ },
        onDelete = { b -> scope.launch { db.bookmarks().delete(b.id) } },
        onDismiss = { marksOpen = false },
    )
    var remote by remember { mutableStateOf<com.oasisreader.app.data.KoSync.Remote?>(null) }
    fun remotePage(r: com.oasisreader.app.data.KoSync.Remote): Int? =
        (r.pageNumber?.minus(1) ?: r.percentage?.let { (it * pageCount).toInt() - 1 })
            ?.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    com.oasisreader.app.ui.ProgressSync(
        bookId = book.id,
        document = book.kosyncDoc,
        lastReadAt = book.lastReadAt,
        ready = true,
        positionKey = curPage to lastPageShown,
        place = { (lastPageShown + 1).toString() to progress },
        differs = { r -> remotePage(r)?.let { it != curPage && it != lastPageShown } == true },
        onRemote = { remote = it },
    )
    remote?.let { r ->
        val page = remotePage(r)
        com.oasisreader.app.ui.RemoteProgressDialog(
            where = "${com.oasisreader.app.ui.remoteDeviceLabel(r)}에서 ${page?.plus(1) ?: "?"}쪽까지 읽었습니다 (전체 ${pageCount}쪽).",
            onGo = {
                remote = null
                if (page != null) { curPage = page; curFrac = 0f; lastPageShown = page; viewKey++ }
            },
            onStay = { remote = null },
        )
    }

    // active reading time (same rule as the book reader)
    val lifecycleOwner = LocalLifecycleOwner.current
    ComicReadingTime(book.id, db, lifecycleOwner, lastActive = { lastActive })

    // keep the screen on while reading (released after 10 minutes idle)
    com.oasisreader.app.ui.NoEdgeBackGesture(active = !barsVisible)
    com.oasisreader.app.reader.ImmersiveBars(readingChrome, appBarsUp = barsVisible)
    val window = remember(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is Activity) c = c.baseContext
        (c as? Activity)?.window
    }
    ComicWindowEffects(window, keepOn, brightness, lastActive = { lastActive })

    // the bar hides itself
    LaunchedEffect(barsVisible, barsShownAt, panel, autoHide, aaMenu) {
        // Aa's menu open: the bar stays; the bar gone: the menu goes too, so it does not pop up again with it (2.10.x)
        if (!barsVisible) { panel = com.oasisreader.app.ui.ReadPanel.NONE; aaMenu = false; return@LaunchedEffect }
        if (panel != com.oasisreader.app.ui.ReadPanel.NONE || aaMenu || !autoHide) return@LaunchedEffect
        delay(2_500)
        barsVisible = false
    }
    fun toggleBars() { barsVisible = !barsVisible; if (barsVisible) barsShownAt = System.currentTimeMillis() }

    val dark = isAppDark()
    val barColor = com.oasisreader.app.ui.theme.barColorFor(dark)
    val chrome = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val labelState = remember { mutableStateOf("") }
    var label by labelState

    // with a bar hidden: a band at the bottom for the page number, so it does not sit on the picture
    // (not in the webtoon scroll, which runs under it as before), and the clock/battery line on top
    val pageBand = readingChrome.pageNum && (readingChrome.hideStatus || readingChrome.hideNav) && mode == ComicMode.PAGE
    val topLine = if (readingChrome.statusLine) com.oasisreader.app.reader.STATUS_LINE_TOP + com.oasisreader.app.reader.STATUS_LINE_HEIGHT + 4.dp else 0.dp
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier.fillMaxSize().readingInsets(readingChrome, cut, topLine).padding(bottom = if (pageBand) 24.dp else 0.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val wPx = with(density) { maxWidth.roundToPx() }
            val hPx = with(density) { maxHeight.roundToPx() }
            // landscape (or the opened fold held sideways): two pages side by side, no gap
            val spread = maxWidth > maxHeight * 1.05f
            if (mode == ComicMode.PAGE) {
                ComicPageMode(
                    pkg = pkg, content = content, isPdf = isPdf, spread = spread, wPx = wPx, hPx = hPx, density = density,
                    rtl = rtl, coverAlone = coverAlone, viewKey = viewKey, effTurn = effTurn, scope = scope,
                    readerPrefs = readerPrefs, overlayAlpha = overlayAlpha, turnPicker = turnPicker,
                    curPageState = curPageState, curFracState = curFracState, lastPageShownState = lastPageShownState,
                    labelState = labelState, panelState = panelState, turnShotState = turnShotState,
                    turnStyleState = turnStyleState, turnSpeedState = turnSpeedState, lastActiveState = lastActiveState,
                    askPrevious = ::askPrevious, toggleBars = ::toggleBars,
                    onTurnPickerDismiss = { turnPicker = false },
                )
            } else {
                val slices = remember(pkg) { slicesOf(pkg) }
                val firstSliceOf = remember(slices) {
                    IntArray(pageCount).also { arr ->
                        arr.fill(-1); slices.forEachIndexed { i, s -> if (arr[s.page] < 0) arr[s.page] = i }
                    }
                }
                key(viewKey) {
                    val startSlice = remember {
                        val first = firstSliceOf.getOrElse(curPage) { 0 }.coerceAtLeast(0)
                        val count = slices.count { it.page == curPage }.coerceAtLeast(1)
                        (first + (curFrac * count).toInt()).coerceIn(0, (slices.size - 1).coerceAtLeast(0))
                    }
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startSlice)
                    LaunchedEffect(listState) {
                        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                            .collect { (fi, li) ->
                                val s0 = slices.getOrNull(fi) ?: return@collect
                                val count = slices.count { it.page == s0.page }.coerceAtLeast(1)
                                curPage = s0.page
                                curFrac = ((fi - firstSliceOf[s0.page]).toFloat() / count).coerceIn(0f, 1f)
                                val tail = (li ?: 0) >= slices.size
                                if (tail) tailSeen = true
                                lastPageShown = if (!listState.canScrollForward || tail) pageCount - 1
                                                else slices.getOrNull(li ?: fi)?.page ?: s0.page
                                label = "${s0.page + 1} / $pageCount"
                            }
                    }
                    // at the very end (the "— 끝 —" tail on screen), pulling up a little more opens the
                    // next volume — as asked from the community; the button stays for a plain tap
                    val pullPx = with(density) { 110.dp.toPx() }
                    var pulled by remember { mutableFloatStateOf(0f) }
                    var pullOpened by remember { mutableStateOf(false) }
                    // at the very top, pulling down a little more asks about the volume before (going
                    // back is rarer, so it asks instead of opening straight away)
                    var pulledTop by remember { mutableFloatStateOf(0f) }
                    // measured from the finger itself: at an edge the stretch effect takes the scroll
                    // left over, so leftovers (nested scroll) never added up (1.9 브랜치 빌드)
                    val pullToNext = Modifier.pointerInput(listState) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            pulled = 0f; pulledTop = 0f
                            while (true) {
                                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val c = e.changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                val dy = c.position.y - c.previousPosition.y
                                // finger up = reading on: at the end, count it
                                if (dy < 0f && !listState.canScrollForward) pulled += -dy
                                else if (dy > 0f) pulled = 0f
                                // finger down = going back: at the top, count it
                                if (dy > 0f && !listState.canScrollBackward) pulledTop += dy
                                else if (dy < 0f) pulledTop = 0f
                                val n = nextVol
                                if (pulled >= pullPx && n != null && !pullOpened) {
                                    pullOpened = true
                                    com.oasisreader.app.reader.openNextVolume(context, n, onOpenBook)
                                }
                                if (pulledTop >= pullPx) { pulledTop = 0f; askPrevious() }
                            }
                            pulled = 0f; pulledTop = 0f
                        }
                    }
                    val viewW = if (narrow) minOf(maxWidth, 600.dp) else maxWidth
                    val targetPx = with(density) { viewW.roundToPx() }
                    val viewportH = hPx.toFloat()
                    com.oasisreader.app.ui.PageTurnKeys { dir, _ ->
                        lastActive = System.currentTimeMillis()
                        scope.launch { listState.animateScrollBy(dir * viewportH * 0.85f) }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .widthIn(max = viewW)
                            .fillMaxSize()
                            .then(pullToNext)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { o ->
                                    lastActive = System.currentTimeMillis()
                                    val y = o.y / size.height
                                    when {
                                        y < 0.25f -> scope.launch { listState.animateScrollBy(-viewportH * 0.85f) }
                                        y > 0.75f -> scope.launch { listState.animateScrollBy(viewportH * 0.85f) }
                                        else -> toggleBars()
                                    }
                                })
                            },
                    ) {
                        itemsIndexed(slices, key = { i, _ -> i }) { _, s ->
                            Strip(File(content, s.path), s, targetPx)
                        }
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("— 끝 —", color = chrome)
                                val n = nextVol
                                if (n != null) {
                                    androidx.compose.material3.FilledTonalButton(
                                        onClick = { com.oasisreader.app.reader.openNextVolume(context, n, onOpenBook) },
                                        modifier = Modifier.padding(top = 12.dp).widthIn(max = 420.dp).padding(horizontal = 24.dp),
                                    ) { EmojiText("▶ 다음 권  ${n.title}", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                                    Text(
                                        if (pulled > 0f) "조금 더 끌어 올리면 다음 권을 엽니다" else "끝에서 위로 더 끌어 올려도 다음 권으로 넘어갑니다",
                                        color = chrome, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 6.dp).graphicsLayer { alpha = if (pulled > 0f) 1f else 0.7f },
                                    )
                                } else if (nextSearching) Text("다음 권 찾는 중…", color = chrome, modifier = Modifier.padding(top = 12.dp))
                                TextButton(onClick = onBack) { Text("서재로") }
                            }
                        }
                    }
                }
            }
        }

        // with the bars up (no panel), a tap or swipe on the page only puts them away — no page turn
        if (panel == com.oasisreader.app.ui.ReadPanel.NONE && barsVisible) Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                    barsVisible = false
                }
            },
        )
        // a settings panel is open: a tap (or swipe) on the page only closes it — the page stays
        if (panel != com.oasisreader.app.ui.ReadPanel.NONE) Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                    panel = com.oasisreader.app.ui.ReadPanel.NONE
                    barsShownAt = System.currentTimeMillis()
                }
            },
        )
        if (barsVisible && !readingChrome.hideStatus) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(barColor))
        }
        AnimatedVisibility(
            visible = !barsVisible,
            enter = com.oasisreader.app.ui.theme.calm(fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 250))), exit = fadeOut(androidx.compose.animation.core.tween(0)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            com.oasisreader.app.reader.StatusLine(readingChrome, cut, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), title = book.title)
        }
        // the panels' insides: ☰ under the top bar, Aa's three in the card at the bottom (98)
        val comicPanelBody: @Composable androidx.compose.foundation.layout.ColumnScope.(com.oasisreader.app.ui.ReadPanel) -> Unit = { which ->
            when (which) {
                com.oasisreader.app.ui.ReadPanel.SCREEN -> ComicScreenPanel(
                    readerPrefs = readerPrefs,
                    brightness = brightness, onBrightness = { brightness = it },
                    keepOn = keepOn, onKeepOn = { keepOn = it },
                    autoHide = autoHide, onAutoHide = { autoHide = it; barsShownAt = System.currentTimeMillis() },
                    readingChrome = readingChrome, onChrome = { readingChrome = it },
                )
                com.oasisreader.app.ui.ReadPanel.TEXT -> ComicViewPanel(
                    prefs = prefs, modeKey = modeKey,
                    mode = mode, onMode = { mode = it },
                    rtl = rtl, onRtl = { rtl = it },
                    coverAlone = coverAlone, onCoverAlone = { coverAlone = it },
                    narrow = narrow, onNarrow = { narrow = it },
                )
                com.oasisreader.app.ui.ReadPanel.PAGE -> ComicTurnPanel(
                    readerPrefs = readerPrefs, paged = mode == ComicMode.PAGE,
                    turnStyle = turnStyle, onTurnStyle = { turnStyle = it },
                    turnSpeed = turnSpeed, onTurnSpeed = { turnSpeed = it },
                )
                com.oasisreader.app.ui.ReadPanel.NAV -> ComicNavPanel(
                    bookmarkCount = bookmarks.size, finished = finished, canOrigin = com.oasisreader.app.reader.canOpenOrigin(book),
                    onMarks = { marksOpen = true },
                    onRestart = { panel = com.oasisreader.app.ui.ReadPanel.NONE; curPage = 0; curFrac = 0f; viewKey++ },
                    onFinished = {
                        finished = !finished
                        val at = if (finished) System.currentTimeMillis() else null
                        scope.launch { db.books().setFinished(book.id, at) }
                    },
                    onNext = {
                        panel = com.oasisreader.app.ui.ReadPanel.NONE
                        scope.launch { com.oasisreader.app.reader.findAndOpenNext(context, book, nextVol, onOpenBook) }
                    },
                    onFolder = {
                        panel = com.oasisreader.app.ui.ReadPanel.NONE
                        onOpenFolder(book)
                    },
                )
                else -> {}
            }
        }

        ComicTopBar(
            barsVisible = barsVisible, panel = panel, aaMenu = aaMenu, readingChrome = readingChrome, cut = cut,
            barColor = barColor, chrome = chrome, title = book.title,
            onBack = onBack, onAaMenu = { aaMenu = it }, onPanel = { panel = it },
            panelBody = comicPanelBody,
        )

        // 98: Aa's panels in a card at the bottom, no dimming; tabs move between the three
        if (panel in COMIC_AA_PANELS) lastAa[0] = panel
        ComicAaCard(
            visible = panel in COMIC_AA_PANELS, which = lastAa[0], barColor = barColor, chrome = chrome,
            modifier = Modifier.align(Alignment.BottomCenter),
            onPanel = { panel = it },
            onClose = { panel = com.oasisreader.app.ui.ReadPanel.NONE; barsVisible = true; barsShownAt = System.currentTimeMillis() },
            panelBody = comicPanelBody,
        )

        // 98: the top-right corner puts a bookmark in / takes it out, as in the book screen (91)
        if (!barsVisible && panel == com.oasisreader.app.ui.ReadPanel.NONE) Box(
            Modifier.align(Alignment.TopEnd).let { if (readingChrome.hideStatus) it else it.statusBarsPadding() }
                .size(56.dp)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { toggleBookmark() },
        )

        if (prevAsk) androidx.compose.material3.AlertDialog(
            onDismissRequest = { prevAsk = false },
            title = { Text("이전 권으로 갈까요?") },
            text = {
                val p = prevVol
                Text(
                    when {
                        p != null -> p.title
                        prevSearching -> "이전 권을 찾는 중…"
                        else -> "이전 권을 찾지 못했습니다."
                    }
                )
            },
            confirmButton = {
                val p = prevVol
                TextButton(
                    enabled = p != null,
                    onClick = { prevAsk = false; if (p != null) com.oasisreader.app.reader.openNextVolume(context, p, onOpenBook) },
                ) { Text("열기") }
            },
            dismissButton = { TextButton(onClick = { prevAsk = false }) { Text("취소") } },
        )

        // the last page: the next volume, one tap away
        // 쪽 넘기기: a card on the last page. 웹툰 스크롤: the button in the "— 끝 —" tail (the card came
        // up as soon as the last strip peeked in, and went away again once the tail was on screen)
        val atEnd = pageCount > 0 && lastPageShown >= pageCount - 1
        val lookUp = if (mode == ComicMode.SCROLL) tailSeen else atEnd
        LaunchedEffect(lookUp) {
            if (lookUp && !nextAsked) {
                nextAsked = true; nextSearching = true
                nextVol = runCatching { com.oasisreader.app.data.NextVolumes.find(app, book) }.getOrNull()
                nextSearching = false
            }
        }
        nextVol?.let { n ->
            if (mode == ComicMode.PAGE && atEnd && !nextClosed) com.oasisreader.app.reader.NextVolumeCard(
                n, onOpen = { com.oasisreader.app.reader.openNextVolume(context, n, onOpenBook) }, onDismiss = { nextClosed = true },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .let { if (readingChrome.hideNav) it else it.navigationBarsPadding() }
                    .padding(start = 16.dp, end = 16.dp, bottom = if (barsVisible) 96.dp else 40.dp),
            )
        }

        // the bottom bar with the top bar: title · page, −/+ (one page) and a slider to jump
        AnimatedVisibility(
            visible = barsVisible && panel == com.oasisreader.app.ui.ReadPanel.NONE && pageCount > 1,
            enter = com.oasisreader.app.ui.theme.calm(fadeIn()), exit = com.oasisreader.app.ui.theme.calm(fadeOut()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ComicSeekBar(
                title = book.title, page = lastPageShown.coerceIn(0, pageCount - 1), count = pageCount, rtl = rtl,
                barColor = barColor, chrome = chrome, navPad = !readingChrome.hideNav,
                onTouch = { barsShownAt = System.currentTimeMillis() },
            ) { p -> curPage = p; curFrac = 0f; lastPageShown = p; viewKey++ }
        }

        if (label.isNotEmpty() && readingChrome.pageNum && !barsVisible) {
            Text(
                "$label  ·  ${(progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .let { if (readingChrome.hideNav) it else it.navigationBarsPadding() }
                    .padding(bottom = if (pageBand) 3.dp else 8.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}
