package com.oasisreader.app.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState

/**
 * The page-by-page view of a comic: the pager, zoom and pan, curl drag, the turn overlay. The
 * reader's own state ([curPageState] and the like) stays with the screen that remembers it; the
 * state that only this view needs (the pager, zoom, the curl) is remembered in here, as before.
 */
@Composable
internal fun ComicPageMode(
    pkg: ComicPackage, content: java.io.File, isPdf: Boolean, spread: Boolean, wPx: Int, hPx: Int, density: androidx.compose.ui.unit.Density,
    rtl: Boolean, coverAlone: Boolean, viewKey: Int, effTurn: com.oasisreader.app.ui.TurnStyle, scope: kotlinx.coroutines.CoroutineScope,
    readerPrefs: com.oasisreader.app.reader.ReaderPrefs, overlayAlpha: androidx.compose.animation.core.Animatable<Float, *>, turnPicker: Boolean,
    curPageState: MutableIntState, curFracState: MutableState<Float>, lastPageShownState: MutableIntState,
    labelState: MutableState<String>, panelState: MutableState<com.oasisreader.app.ui.ReadPanel>, turnShotState: MutableState<com.oasisreader.app.ui.TurnShot?>,
    turnStyleState: MutableState<com.oasisreader.app.ui.TurnStyle>, turnSpeedState: MutableState<com.oasisreader.app.ui.TurnSpeed>,
    lastActiveState: MutableLongState,
    askPrevious: () -> Unit, toggleBars: () -> Unit, onTurnPickerDismiss: () -> Unit,
) {
    var curPage by curPageState
    var curFrac by curFracState
    var lastPageShown by lastPageShownState
    var label by labelState
    var panel by panelState
    var turnShot by turnShotState
    var turnStyle by turnStyleState
    var turnSpeed by turnSpeedState
    var lastActive by lastActiveState
    val pageCount = pkg.pages.size
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
        val stack = effTurn == com.oasisreader.app.ui.TurnStyle.COVER || effTurn == com.oasisreader.app.ui.TurnStyle.CURL
        // 종이 말림: a horizontal drag lifts the page under the finger (the pager does not slide)
        val curlDrag = effTurn == com.oasisreader.app.ui.TurnStyle.CURL && com.oasisreader.app.ui.TurnStyle.curlSupported
        var dragShot by remember { mutableStateOf<com.oasisreader.app.ui.TurnShot?>(null) }
        var dragFrom by remember { mutableIntStateOf(0) }
        fun turnTo(target: Int, tapY: Float = 0.5f) {
            // back from the first page: the volume before? (asked, not opened)
            if (target < 0 && pager.currentPage == 0) { askPrevious(); return }
            val t = target.coerceIn(0, groups.size - 1)
            if (t == pager.currentPage) return
            val next = t > pager.currentPage
            scope.launch {
                when {
                    effTurn == com.oasisreader.app.ui.TurnStyle.NONE -> pager.scrollToPage(t)
                    effTurn == com.oasisreader.app.ui.TurnStyle.CURL && com.oasisreader.app.ui.TurnStyle.curlSupported -> {
                        val img = runCatching { pagerLayer.toImageBitmap() }.getOrNull()
                        if (img == null) { pager.animateScrollToPage(t); return@launch }
                        // right-to-left books turn the other way on screen
                        turnShot = com.oasisreader.app.ui.TurnShot(img, forward = if (rtl) !next else next, style = effTurn, tapY = tapY, speed = turnSpeed)
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
        val backPx = with(density) { 110.dp.toPx() }
        val swipeBackFirst = Modifier.pointerInput(rtl) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var back = 0f
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val c = e.changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    if (e.changes.size > 1 || zoom > 1.01f || pager.currentPage != 0) { back = 0f; continue }
                    val dx = c.position.x - c.previousPosition.x
                    // the way back: finger to the right (to the left when read right to left)
                    val d = if (rtl) -dx else dx
                    back = if (d > 0f) back + d else 0f
                    if (back >= backPx) { back = 0f; askPrevious() }
                }
            }
        }
        HorizontalPager(
            state = pager,
            reverseLayout = rtl,
            beyondViewportPageCount = if (isPdf) 2 else 1,
            userScrollEnabled = zoom <= 1.01f && !curlDrag,
            key = { groups[it].first() },
            modifier = Modifier.fillMaxSize()
                .then(swipeBackFirst)
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
                                            img, forward = leftward, style = effTurn, tapY = startY, speed = turnSpeed, interactive = true,
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
            onDismiss = onTurnPickerDismiss,
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
                    if (com.oasisreader.app.ui.theme.ThemeMode.eink) overlayAlpha.snapTo(0f) else overlayAlpha.animateTo(0f, androidx.compose.animation.core.tween(140))
                    if (turnShot === done) turnShot = null
                    overlayAlpha.snapTo(1f)
                }
            },
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = overlayAlpha.value },
        )
    }
}
