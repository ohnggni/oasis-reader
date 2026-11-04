package com.oasisreader.app.comic

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import com.oasisreader.app.ui.theme.isAppDark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.ReadingTimeEntity
import com.oasisreader.app.ui.theme.BarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

/** A horizontal strip of one page (webtoon pages can be 20 000 px tall: never decode them whole). */
private data class Slice(val page: Int, val path: String, val w: Int, val top: Int, val bottom: Int) {
    val h get() = bottom - top
}

private const val SLICE_SRC = 1600   // source pixels per strip

private fun slicesOf(pkg: ComicPackage): List<Slice> = pkg.pages.flatMapIndexed { i, p ->
    val n = (p.h + SLICE_SRC - 1) / SLICE_SRC
    (0 until n).map { k -> Slice(i, p.path, p.w, k * SLICE_SRC, minOf(p.h, (k + 1) * SLICE_SRC)) }
}

private object StripCache {
    val mem = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

/** A PDF "page" path points into book.pdf next to the content folder. */
private fun pdfOf(file: File) = File(file.parentFile!!.parentFile, PdfPages.FILE)

/** Decode one strip at roughly [targetW] pixels wide. */
private fun decodeStrip(file: File, s: Slice, targetW: Int): Bitmap? {
    if (PdfPages.isPdfPage(s.path)) return PdfPages.render(pdfOf(file), PdfPages.indexOf(s.path), targetW, s.top, s.bottom)
    var sample = 1
    while (s.w / (sample * 2) >= targetW) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return try {
        val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.path)
                      else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.path, false)
        try { decoder.decodeRegion(Rect(0, s.top, s.w, s.bottom), opts) } finally { decoder.recycle() }
    } catch (e: Exception) {
        // formats the region decoder cannot read (gif, …): decode whole, then cut
        val full = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val top = s.top / sample
        val h = (s.h / sample).coerceAtMost(full.height - top).coerceAtLeast(1)
        Bitmap.createBitmap(full, 0, top.coerceIn(0, full.height - 1), full.width, h)
    }
}

/** Vertical, gap-free webtoon viewer for CBZ/ZIP books. */
@Composable
fun ComicScreen(bookId: String, onBack: () -> Unit) {
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
                Text("📦", fontSize = 40.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (b?.sourceUrl != null) "이 웹툰 파일이 이 폰에 없습니다.\n서버에서 다시 받을 수 있습니다."
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
                            val tmp = File(app.cacheDir, "refetch_${System.nanoTime()}.bin")
                            try {
                                val server = com.oasisreader.app.data.ServerSettings(app).forUrl(url) ?: com.oasisreader.app.data.Server("", "", "", "", "")
                                com.oasisreader.app.opds.OpdsClient(server).download(url, tmp) { _, _ -> }
                                store.rebuild(bookId, tmp)
                                attempt++
                            } catch (e: Exception) {
                                Toast.makeText(context, "다시 받기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                tmp.delete(); refetching = false
                            }
                        }
                    }
                    // a "바로 읽기" book whose file was dropped from the cache: fetch it without asking
                    LaunchedEffect(Unit) { if (b?.pinned == false && !refetching) fetch() }
                    if (refetching) CircularProgressIndicator()
                    else Button(onClick = { fetch() }) { Text("⬇️ 다시 받기") }
                }
                TextButton(onClick = onBack) { Text("뒤로") }
            }
            b == null || p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> ComicViewer(b, p, store, db, onBack)
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
private fun groupsOf(pkg: ComicPackage, spread: Boolean, coverAlone: Boolean): List<List<Int>> {
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

/** Pages much taller than wide are a webtoon strip: start those books in scroll mode. */
private fun guessMode(pkg: ComicPackage): ComicMode {
    val ratios = pkg.pages.map { it.h.toFloat() / it.w.coerceAtLeast(1) }.sorted()
    val median = ratios.getOrElse(ratios.size / 2) { 1.4f }
    return if (median > 2.5f) ComicMode.SCROLL else ComicMode.PAGE
}

@Composable
private fun ComicViewer(book: BookEntity, pkg: ComicPackage, store: BookStore, db: AppDatabase, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val prefs = remember { app.getSharedPreferences("comic", android.content.Context.MODE_PRIVATE) }
    val readerPrefs = remember { com.oasisreader.app.reader.ReaderPrefs(app, book.id) }
    val scope = rememberCoroutineScope()
    val content = remember(book.id) { store.contentDir(book.id) }
    val pageCount = pkg.pages.size
    val isPdf = remember(pkg) { pkg.pages.firstOrNull()?.path?.let { PdfPages.isPdfPage(it) } == true }

    var mode by remember {
        mutableStateOf(runCatching { ComicMode.valueOf(prefs.getString("mode_${book.id}", null) ?: "") }.getOrDefault(guessMode(pkg)))
    }
    var rtl by remember { mutableStateOf(prefs.getBoolean("rtl", false)) }
    var coverAlone by remember { mutableStateOf(prefs.getBoolean("cover_alone", true)) }
    var narrow by remember { mutableStateOf(prefs.getBoolean("narrow", false)) }
    var keepOn by remember { mutableStateOf(readerPrefs.keepScreenOn) }
    // same brightness setting as the book reader; only while this screen is open
    var brightness by remember { androidx.compose.runtime.mutableFloatStateOf(readerPrefs.brightness) }
    // top-bar panels, the same four as the book reader: 화면 · 보기 · 넘김 · 이동
    var panel by remember { mutableStateOf(com.oasisreader.app.ui.ReadPanel.NONE) }
    var autoHide by remember { mutableStateOf(readerPrefs.autoHideBars) }
    var turnStyle by remember { mutableStateOf(readerPrefs.turnStyle) }
    var turnShot by remember { mutableStateOf<com.oasisreader.app.ui.TurnShot?>(null) }
    var turnPicker by remember { mutableStateOf(false) }
    var turnSpeed by remember { mutableStateOf(readerPrefs.turnSpeed) }
    var finished by remember { mutableStateOf(book.finishedAt != null) }

    // position, shared by both modes: page index + fraction inside that page (scroll mode)
    var curPage by remember { mutableIntStateOf(book.spineIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) }
    var curFrac by remember { mutableStateOf(book.chapterFraction.coerceIn(0f, 1f)) }
    var lastPageShown by remember { mutableIntStateOf(curPage) }   // last page on screen (spreads)
    var viewKey by remember { mutableIntStateOf(0) }                // bump to rebuild the view at curPage

    var barsVisible by remember { mutableStateOf(true) }
    var barsShownAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var menuOpen by remember { mutableStateOf(false) }
    var lastActive by remember { mutableLongStateOf(System.currentTimeMillis()) }

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
    LaunchedEffect(book.id) {
        var pending = 0L
        suspend fun flush() {
            if (pending <= 0) return
            val add = pending; pending = 0
            val day = LocalDate.now().toString()
            val cur = db.readingTime().get(day, book.id)?.seconds ?: 0L
            db.readingTime().upsert(ReadingTimeEntity(day, book.id, cur + add))
        }
        try {
            while (true) {
                delay(5_000)
                val front = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (front && System.currentTimeMillis() - lastActive < 180_000) pending += 5
                if (pending >= 30) flush()
            }
        } finally {
            withContext(NonCancellable) { flush() }
        }
    }

    // keep the screen on while reading (released after 10 minutes idle)
    com.oasisreader.app.ui.NoEdgeBackGesture(active = !barsVisible)
    val window = remember(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is Activity) c = c.baseContext
        (c as? Activity)?.window
    }
    LaunchedEffect(keepOn, window) {
        val w = window ?: return@LaunchedEffect
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (!keepOn) { w.clearFlags(flag); return@LaunchedEffect }
        try {
            while (true) {
                if (System.currentTimeMillis() - lastActive < 600_000) w.addFlags(flag) else w.clearFlags(flag)
                delay(15_000)
            }
        } finally { w.clearFlags(flag) }
    }
    LaunchedEffect(brightness, window) {
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }

    // the bar hides itself
    LaunchedEffect(barsVisible, barsShownAt, panel, autoHide) {
        if (!barsVisible) { panel = com.oasisreader.app.ui.ReadPanel.NONE; return@LaunchedEffect }
        if (panel != com.oasisreader.app.ui.ReadPanel.NONE || !autoHide) return@LaunchedEffect
        delay(2_000)
        barsVisible = false
    }
    fun toggleBars() { barsVisible = !barsVisible; if (barsVisible) barsShownAt = System.currentTimeMillis() }

    val dark = isAppDark()
    val barColor = if (dark) BarColors.Dark else BarColors.Light
    val chrome = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    var label by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val wPx = with(density) { maxWidth.roundToPx() }
            val hPx = with(density) { maxHeight.roundToPx() }
            // landscape (or the opened fold held sideways): two pages side by side, no gap
            val spread = maxWidth > maxHeight * 1.05f
            if (mode == ComicMode.PAGE) {
                val groups = remember(pkg, spread, coverAlone) { groupsOf(pkg, spread, coverAlone) }
                key(groups, viewKey) {
                    val startGroup = remember { groups.indexOfFirst { curPage in it }.coerceAtLeast(0) }
                    val pager = rememberPagerState(initialPage = startGroup) { groups.size }
                    LaunchedEffect(pager) {
                        snapshotFlow { pager.currentPage }.collect { g ->
                            val grp = groups.getOrNull(g) ?: return@collect
                            curPage = grp.first(); curFrac = 0f; lastPageShown = grp.last()
                            label = grp.joinToString("-") { (it + 1).toString() } + " / $pageCount"
                        }
                    }
                    val slotW = if (spread) wPx / 2 else wPx
                    // zoom of the page on screen; turning pages is locked while zoomed in
                    var zoom by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
                    var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero } }
                    val viewW = wPx.toFloat()
                    val viewH = hPx.toFloat()
                    fun clampPan(o: androidx.compose.ui.geometry.Offset, z: Float): androidx.compose.ui.geometry.Offset {
                        val mx = viewW * (z - 1) / 2
                        val my = viewH * (z - 1) / 2
                        return androidx.compose.ui.geometry.Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
                    }
                    // what the pager last drew: the picture a curl turn rolls away
                    val pagerLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
                    val stack = turnStyle == com.oasisreader.app.ui.TurnStyle.COVER || turnStyle == com.oasisreader.app.ui.TurnStyle.CURL
                    // 종이 말림: a horizontal drag lifts the page under the finger (the pager does not slide)
                    val curlDrag = turnStyle == com.oasisreader.app.ui.TurnStyle.CURL && com.oasisreader.app.ui.TurnStyle.curlSupported
                    var dragShot by remember { mutableStateOf<com.oasisreader.app.ui.TurnShot?>(null) }
                    var dragFrom by remember { mutableIntStateOf(0) }
                    fun turnTo(target: Int, tapY: Float = 0.5f) {
                        val t = target.coerceIn(0, groups.size - 1)
                        if (t == pager.currentPage) return
                        val next = t > pager.currentPage
                        scope.launch {
                            when {
                                turnStyle == com.oasisreader.app.ui.TurnStyle.NONE -> pager.scrollToPage(t)
                                turnStyle == com.oasisreader.app.ui.TurnStyle.CURL && com.oasisreader.app.ui.TurnStyle.curlSupported -> {
                                    val img = runCatching { pagerLayer.toImageBitmap() }.getOrNull()
                                    if (img == null) { pager.animateScrollToPage(t); return@launch }
                                    // right-to-left books turn the other way on screen
                                    turnShot = com.oasisreader.app.ui.TurnShot(img, forward = if (rtl) !next else next, style = turnStyle, tapY = tapY, speed = turnSpeed)
                                    androidx.compose.runtime.withFrameNanos { }
                                    pager.scrollToPage(t)
                                }
                                else -> pager.animateScrollToPage(t)
                            }
                        }
                    }
                    // page-turner remotes / keyboards; ←/→ follow the screen in a right-to-left book
                    com.oasisreader.app.ui.PageTurnKeys { dir, horizontal ->
                        if (zoom > 1.01f) return@PageTurnKeys
                        // with a settings panel open, the first press only closes it
                        if (panel != com.oasisreader.app.ui.ReadPanel.NONE) { panel = com.oasisreader.app.ui.ReadPanel.NONE; return@PageTurnKeys }
                        lastActive = System.currentTimeMillis()
                        val d = if (horizontal && rtl) -dir else dir
                        turnTo(pager.currentPage + d)
                    }
                    HorizontalPager(
                        state = pager,
                        reverseLayout = rtl,
                        beyondViewportPageCount = if (isPdf) 2 else 1,
                        userScrollEnabled = zoom <= 1.01f && !curlDrag,
                        key = { groups[it].first() },
                        modifier = Modifier.fillMaxSize()
                            .then(if (!curlDrag) Modifier else Modifier.pointerInput(groups, rtl) {
                                var total = 0f
                                var started = false
                                var pendingFinger: androidx.compose.ui.geometry.Offset? = null
                                var pendingRelease: Boolean? = null
                                detectHorizontalDragGestures(
                                    onDragStart = { total = 0f; started = false; pendingFinger = null; pendingRelease = null },
                                    onHorizontalDrag = { change, dx ->
                                        if (zoom > 1.01f) return@detectHorizontalDragGestures
                                        total += dx
                                        pendingFinger = change.position
                                        dragShot?.finger = change.position
                                        if (!started && kotlin.math.abs(total) > 14f) {
                                            started = true
                                            val leftward = total < 0f   // finger moving left: the page goes left
                                            val next = if (rtl) !leftward else leftward
                                            val target = pager.currentPage + if (next) 1 else -1
                                            if (target in groups.indices && turnShot == null) {
                                                val startY = (change.position.y / size.height).coerceIn(0f, 1f)
                                                scope.launch {
                                                    val img = runCatching { pagerLayer.toImageBitmap() }.getOrNull() ?: return@launch
                                                    val shot = com.oasisreader.app.ui.TurnShot(
                                                        img, forward = leftward, style = turnStyle, tapY = startY, speed = turnSpeed, interactive = true,
                                                    )
                                                    shot.finger = pendingFinger
                                                    dragFrom = pager.currentPage
                                                    turnShot = shot
                                                    androidx.compose.runtime.withFrameNanos { }
                                                    pager.scrollToPage(target)
                                                    val early = pendingRelease
                                                    if (early != null) shot.release = early else dragShot = shot
                                                }
                                            }
                                        }
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        val done = kotlin.math.abs(total) > size.width * 0.25f
                                        val s = dragShot
                                        if (s != null) s.release = done else pendingRelease = done
                                        dragShot = null
                                    },
                                    onDragCancel = {
                                        val s = dragShot
                                        if (s != null) s.release = false else pendingRelease = false
                                        dragShot = null
                                    },
                                )
                            })
                            .drawWithContent {
                                pagerLayer.record { this@drawWithContent.drawContent() }
                                drawLayer(pagerLayer)
                            }
                            // pinch (two fingers) zooms; one finger pans while zoomed. Runs before the pager sees the drag.
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                        val fingers = ev.changes.count { it.pressed }
                                        if (fingers >= 2 || zoom > 1.01f) {
                                            val nz = (zoom * ev.calculateZoom()).coerceIn(1f, 5f)
                                            pan = clampPan(pan + ev.calculatePan(), nz)
                                            zoom = nz
                                            if (zoom <= 1.01f) { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero }
                                            if (fingers >= 2 || zoom > 1.01f) ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    } while (ev.changes.any { it.pressed })
                                }
                            }
                            .pointerInput(rtl, groups) {
                            detectTapGestures(onDoubleTap = { o ->
                                lastActive = System.currentTimeMillis()
                                if (zoom > 1.01f) { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero }
                                else {
                                    val z = 2.5f
                                    zoom = z
                                    pan = clampPan((androidx.compose.ui.geometry.Offset(viewW / 2, viewH / 2) - o) * (z - 1), z)
                                }
                            }, onTap = { o ->
                                if (zoom > 1.01f) return@detectTapGestures
                                lastActive = System.currentTimeMillis()
                                val x = o.x / size.width
                                val forward = if (rtl) x < 0.3f else x > 0.7f
                                val backward = if (rtl) x > 0.7f else x < 0.3f
                                when {
                                    forward -> turnTo(pager.currentPage + 1, o.y / size.height)
                                    backward -> turnTo(pager.currentPage - 1, o.y / size.height)
                                    else -> toggleBars()
                                }
                            })
                        },
                    ) { g ->
                        val grp = groups[g]
                        // order on screen: left-to-right, or right-to-left for Japanese manga
                        val onScreen = if (rtl) grp.reversed() else grp
                        val isCurrent = g == pager.currentPage
                        val paperBg = MaterialTheme.colorScheme.background
                        Row(
                            Modifier.fillMaxSize()
                                // 덮기: the later page stays put underneath, the earlier one slides over it
                                .zIndex(if (stack) -g.toFloat() else 0f)
                                .graphicsLayer {
                                    if (stack) {
                                        val o = (pager.currentPage - g) + pager.currentPageOffsetFraction
                                        if (o < 0f) translationX = (if (rtl) -1f else 1f) * o * size.width
                                        else if (o > 0.001f) shadowElevation = 18f
                                    }
                                }
                                .then(if (stack) Modifier.background(paperBg) else Modifier)
                                .graphicsLayer {
                                if (isCurrent) {
                                    scaleX = zoom; scaleY = zoom
                                    translationX = pan.x; translationY = pan.y
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            onScreen.forEachIndexed { i, pageIdx ->
                                val align = when {
                                    onScreen.size == 1 -> Alignment.Center
                                    i == 0 -> Alignment.CenterEnd      // left page hugs the gutter
                                    else -> Alignment.CenterStart      // right page hugs the gutter
                                }
                                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = align) {
                                    PageImage(
                                        File(content, pkg.pages[pageIdx].path), pkg.pages[pageIdx], slotW, hPx, align,
                                        sharp = isCurrent && zoom > 1.3f,
                                        // pages off screen are drawn only once the swipe has settled
                                        waitIdle = if (isCurrent) null else suspend { snapshotFlow { pager.isScrollInProgress }.first { !it }; Unit },
                                    )
                                }
                            }
                        }
                    }
                    if (turnPicker) com.oasisreader.app.ui.TurnStyleDialog(
                        current = turnStyle, speed = turnSpeed,
                        onPick = { st -> turnStyle = st; readerPrefs.turnStyle = st },
                        onSpeed = { sp -> turnSpeed = sp; readerPrefs.turnSpeed = sp },
                        onDismiss = { turnPicker = false },
                    )
                    com.oasisreader.app.ui.TurnOverlay(
                        shot = turnShot, paper = MaterialTheme.colorScheme.background,
                        onDone = { done, ok ->
                            if (ok) { if (turnShot === done) turnShot = null }
                            else scope.launch {
                                // let go early: the old page lies flat on top again; put it back underneath
                                pager.scrollToPage(dragFrom)
                                androidx.compose.runtime.withFrameNanos { }
                                androidx.compose.runtime.withFrameNanos { }
                                // fade the picture out rather than letting it vanish in one frame
                                overlayAlpha.animateTo(0f, androidx.compose.animation.core.tween(140))
                                if (turnShot === done) turnShot = null
                                overlayAlpha.snapTo(1f)
                            }
                        },
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = overlayAlpha.value },
                    )
                }
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
                                lastPageShown = if (!listState.canScrollForward) pageCount - 1
                                                else slices.getOrNull(li ?: fi)?.page ?: s0.page
                                label = "${s0.page + 1} / $pageCount"
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
                                TextButton(onClick = onBack) { Text("서재로") }
                            }
                        }
                    }
                }
            }
        }

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
        if (barsVisible) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(barColor))
        }
        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.statusBarsPadding(),
        ) {
            Surface(color = barColor, contentColor = chrome) {
                Column {
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { toggleBookmark() }, modifier = Modifier.size(40.dp)) {
                            com.oasisreader.app.reader.BookmarkGlyph(filled = hereMark != null, color = if (hereMark != null) MaterialTheme.colorScheme.primary else chrome, width = 13.dp)
                        }
                        // [ ☀️ 화면 | ▣ 보기 | ⇆ 넘김 | ☰ 이동 ]
                        Box(Modifier.padding(end = 10.dp)) {
                            com.oasisreader.app.reader.PillGroup(MaterialTheme.colorScheme.background) {
                                com.oasisreader.app.reader.PillSegment(selected = panel == com.oasisreader.app.ui.ReadPanel.SCREEN, onClick = { panel = panel.toggle(com.oasisreader.app.ui.ReadPanel.SCREEN) }) { Text("☀️", fontSize = 14.sp) }
                                com.oasisreader.app.reader.PillDivider()
                                com.oasisreader.app.reader.PillSegment(selected = panel == com.oasisreader.app.ui.ReadPanel.TEXT, onClick = { panel = panel.toggle(com.oasisreader.app.ui.ReadPanel.TEXT) }) { Text("▣", fontSize = 16.sp) }
                                com.oasisreader.app.reader.PillDivider()
                                com.oasisreader.app.reader.PillSegment(selected = panel == com.oasisreader.app.ui.ReadPanel.PAGE, onClick = { panel = panel.toggle(com.oasisreader.app.ui.ReadPanel.PAGE) }) { Text("⇆", fontSize = 16.sp) }
                                com.oasisreader.app.reader.PillDivider()
                                com.oasisreader.app.reader.PillSegment(selected = panel == com.oasisreader.app.ui.ReadPanel.NAV, onClick = { panel = panel.toggle(com.oasisreader.app.ui.ReadPanel.NAV) }) { Text("☰", fontSize = 15.sp) }
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    AnimatedVisibility(visible = panel != com.oasisreader.app.ui.ReadPanel.NONE) {
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.42f).dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            when (panel) {
                                com.oasisreader.app.ui.ReadPanel.SCREEN -> {
                                    com.oasisreader.app.reader.BrightnessPanel(brightness) { brightness = it; readerPrefs.brightness = it }
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        com.oasisreader.app.ui.PanelSwitch("화면 켜짐 유지", null, keepOn, { keepOn = it; readerPrefs.keepScreenOn = it }, Modifier.weight(1f))
                                        com.oasisreader.app.ui.PanelSwitch("바 자동 숨김", null, autoHide, {
                                            autoHide = it; readerPrefs.autoHideBars = it; barsShownAt = System.currentTimeMillis()
                                        }, Modifier.weight(1f))
                                    }
                                }
                                com.oasisreader.app.ui.ReadPanel.TEXT -> {
                                    com.oasisreader.app.ui.PanelChoice("보기", listOf("책처럼 넘기기", "세로 스크롤 (웹툰)"), if (mode == ComicMode.PAGE) 0 else 1) { i ->
                                        mode = if (i == 0) ComicMode.PAGE else ComicMode.SCROLL
                                        prefs.edit().putString("mode_${book.id}", mode.name).apply()
                                    }
                                    if (mode == ComicMode.PAGE) {
                                        com.oasisreader.app.ui.PanelChoice("방향", listOf("왼쪽 → 오른쪽", "오른쪽 → 왼쪽 (일본 만화)"), if (rtl) 1 else 0) { i ->
                                            rtl = i == 1; prefs.edit().putBoolean("rtl", rtl).apply()
                                        }
                                        com.oasisreader.app.ui.PanelChoice("맞쪽", listOf("첫 장은 혼자", "첫 장도 짝 지어"), if (coverAlone) 0 else 1) { i ->
                                            coverAlone = i == 0; prefs.edit().putBoolean("cover_alone", coverAlone).apply()
                                        }
                                    } else {
                                        com.oasisreader.app.ui.PanelChoice("폭", listOf("화면 너비에 맞추기", "가운데 좁게"), if (narrow) 1 else 0) { i ->
                                            narrow = i == 1; prefs.edit().putBoolean("narrow", narrow).apply()
                                        }
                                    }
                                }
                                com.oasisreader.app.ui.ReadPanel.PAGE -> {
                                    if (mode == ComicMode.PAGE) com.oasisreader.app.ui.TurnStylePicker(
                                        current = turnStyle, speed = turnSpeed,
                                        onPick = { st -> turnStyle = st; readerPrefs.turnStyle = st },
                                        onSpeed = { sp -> turnSpeed = sp; readerPrefs.turnSpeed = sp },
                                    ) else Text(
                                        "세로 스크롤에서는 넘김 효과가 없습니다.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 8.dp),
                                    )
                                }
                                com.oasisreader.app.ui.ReadPanel.NAV -> {
                                    com.oasisreader.app.ui.PanelAction("🔖", "책갈피 (${bookmarks.size})") { marksOpen = true }
                                    com.oasisreader.app.ui.PanelAction("⏮️", "처음부터") { panel = com.oasisreader.app.ui.ReadPanel.NONE; curPage = 0; curFrac = 0f; viewKey++ }
                                    com.oasisreader.app.ui.PanelAction(if (finished) "↩️" else "✅", if (finished) "다 읽음 취소" else "다 읽음으로 표시") {
                                        finished = !finished
                                        val at = if (finished) System.currentTimeMillis() else null
                                        scope.launch { db.books().setFinished(book.id, at) }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        if (label.isNotEmpty()) {
            Text(
                "$label  ·  ${(progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * A finished bitmap as a GPU (hardware) bitmap: uploaded here, on the worker thread, instead of
 * on the render thread the first time the page slides into view.
 */
private fun toGpu(b: Bitmap): Bitmap =
    runCatching { b.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()?.also { if (it !== b) b.recycle() } ?: b

/**
 * One whole page, decoded to fit [boxW] x [boxH] pixels and shown with ContentScale.Fit.
 * [sharp]: the page is zoomed in — draw it again at higher resolution (the plain one stays until then).
 * [waitIdle]: wait with the work until page turning has stopped (pages prepared off screen).
 */
@Composable
private fun PageImage(
    file: File, page: ComicPage, boxW: Int, boxH: Int, align: Alignment,
    sharp: Boolean = false, waitIdle: (suspend () -> Unit)? = null,
) {
    val base = "${file.path}#page#$boxW#$boxH"
    val key = if (sharp) "$base#sharp" else base
    val bmp by produceState(StripCache.mem.get(key) ?: StripCache.mem.get(base), key) {
        StripCache.mem.get(key)?.let { value = it; return@produceState }
        val pdf = PdfPages.isPdfPage(page.path)
        waitIdle?.invoke()
        val fresh = withContext(if (pdf) PdfPages.dispatcher else Dispatchers.IO) {
            runCatching {
                if (pdf) {
                    // 1x the size on screen; 2.5x when zoomed in
                    val fit = minOf(boxW.toFloat() / page.w, boxH.toFloat() / page.h) * (if (sharp) 2.5f else 1f)
                    PdfPages.render(pdfOf(file), PdfPages.indexOf(page.path), (page.w * fit).toInt())
                } else {
                    var sample = 1
                    if (!sharp) while (page.w / (sample * 2) >= boxW && page.h / (sample * 2) >= boxH) sample *= 2
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }?.let(::toGpu)
            }.getOrNull()
        }
        if (fresh != null) { StripCache.mem.put(key, fresh); value = fresh }
    }
    val b = bmp
    if (b == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
    } else {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            alignment = align,
        )
    }
}

@Composable
private fun Strip(file: File, s: Slice, targetPx: Int) {
    val key = "${file.path}#${s.top}#$targetPx"
    val bmp by produceState(StripCache.mem.get(key), key) {
        if (value != null) return@produceState
        value = withContext(if (PdfPages.isPdfPage(s.path)) PdfPages.dispatcher else Dispatchers.IO) {
            runCatching { decodeStrip(file, s, targetPx)?.let(::toGpu) }.getOrNull()
        }
            ?.also { StripCache.mem.put(key, it) }
    }
    val ratio = s.w.toFloat() / s.h.coerceAtLeast(1)
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).background(if (bmp == null) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

