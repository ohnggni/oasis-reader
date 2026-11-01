package com.oasisreader.app.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import com.oasisreader.app.ui.theme.isAppDark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewAssetLoader
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.FontManager
import com.oasisreader.app.data.FontOption
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.HighlightEntity
import com.oasisreader.app.data.db.ReadingTimeEntity
import com.oasisreader.app.epub.EpubPackage
import com.oasisreader.app.epub.TocItem
import com.oasisreader.app.ui.theme.BarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

private const val ASSET_HOST = BookStore.ASSET_HOST
private const val BAR_DP = 52
private const val RUNNING_HEAD_DP = 30
private const val SIDEWAYS_PAD_DP = 24
private const val SIDEWAYS_EXTRA_BOTTOM = 14   // a little more air above the page label
private val CompactPad = PaddingValues(horizontal = 8.dp)

/** Where to open: chapter + fraction, or an element id inside it. [token] forces a reload. */
private data class Target(
    val index: Int,
    val start: Float,
    val anchor: String? = null,
    val focus: String? = null,
    val token: Long = System.nanoTime(),
)

private sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val html: String, val baseUrl: String, val token: Long) : LoadState
    data class Failed(val message: String) : LoadState
}

/** The reader in its page colours: menus and panels take the theme's light or dark look. */
@Composable
fun ReaderScreen(bookId: String, focusHighlight: Pair<Int, String>? = null, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val prefs = remember(bookId) { ReaderPrefs(app, bookId) }
    var theme by remember(bookId) { mutableStateOf(prefs.theme) }
    val rTheme = theme.resolve(isAppDark())
    com.oasisreader.app.ui.theme.OasisTheme(darkTheme = rTheme.dark) {
        ReaderBody(bookId, focusHighlight, onBack, prefs, theme, rTheme) { t -> theme = t; prefs.theme = t }
    }
}

@Composable
private fun ReaderBody(
    bookId: String, focusHighlight: Pair<Int, String>?, onBack: () -> Unit,
    prefs: ReaderPrefs, theme: ReaderTheme, rTheme: ReaderTheme, onTheme: (ReaderTheme) -> Unit,
) {
    val context = LocalContext.current
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
    var curFraction by remember { mutableFloatStateOf(0f) }

    var barsVisible by remember { mutableStateOf(true) }
    var pageLabel by remember { mutableStateOf("") }
    var fontPx by remember { mutableIntStateOf(prefs.fontPx) }
    var lineHeight by remember { mutableIntStateOf(prefs.lineHeight) }
    var mode by remember { mutableStateOf(prefs.mode) }
    var menuOpen by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var hlListOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var viewerFile by remember { mutableStateOf<File?>(null) }
    var turnStyle by remember { mutableStateOf(prefs.turnStyle) }
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
    var fontId by remember { mutableStateOf(prefs.fontId) }
    var fontBusy by remember { mutableStateOf<String?>(null) }
    var fontProgress by remember { mutableFloatStateOf(0f) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var panel by remember { mutableStateOf(Panel.NONE) }
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
    val spread = rememberSpread()
    // Fold opened and turned sideways (hinge horizontal): the inner screen's punch hole
    // sits on a side edge. Samsung does not report it as a display cutout, so keep the
    // text clear of it with a fixed extra margin on both sides (only in this posture).
    val sideways = rememberSideways()
    val sidePad = if (sideways) SIDEWAYS_PAD_DP.dp else 0.dp
    val padTop = (BAR_DP + 10 + mTop).coerceAtLeast(16)
    val padBottom = ((if (sideways) 34 + SIDEWAYS_EXTRA_BOTTOM else 34) + mBottom).coerceAtLeast(12)
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
                    turn = turnStyle.id,
                    fontFamily = font.cssStack(),
                    highlights = hls,
                    focus = t.focus,
                    animate = turnStyle != com.oasisreader.app.ui.TurnStyle.NONE,
                    spread = spread,
                    weight = weight,
                    colors = ReaderHtml.COLORS,
                    docUrl = doc.url,
                    head = chapterTitleFor(p, t.index).ifBlank { book?.title.orEmpty() },
                    hasPrev = t.index > 0,
                    hasNext = t.index < p.spine.size - 1,
                ),
                fontCssHref = fontHref(font),
                publisherStyle = publisherStyle,
                theme = rTheme,
            )
            curIndex = t.index
            pageLabel = ""
            LoadState.Ready(html, doc.url, t.token)
        } catch (e: Exception) {
            LoadState.Failed("이 장을 여는 중 문제가 생겼습니다: ${e.message}")
        }
    }

    LaunchedEffect(barsVisible, barsShownAt, panel, menuOpen, tocOpen, hlListOpen, autoHide, state is LoadState.Ready) {
        if (!autoHide || !barsVisible || state !is LoadState.Ready) return@LaunchedEffect
        if (panel != Panel.NONE || menuOpen || tocOpen || hlListOpen) return@LaunchedEffect
        delay(2_000)
        barsVisible = false
    }

    LaunchedEffect(spread, webView) {
        webView?.evaluateJavascript("window.Reader && Reader.setSpread($spread)", null)
    }
    // insets can change (rotation, fold/unfold)
    LaunchedEffect(padTop, padBottom, padLeft, padRight, webView) {
        webView?.evaluateJavascript("window.Reader && Reader.setInsets($padTop, $padBottom, $padLeft, $padRight)", null)
    }

    fun reloadHere() { target = Target(curIndex, curFraction) }

    fun goChapter(index: Int, start: Float = 0f, anchor: String? = null) {
        val p = pkg ?: return
        if (index !in p.spine.indices) return
        target = Target(index, start, anchor)
    }

    fun savePosition(fraction: Float, end: Boolean) {
        val p = pkg ?: return
        curFraction = fraction
        val idx = curIndex
        val overall = p.overall(idx, fraction)
        scope.launch {
            db.books().savePosition(bookId, idx, fraction, overall, System.currentTimeMillis())
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
            if (idx >= 0) goChapter(idx, 0f, u.fragment)
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
                Toast.makeText(context, "글꼴 추가 실패: ${e.message}", Toast.LENGTH_LONG).show()
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
                Toast.makeText(context, "글꼴 받기 실패: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                fontBusy = null
            }
        }
    }

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
        if (panel != Panel.NONE) panel = Panel.NONE
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
    val chapterTitle = remember(p, curIndex) { p?.let { chapterTitleFor(it, curIndex) } ?: "" }
    val overall = p?.overall(curIndex, curFraction) ?: 0f
    val paper = Color(rTheme.bg)
    val chrome = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    // bars sit two shades off the page so they read as separate from the text
    val barColor = if (dark) BarColors.Dark else BarColors.Light
    val showBars = barsVisible || state !is LoadState.Ready

    Box(Modifier.fillMaxSize().background(paper)) {
        if (showBars) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(barColor))
        }
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
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
                            val tmp = File(app.cacheDir, "refetch_${System.nanoTime()}.epub")
                            try {
                                val server = com.oasisreader.app.data.ServerSettings(app).forUrl(url) ?: com.oasisreader.app.data.Server("", "", "", "", "")
                                com.oasisreader.app.opds.OpdsClient(server).download(url, tmp) { _, _ -> }
                                store.rebuild(bookId, tmp)
                                openAttempt++
                            } catch (e: Exception) {
                                Toast.makeText(context, "다시 받기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                tmp.delete()
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
                            val shot = com.oasisreader.app.ui.TurnShot(bmp.asImageBitmap(), forward, turnStyle, tapY, turnSpeed, interactive)
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
                            pageLabel = "$cur / $total"; lastActive = System.currentTimeMillis()
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
                                overlayAlpha.animateTo(0f, androidx.compose.animation.core.tween(140))
                                if (turnShot === done) turnShot = null
                                overlayAlpha.snapTo(1f)
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(horizontal = sidePad).graphicsLayer { alpha = overlayAlpha.value },
                    )
                }
            }

            // the running head (chapter title) is drawn inside the page, so it turns with it
            com.oasisreader.app.ui.NoEdgeBackGesture(active = !barsVisible)

            // compact overlay bar: it only ever covers the top margin, never text
            AnimatedVisibility(visible = showBars, enter = fadeIn(), exit = fadeOut()) {
                Surface(color = barColor, contentColor = chrome, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            Modifier.height(BAR_DP.dp).padding(end = 10.dp),
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
                            // [ ☀️ | 가 | ✒️ | ⋮ ]: panels under the bar + the overflow menu
                            Box {
                                PillGroup(paper) {
                                    PillSegment(selected = panel == Panel.BRIGHTNESS, onClick = { panel = panel.toggle(Panel.BRIGHTNESS) }) {
                                        Text("☀️", fontSize = 14.sp)
                                    }
                                    PillDivider()
                                    PillSegment(selected = panel == Panel.SIZE, onClick = { panel = panel.toggle(Panel.SIZE) }) {
                                        Text("가", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    PillDivider()
                                    PillSegment(selected = panel == Panel.FONT, onClick = { panel = panel.toggle(Panel.FONT) }) {
                                        Text("✒️", fontSize = 14.sp)
                                    }
                                    PillDivider()
                                    PillSegment(selected = menuOpen, onClick = { panel = Panel.NONE; menuOpen = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "메뉴", modifier = Modifier.size(18.dp))
                                    }
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        leadingIcon = { MenuEmoji("📑") },
                                        text = { Text("목차") },
                                        enabled = p != null,
                                        onClick = { menuOpen = false; tocOpen = true },
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { MenuEmoji("🖍️") },
                                        text = { Text("이 책의 하이라이트") },
                                        onClick = { menuOpen = false; hlListOpen = true },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        leadingIcon = { MenuEmoji(if (mode == ReaderMode.PAGE) "📜" else "📖") },
                                        text = { Text(if (mode == ReaderMode.PAGE) "스크롤로 보기" else "책처럼 넘겨 보기") },
                                        onClick = {
                                            menuOpen = false
                                            mode = if (mode == ReaderMode.PAGE) ReaderMode.SCROLL else ReaderMode.PAGE
                                            prefs.mode = mode
                                            pageLabel = ""   // the page reports again in the new mode
                                            webView?.evaluateJavascript(
                                                "Reader.setMode('${if (mode == ReaderMode.PAGE) "page" else "scroll"}')", null
                                            )
                                        },
                                    )
                                    if (mode == ReaderMode.PAGE) {
                                        DropdownMenuItem(
                                            leadingIcon = { MenuEmoji("🎞️") },
                                            text = { Text("넘김 효과 (${turnStyle.label})") },
                                            onClick = { menuOpen = false; turnPicker = true },
                                        )
                                    }
                                    DropdownMenuItem(
                                        leadingIcon = { MenuEmoji("🎨") },
                                        text = { Text(if (publisherStyle) "책 스타일 끄기" else "책 스타일 켜기") },
                                        onClick = {
                                            menuOpen = false
                                            publisherStyle = !publisherStyle
                                            prefs.publisherStyle = publisherStyle
                                            reloadHere()
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        leadingIcon = { MenuEmoji("⭐") },
                                        text = { Text("이 책 설정을 기본값으로") },
                                        onClick = {
                                            menuOpen = false
                                            prefs.saveAsDefaults()
                                            Toast.makeText(context, "이 책의 글꼴·크기·여백·배경·넘김 설정을 기본값으로 저장했습니다.", Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                    if (prefs.hasOwn) DropdownMenuItem(
                                        leadingIcon = { MenuEmoji("↩️") },
                                        text = { Text("이 책 설정 초기화") },
                                        onClick = {
                                            menuOpen = false
                                            prefs.resetBook()
                                            fontPx = prefs.fontPx; lineHeight = prefs.lineHeight; mode = prefs.mode
                                            weight = prefs.weight; fontId = prefs.fontId; publisherStyle = prefs.publisherStyle
                                            turnStyle = prefs.turnStyle; turnSpeed = prefs.turnSpeed
                                            mTop = prefs.marginTop; mBottom = prefs.marginBottom; mLeft = prefs.marginLeft; mRight = prefs.marginRight
                                            onTheme(prefs.theme)
                                            prefs.resetBook()   // onTheme wrote the theme back for this book
                                            reloadHere()
                                            Toast.makeText(context, "이 책은 다시 기본 설정을 따릅니다.", Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        leadingIcon = { MenuEmoji(if (keepOn) "🔆" else "💡") },
                                        text = { Text(if (keepOn) "화면 켜짐 유지 끄기" else "화면 켜짐 유지 켜기") },
                                        onClick = {
                                            menuOpen = false
                                            keepOn = !keepOn; prefs.keepScreenOn = keepOn
                                            Toast.makeText(
                                                context,
                                                if (keepOn) "읽는 동안 화면이 꺼지지 않습니다 (10분 동안 넘기지 않으면 해제)" else "화면 꺼짐은 시스템 설정을 따릅니다",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { MenuEmoji(if (autoHide) "📌" else "🫥") },
                                        text = { Text(if (autoHide) "상단바 고정" else "상단바 자동 숨기기") },
                                        onClick = {
                                            menuOpen = false
                                            autoHide = !autoHide; prefs.autoHideBars = autoHide
                                            barsShownAt = System.currentTimeMillis()
                                        },
                                    )
                                    HorizontalDivider()
                                    val finished = book?.finishedAt != null
                                    DropdownMenuItem(
                                        leadingIcon = { MenuEmoji(if (finished) "↩️" else "✅") },
                                        text = { Text(if (finished) "다 읽음 취소" else "다 읽음으로 표시") },
                                        onClick = {
                                            menuOpen = false
                                            val at = if (finished) null else System.currentTimeMillis()
                                            book = book?.copy(finishedAt = at)
                                            scope.launch { db.books().setFinished(bookId, at) }
                                        },
                                    )
                                }
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        AnimatedVisibility(
                            visible = panel != Panel.NONE,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    when (panel) {
                                        Panel.BRIGHTNESS -> {
                                            BrightnessPanel(
                                                value = brightness,
                                                onChange = { brightness = it; prefs.brightness = it },
                                            )
                                            ThemeRow(theme, onTheme)
                                        }
                                        Panel.SIZE -> SizePanel(
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
                                        else -> {}
                                    }
                                    if (panel == Panel.SIZE) {
                                        MarginPanel(
                                            top = mTop, bottom = mBottom, left = mLeft, right = mRight,
                                            onChange = { t, b, l, r ->
                                                mTop = t; mBottom = b; mLeft = l; mRight = r
                                                prefs.marginTop = t; prefs.marginBottom = b; prefs.marginLeft = l; prefs.marginRight = r
                                            },
                                        )
                                    }
                                    when (panel) {
                                        Panel.FONT -> FontPanel(
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
                                        else -> {}
                                    }
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }

            // page indicator — book mode: "3 / 34 · 12%", scroll mode: "12%"
            if (state is LoadState.Ready || turnShot != null) {
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

            // with the top bar: a bottom bar whose thin slider spans the whole book
            if (p != null) AnimatedVisibility(
                visible = showBars && state is LoadState.Ready,
                enter = fadeIn(), exit = fadeOut(),
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
        }
    }

    if (tocOpen && p != null) {
        TocDialog(
            pkg = p,
            currentIndex = curIndex,
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

/**
 * Where a server position points in this book. OG Reader positions are exact percentages;
 * a KOReader xpointer gives the chapter (/body/DocFragment[n]), placed inside it by percentage.
 */
internal fun remoteTarget(p: EpubPackage, r: com.oasisreader.app.data.KoSync.Remote): Pair<Int, Float>? {
    if (p.spine.isEmpty()) return null
    val pct = r.percentage
    if (r.fromOgReader && pct != null) return seekTarget(p, pct)
    val frag = Regex("""DocFragment\[(\d+)]""").find(r.progress.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
    if (frag != null && frag in p.spine.indices) {
        val within = if (pct == null) 0f
            else ((pct * p.totalChars - p.charsBefore(frag)) / p.spine[frag].chars.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
        return frag to within
    }
    return pct?.let { seekTarget(p, it) }
}

/** Whole-book fraction -> (spine index, fraction inside that chapter), by character counts. */
internal fun seekTarget(p: EpubPackage, v: Float): Pair<Int, Float> {
    if (p.spine.isEmpty()) return 0 to 0f
    val target = v.coerceIn(0f, 1f) * p.totalChars
    var before = 0L
    p.spine.forEachIndexed { i, s ->
        val n = s.chars.coerceAtLeast(1)
        if (target < before + n || i == p.spine.lastIndex) return i to ((target - before) / n).toFloat().coerceIn(0f, 1f)
        before += n
    }
    return p.spine.lastIndex to 1f
}

/** Bottom bar: chapter title and % of the place under the thumb, over a thin whole-book slider. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSeekBar(
    pkg: EpubPackage,
    overall: Float,
    barColor: Color,
    chrome: Color,
    onDrag: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    // keep showing where we jumped to until the book reports its new place
    LaunchedEffect(overall) { drag = null }
    val v = drag ?: overall
    val chapter = remember(pkg, v) { chapterTitleFor(pkg, seekTarget(pkg, v).first) }
    val accent = MaterialTheme.colorScheme.primary
    Surface(color = barColor, contentColor = chrome, modifier = Modifier.fillMaxWidth()) {
        // lifted well clear of the bottom edge: with gesture navigation a swipe up from there
        // means home / recent apps, and the slider must not catch it (it may cover a line of text)
        Column(Modifier.navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chapter.ifEmpty { " " },
                    style = MaterialTheme.typography.labelMedium,
                    color = chrome,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${(v * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = chrome)
            }
            Slider(
                value = v,
                onValueChange = { drag = it; onDrag() },
                onValueChangeFinished = { drag?.let(onSeek) },
                thumb = {
                    Box(Modifier.size(14.dp).background(accent, androidx.compose.foundation.shape.CircleShape))
                },
                track = { st ->
                    androidx.compose.material3.SliderDefaults.Track(
                        sliderState = st,
                        modifier = Modifier.height(3.dp),
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            activeTrackColor = accent,
                            inactiveTrackColor = chrome.copy(alpha = 0.25f),
                        ),
                        drawStopIndicator = null,
                        thumbTrackGapSize = 0.dp,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
        }
    }
}

/** Title of the toc entry that covers spine item [index] (the last one at or before it). */
private fun chapterTitleFor(p: EpubPackage, index: Int): String =
    com.oasisreader.app.data.HighlightExport.chapterTitle(p, index)

@Composable
private fun Failure(message: String, onRetry: (() -> Unit)?, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        if (onRetry != null) Button(onClick = onRetry) { Text("다시 시도") }
        TextButton(onClick = onBack) { Text("뒤로") }
    }
}

@Composable
private fun MissingFiles(canFetch: Boolean, auto: Boolean, busy: Boolean, onFetch: () -> Unit, onBack: () -> Unit) {
    // a "바로 읽기" book whose file was dropped from the cache: fetch it without asking
    LaunchedEffect(Unit) { if (auto && canFetch && !busy) onFetch() }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("📦", fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (canFetch) "이 책 파일이 이 폰에 없습니다.\n북오아시스에서 다시 받을 수 있습니다."
            else "이 책 파일이 이 폰에 없습니다.\n파일에서 다시 가져와 주세요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (canFetch) {
            if (busy) CircularProgressIndicator()
            else Button(onClick = onFetch) { Text("⬇️ 다시 받기") }
        }
        TextButton(onClick = onBack) { Text("뒤로") }
    }
}

@Composable
private fun BookHighlightsDialog(bookId: String, db: AppDatabase, onPick: (HighlightEntity) -> Unit, onDismiss: () -> Unit) {
    val list by remember(bookId) { db.highlights().observeFor(bookId) }.collectAsState(initial = null)
    var exporting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이 책의 하이라이트") },
        text = {
            val items = list
            when {
                items == null -> CircularProgressIndicator()
                items.isEmpty() -> Text("아직 없습니다. 글을 길게 눌러 고른 뒤 색을 누르면 생깁니다.", style = MaterialTheme.typography.bodyMedium)
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(items) { _, h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(h) }.padding(vertical = 8.dp),
                        ) {
                            Box(
                                Modifier.width(4.dp).height(40.dp)
                                    .background(runCatching { Color(android.graphics.Color.parseColor(h.color)) }.getOrDefault(Color.Yellow))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(h.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = {
            if (!list.isNullOrEmpty()) TextButton(onClick = { exporting = true }) { Text("내보내기") }
        },
    )
    if (exporting) com.oasisreader.app.ui.HighlightExportDialog(bookId) { exporting = false }
}

@Composable
private fun TocDialog(pkg: EpubPackage, currentIndex: Int, onPick: (TocItem) -> Unit, onDismiss: () -> Unit) {
    // fall back to the spine when the book has no toc
    val items = remember(pkg) {
        pkg.toc.ifEmpty { pkg.spine.mapIndexed { i, s -> TocItem("${i + 1}. " + s.path.substringAfterLast('/'), s.path) } }
    }
    val currentTitle = remember(pkg, currentIndex) { chapterTitleFor(pkg, currentIndex) }
    val startAt = remember(items) { items.indexOfFirst { it.title == currentTitle }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startAt - 3).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("목차") },
        text = {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(items) { i, t ->
                    val here = i == startAt && currentTitle.isNotEmpty()
                    Text(
                        t.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (here) FontWeight.Bold else FontWeight.Normal,
                        color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(t) }
                            .padding(start = (t.level * 16).dp, top = 10.dp, bottom = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderWebView(
    store: BookStore,
    fonts: FontManager,
    page: LoadState.Ready?,
    onCreated: (WebView) -> Unit,
    onPage: (Int, Int) -> Unit,
    onProgress: (Float, Boolean) -> Unit,
    onTapCenter: () -> Unit,
    onAddHighlight: (String, String, String, String) -> Unit,
    onTapHighlight: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onEdge: (Int, Float) -> Unit,
    onLink: (String) -> Unit,
    onTurn: (Boolean, Float) -> Unit,
    onDrag: (Int, Float, Float, Float) -> Unit,
    sidePad: Dp = 0.dp,
) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }

    val loader = remember {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/books/", WebViewAssetLoader.InternalStoragePathHandler(context, store.root))
            .addPathHandler("/fonts/", WebViewAssetLoader.InternalStoragePathHandler(context, fonts.root))
            .build()
    }

    // callbacks change every recomposition; the bridge reads the latest ones
    val callbacks = remember { Callbacks() }
    callbacks.onPage = onPage
    callbacks.onProgress = onProgress
    callbacks.onTap = onTapCenter
    callbacks.onAdd = onAddHighlight
    callbacks.onTapHl = onTapHighlight
    callbacks.onImage = onOpenImage
    callbacks.onEdge = onEdge
    callbacks.onLink = onLink
    callbacks.onTurn = onTurn
    callbacks.onDrag = onDrag
    val bridge = remember { JsBridge(main, callbacks) }

    AndroidView(
        modifier = Modifier.fillMaxSize().padding(horizontal = sidePad),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                settings.textZoom = 100
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(bridge, "Oasis")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // every navigation goes through the app (chapters are loaded by the reader)
                        main.post { callbacks.onLink(request.url.toString()) }
                        return true
                    }
                }
                onCreated(this)
            }
        },
        update = { wv ->
            if (page != null && wv.tag != page.token) {
                wv.tag = page.token
                wv.loadDataWithBaseURL(page.baseUrl, page.html, "text/html", "utf-8", null)
            }
        },
    )
}

private class Callbacks {
    var onPage: (Int, Int) -> Unit = { _, _ -> }
    var onProgress: (Float, Boolean) -> Unit = { _, _ -> }
    var onTap: () -> Unit = {}
    var onAdd: (String, String, String, String) -> Unit = { _, _, _, _ -> }
    var onTapHl: (String) -> Unit = {}
    var onImage: (String) -> Unit = {}
    var onEdge: (Int, Float) -> Unit = { _, _ -> }
    var onLink: (String) -> Unit = {}
    var onTurn: (Boolean, Float) -> Unit = { _, _ -> }
    var onDrag: (Int, Float, Float, Float) -> Unit = { _, _, _, _ -> }
}

/** Called from the page script (window.Oasis). Calls arrive on a background thread. */
private class JsBridge(private val main: Handler, private val cb: Callbacks) {
    @JavascriptInterface fun page(cur: Int, total: Int) { main.post { cb.onPage(cur, total) } }
    @JavascriptInterface fun progress(f: Float, end: Boolean) { main.post { cb.onProgress(f, end) } }
    @JavascriptInterface fun tap() { main.post { cb.onTap() } }
    @JavascriptInterface fun addHighlight(id: String, text: String, color: String, anchor: String) {
        main.post { cb.onAdd(id, text, color, anchor) }
    }
    @JavascriptInterface fun tapHighlight(id: String) { main.post { cb.onTapHl(id) } }
    @JavascriptInterface fun openImage(src: String) { main.post { cb.onImage(src) } }
    @JavascriptInterface fun edge(dir: Int, y: Float) { main.post { cb.onEdge(dir, if (y.isNaN()) 0.5f else y) } }
    @JavascriptInterface fun link(url: String) { main.post { cb.onLink(url) } }
    @JavascriptInterface fun turn(dir: Int, y: Float) { main.post { cb.onTurn(dir > 0, y) } }
    @JavascriptInterface fun dragStart(dir: Int, yFrac: Float, w: Float) { main.post { cb.onDrag(0, w, yFrac, dir.toFloat()) } }
    @JavascriptInterface fun dragMove(x: Float, y: Float) { main.post { cb.onDrag(1, x, y, 0f) } }
    @JavascriptInterface fun dragEnd(dx: Float, vx: Float) { main.post { cb.onDrag(2, dx, 0f, vx) } }
}

private fun android.content.Context.findActivity(): Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper && c !is Activity) c = c.baseContext
    return c as? Activity
}

/** Rounded "button" container for the reader bar: lighter than the bar, hairline border. */
@Composable
private fun PillGroup(fill: Color, content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = fill,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun PillSegment(
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxHeight()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp)
            .alpha(if (enabled) 1f else 0.35f),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PillDivider() {
    Box(Modifier.width(0.75.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** Fold is open and turned sideways: the hinge runs horizontally. */
@Composable
private fun rememberSideways(): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val info by remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val cfg = LocalConfiguration.current
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
    if (fold != null) return fold.orientation == FoldingFeature.Orientation.HORIZONTAL
    // No hinge reported (Samsung often reports none while the fold lies flat): the big inner
    // screen (smallest width >= 600dp; the cover screen is far below) held taller than wide.
    return cfg.smallestScreenWidthDp >= 600 && cfg.screenHeightDp > cfg.screenWidthDp
}

/**
 * true = show two facing pages.
 * Foldables report a FoldingFeature: spread only while it is vertical (hinge between
 * left and right pages). Other devices: wide landscape windows only.
 */
@Composable
private fun rememberSpread(): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val cfg = LocalConfiguration.current
    val info by remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
    return if (fold != null) {
        fold.orientation == FoldingFeature.Orientation.VERTICAL
    } else {
        cfg.screenWidthDp >= 600 && cfg.screenWidthDp > cfg.screenHeightDp
    }
}

private enum class Panel {
    NONE, BRIGHTNESS, SIZE, FONT;
    fun toggle(p: Panel) = if (this == p) NONE else p
}

@Composable
private fun MenuEmoji(e: String) = Text(e, fontSize = 17.sp)

@Composable
private fun PanelLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** -1 = follow the system brightness. */
@Composable
internal fun BrightnessPanel(value: Float, onChange: (Float) -> Unit) {
    val auto = value < 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanelLabel("화면 밝기")
        Spacer(Modifier.weight(1f))
        FilterChip(
            selected = auto,
            onClick = { onChange(if (auto) 0.5f else -1f) },
            label = { Text("자동 (시스템)") },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🔅", fontSize = 16.sp)
        Slider(
            value = if (auto) 0.5f else value,
            onValueChange = { onChange(it.coerceIn(0.02f, 1f)) },
            valueRange = 0.02f..1f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).alpha(if (auto) 0.45f else 1f),
        )
        Text("🔆", fontSize = 18.sp)
    }
}

@Composable
private fun SizePanel(
    weight: Int, onWeight: (Int) -> Unit,
    value: Int, onChange: (Int) -> Unit,
    lineHeight: Int, onLineHeight: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanelLabel("글자 크기")
        Spacer(Modifier.weight(1f))
        Text("${value}px", style = MaterialTheme.typography.labelLarge)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = value > ReaderPrefs.MIN_FONT,
            onClick = { onChange(value - 1) },
            contentPadding = CompactPad,
            modifier = Modifier.widthIn(min = 40.dp),
        ) { Text("가", fontSize = 13.sp) }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = ReaderPrefs.MIN_FONT.toFloat()..ReaderPrefs.MAX_FONT.toFloat(),
            steps = ReaderPrefs.MAX_FONT - ReaderPrefs.MIN_FONT - 1,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = value < ReaderPrefs.MAX_FONT,
            onClick = { onChange(value + 1) },
            contentPadding = CompactPad,
            modifier = Modifier.widthIn(min = 40.dp),
        ) { Text("가", fontSize = 20.sp) }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanelLabel("줄 간격")
        Spacer(Modifier.weight(1f))
        TextButton(enabled = lineHeight > 14, onClick = { onLineHeight(lineHeight - 1) }, contentPadding = CompactPad) { Text("－") }
        Text(String.format("%.1f", lineHeight / 10f), style = MaterialTheme.typography.labelLarge)
        TextButton(enabled = lineHeight < 24, onClick = { onLineHeight(lineHeight + 1) }, contentPadding = CompactPad) { Text("＋") }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanelLabel("글자 굵기")
        Spacer(Modifier.weight(1f))
        listOf("보통", "조금 굵게", "굵게").forEachIndexed { i, label ->
            FilterChip(
                selected = weight == i,
                onClick = { onWeight(i) },
                label = { Text(label, fontWeight = if (i == 0) FontWeight.Normal else if (i == 1) FontWeight.Medium else FontWeight.Bold) },
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun FontPanel(
    fonts: FontManager,
    selectedId: String,
    busyId: String?,
    progress: Float,
    onPick: (FontOption) -> Unit,
    onAdd: () -> Unit,
    onRemove: (FontOption) -> Unit,
) {
    // the user's own font files first
    PanelLabel("내 글꼴")
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FontManager.customs.forEach { f ->
            FilterChip(selected = selectedId == f.id, onClick = { onPick(f) }, label = { Text(f.label, maxLines = 1) })
        }
        androidx.compose.material3.AssistChip(onClick = onAdd, label = { Text("＋ 글꼴 파일 추가") })
        FontManager.customs.firstOrNull { it.id == selectedId }?.let { f ->
            TextButton(onClick = { onRemove(f) }, contentPadding = CompactPad) { Text("이 글꼴 삭제", color = MaterialTheme.colorScheme.error) }
        }
    }
    Spacer(Modifier.height(10.dp))
    for (kind in FontOption.Kind.entries) {
        val serif = kind == FontOption.Kind.SERIF
        PanelLabel(if (serif) "명조" else "고딕")
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FontManager.ALL.filter { it.kind == kind }.forEach { f ->
                val ready = fonts.isReady(f)
                val busy = busyId == f.id
                FilterChip(
                    selected = selectedId == f.id,
                    enabled = busyId == null || busy,
                    onClick = { onPick(f) },
                    label = {
                        Text(
                            when {
                                busy -> "${f.label.substringBefore(" ·")} ${(progress * 100).toInt()}%"
                                ready -> f.label.substringBefore(" ·")
                                else -> "⬇ " + f.label.substringBefore(" ·")
                            },
                            fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
                        )
                    },
                )
            }
        }
        if (serif) Spacer(Modifier.height(10.dp))
    }
}

/** Page margins: four steppers, each relative to the default (0 = as before). */
@Composable
private fun MarginPanel(top: Int, bottom: Int, left: Int, right: Int, onChange: (Int, Int, Int, Int) -> Unit) {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanelLabel("여백")
        Spacer(Modifier.weight(1f))
        TextButton(
            enabled = top != 0 || bottom != 0 || left != 0 || right != 0,
            onClick = { onChange(0, 0, 0, 0) }, contentPadding = CompactPad,
        ) { Text("기본으로") }
    }
    @Composable
    fun androidx.compose.foundation.layout.RowScope.Step(label: String, v: Int, range: IntRange, set: (Int) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 36.dp))
            TextButton(enabled = v > range.first, onClick = { set((v - 4).coerceAtLeast(range.first)) }, contentPadding = CompactPad) { Text("－") }
            Text(if (v == 0) "기본" else if (v > 0) "+$v" else "$v", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 34.dp), textAlign = TextAlign.Center)
            TextButton(enabled = v < range.last, onClick = { set((v + 4).coerceAtMost(range.last)) }, contentPadding = CompactPad) { Text("＋") }
        }
    }
    Row {
        Step("위", top, ReaderPrefs.MARGIN_V) { onChange(it, bottom, left, right) }
        Step("아래", bottom, ReaderPrefs.MARGIN_V) { onChange(top, it, left, right) }
    }
    Row {
        Step("왼쪽", left, ReaderPrefs.MARGIN_H) { onChange(top, bottom, it, right) }
        Step("오른쪽", right, ReaderPrefs.MARGIN_H) { onChange(top, bottom, left, it) }
    }
}

/** Page colour swatches: tap to choose; "자동" follows the app's light/dark setting. */
@Composable
private fun ThemeRow(current: ReaderTheme, onPick: (ReaderTheme) -> Unit) {
    Spacer(Modifier.height(10.dp))
    PanelLabel("배경")
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ReaderTheme.entries.forEach { t ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(t) }) {
                Box(
                    Modifier.size(38.dp)
                        .background(
                            if (t == ReaderTheme.AUTO) androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(ReaderTheme.PAPER.bg), Color(ReaderTheme.NIGHT.bg)))
                            else androidx.compose.ui.graphics.SolidColor(Color(t.bg)),
                            androidx.compose.foundation.shape.CircleShape,
                        )
                        .border(
                            if (t == current) 2.5.dp else 1.dp,
                            if (t == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("가", color = Color(if (t == ReaderTheme.AUTO) ReaderTheme.PAPER.fg else t.fg), fontSize = 14.sp)
                }
                Spacer(Modifier.height(3.dp))
                Text(t.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
