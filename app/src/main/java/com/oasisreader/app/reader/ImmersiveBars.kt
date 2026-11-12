package com.oasisreader.app.reader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * What the reading screens show around the page (읽기 기본값, and per book in the ▤ panel):
 * system bars, the top line (chapter title · clock · battery) and the page number at the bottom.
 */
data class ReadingChrome(
    val hideStatus: Boolean, val hideNav: Boolean, val clock: Boolean, val battery: Boolean,
    val head: Boolean = true, val pageNum: Boolean = true,
) {
    /** The app's own top line where the status bar was (title left, clock + battery right). */
    val statusLine get() = hideStatus && (head || clock || battery)

    companion object {
        fun of(p: ReaderPrefs) = ReadingChrome(p.hideStatusBar, p.hideNavBar, p.showClock, p.showBattery, p.showHead, p.showPageNum)
    }
}

/** Top line: its padding from the top edge (the same as the page number's from the bottom) and height. */
val STATUS_LINE_TOP = 10.dp
val STATUS_LINE_HEIGHT = 18.dp

/**
 * Where the camera hole is at the top, in dp: [top] = how far down the page must start
 * (only when the hole is in the middle, where the chapter title is), [left]/[right] = what a corner
 * hole takes from the top line (so the clock / battery step around it).
 */
data class CutoutRoom(val top: Dp = 0.dp, val left: Dp = 0.dp, val right: Dp = 0.dp)

@Composable
fun rememberCutoutRoom(): CutoutRoom {
    val view = LocalView.current
    val density = LocalDensity.current
    val conf = LocalConfiguration.current
    return remember(conf.screenWidthDp, conf.screenHeightDp, conf.orientation) {
        val insets = ViewCompat.getRootWindowInsets(view)
        val cut = insets?.displayCutout ?: return@remember CutoutRoom()
        val w = view.rootView.width.takeIf { it > 0 } ?: with(density) { conf.screenWidthDp.dp.roundToPx() }
        val line = with(density) { 40.dp.toPx() }
        var top = 0f; var left = 0f; var right = 0f
        cut.boundingRects.filter { it.top < line }.forEach { r ->
            when {
                r.right < w * 0.3f -> left = maxOf(left, r.right.toFloat())
                r.left > w * 0.7f -> right = maxOf(right, (w - r.left).toFloat())
                // a hole in the middle: the top line still starts at the very top (it may sit over the hole)
            }
        }
        with(density) { CutoutRoom(top.toDp(), left.toDp(), right.toDp()) }
    }
}

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * While a book / comic / PDF is open: hide the status and/or navigation bar (a swipe from the
 * edge shows them for a moment, over the page, without moving it). Leaving shows them again.
 */
@Composable
fun ImmersiveBars(chrome: ReadingChrome, appBarsUp: Boolean = false) {
    val view = LocalView.current
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    // the navigation bar is hidden only when that gains something: not when the phone itself already
    // hides it (gesture navigation without the hint — hiding it again only makes "back" take two
    // swipes), and not while the app's own bars are up (so one swipe goes back from there)
    val navHidden = chrome.hideNav && !appBarsUp && !navBarAlreadyHidden(view)
    DisposableEffect(chrome.hideStatus, navHidden, owner) {
        val window = view.context.activity()?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        fun apply() {
            ctl ?: return
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chrome.hideStatus) ctl.hide(WindowInsetsCompat.Type.statusBars()) else ctl.show(WindowInsetsCompat.Type.statusBars())
            if (navHidden) ctl.hide(WindowInsetsCompat.Type.navigationBars()) else ctl.show(WindowInsetsCompat.Type.navigationBars())
        }
        apply()
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) apply() }
        owner.lifecycle.addObserver(obs)
        // a dialog, menu or keyboard takes the window focus and Android shows the bars again:
        // hide them once the focus comes back
        // after leaving, a re-hide already posted must not run (it hid the bars again on the shelf
        // on some phones, and the insets came back late → the top bar sat under the status bar)
        var alive = true
        val reapply = Runnable { if (alive) apply() }
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { has ->
            if (has && alive) { view.removeCallbacks(reapply); view.post(reapply) }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        onDispose {
            alive = false
            view.removeCallbacks(reapply)
            owner.lifecycle.removeObserver(obs)
            runCatching { view.viewTreeObserver.removeOnWindowFocusChangeListener(focus) }
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            ViewCompat.requestApplyInsets(view)
        }
    }
}

/**
 * The phone shows no navigation bar of its own (gesture navigation with the hint hidden): its size,
 * even while hidden, is under 16dp. Then the app's "내비게이션 바 숨기기" has nothing to hide.
 */
fun navBarAlreadyHidden(view: android.view.View): Boolean {
    val insets = ViewCompat.getRootWindowInsets(view) ?: return false
    val nav = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
    val px = maxOf(nav.bottom, nav.left, nav.right)
    return px < 16 * view.resources.displayMetrics.density
}

/**
 * The page's padding from the screen edges: the bars' insets as before, or — for a hidden bar — a
 * fixed amount that does not change when the bar peeks in (so the pages are not laid out again).
 */
fun Modifier.readingInsets(chrome: ReadingChrome, cut: CutoutRoom, extraTop: Dp = 0.dp): Modifier = composed {
    var m: Modifier = this
    m = if (chrome.hideStatus) m.padding(top = cut.top + extraTop) else m.statusBarsPadding().padding(top = extraTop)
    if (!chrome.hideNav) m = m.navigationBarsPadding()
    m
}

/** Top inset for the app's own bar: under the status bar, or right at the top (clear of a middle camera hole). */
fun Modifier.barTopInset(chrome: ReadingChrome, cut: CutoutRoom): Modifier = composed {
    if (chrome.hideStatus) padding(top = cut.top, start = cut.left, end = cut.right) else statusBarsPadding()
}

/**
 * The top line where the status bar was: the title on the left (from the left edge, a camera hole
 * may sit over it), clock and battery together on the right, in the page number's size and colour,
 * [STATUS_LINE_TOP] from the top. Nothing unless the status bar is hidden.
 */
@Composable
fun StatusLine(
    chrome: ReadingChrome, cut: CutoutRoom, color: Color, modifier: Modifier = Modifier, title: String? = null,
) {
    if (!chrome.statusLine) return
    val ctx = LocalContext.current
    val time by rememberClock(ctx, chrome.clock)
    val battery by rememberBattery(ctx, chrome.battery)
    val right = listOfNotNull(time.takeIf { chrome.clock }, battery.takeIf { chrome.battery && it.isNotEmpty() }).joinToString("  ")
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().padding(top = STATUS_LINE_TOP, start = 16.dp, end = maxOf(16.dp, cut.right + 6.dp)).height(STATUS_LINE_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (chrome.head) title.orEmpty() else "", color = color, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (right.isNotEmpty()) Text(right, color = color, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
    }
}

/** The time as the phone shows it (12/24-hour setting), updated each minute. */
@Composable
private fun rememberClock(ctx: Context, on: Boolean): androidx.compose.runtime.State<String> {
    fun now() = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date())
    val state = remember { mutableStateOf(now()) }
    DisposableEffect(on) {
        if (!on) return@DisposableEffect onDispose { }
        state.value = now()
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { state.value = now() } }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    return state
}

/** "85%" (and ⚡ while charging), from the sticky battery broadcast. */
@Composable
private fun rememberBattery(ctx: Context, on: Boolean): androidx.compose.runtime.State<String> {
    val state = remember { mutableStateOf("") }
    DisposableEffect(on) {
        if (!on) return@DisposableEffect onDispose { }
        fun read(i: Intent?) {
            i ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
            if (level < 0) return
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            state.value = (if (plugged) "⚡" else "") + "${level * 100 / scale}%"
        }
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { read(i) } }
        read(ContextCompat.registerReceiver(ctx, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED))
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    return state
}

/** Writes [c] (per book when [this] has a book; the navigation bar is always for every book). */
fun ReaderPrefs.saveChrome(c: ReadingChrome) {
    hideStatusBar = c.hideStatus; showClock = c.clock; showBattery = c.battery
    showHead = c.head; showPageNum = c.pageNum; hideNavBar = c.hideNav
}

/**
 * The switches for what is shown around the page: 상태바 숨기기 · 장 제목 (comics: 책 제목) ·
 * 시계 · 배터리 (only with the status bar hidden) · 쪽 번호 [· 내비게이션 바 숨기기].
 */
@Composable
fun ChromeSwitches(c: ReadingChrome, comic: Boolean = false, withNav: Boolean = false, onChange: (ReadingChrome) -> Unit) {
    val w = Modifier
    androidx.compose.foundation.layout.Column {
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            com.oasisreader.app.ui.PanelSwitch("상태바 숨기기", null, c.hideStatus, { onChange(c.copy(hideStatus = it)) }, w.weight(1f))
            com.oasisreader.app.ui.PanelSwitch(if (comic) "책 제목" else "장 제목", null, c.head, { onChange(c.copy(head = it)) }, w.weight(1f))
        }
        if (c.hideStatus) androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            com.oasisreader.app.ui.PanelSwitch("시계", null, c.clock, { onChange(c.copy(clock = it)) }, w.weight(1f))
            com.oasisreader.app.ui.PanelSwitch("배터리", null, c.battery, { onChange(c.copy(battery = it)) }, w.weight(1f))
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            com.oasisreader.app.ui.PanelSwitch("아래 쪽 번호", null, c.pageNum, { onChange(c.copy(pageNum = it)) }, w.weight(1f))
            if (withNav) {
                val navNeeded = !navBarAlreadyHidden(LocalView.current)
                com.oasisreader.app.ui.PanelSwitch(
                    "내비게이션 바 숨기기", if (navNeeded) null else "이 폰은 이미 숨겨져 있어 필요 없습니다",
                    c.hideNav && navNeeded, { onChange(c.copy(hideNav = it)) }, w.weight(1f), enabled = navNeeded,
                )
            }
            else androidx.compose.foundation.layout.Spacer(w.weight(1f))
        }
        if (withNav && navBarAlreadyHidden(LocalView.current)) Text(
            "이 폰은 내비게이션 바가 이미 숨겨져 있어(제스처 힌트 숨김) 이 설정이 필요 없습니다.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
