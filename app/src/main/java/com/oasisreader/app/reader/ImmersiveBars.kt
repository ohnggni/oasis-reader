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
import androidx.compose.material3.Text
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

/** The reading screens' choices about the system bars (읽기 기본값 › 화면). */
data class ReadingChrome(val hideStatus: Boolean, val hideNav: Boolean, val clock: Boolean, val battery: Boolean) {
    /** The clock/battery line is only drawn where the status bar was. */
    val statusLine get() = hideStatus && (clock || battery)

    companion object {
        fun of(p: ReaderPrefs) = ReadingChrome(p.hideStatusBar, p.hideNavBar, p.showClock, p.showBattery)
    }
}

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
                else -> top = maxOf(top, r.bottom.toFloat())
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
fun ImmersiveBars(chrome: ReadingChrome) {
    val view = LocalView.current
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(chrome.hideStatus, chrome.hideNav, owner) {
        val window = view.context.activity()?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        fun apply() {
            ctl ?: return
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chrome.hideStatus) ctl.hide(WindowInsetsCompat.Type.statusBars()) else ctl.show(WindowInsetsCompat.Type.statusBars())
            if (chrome.hideNav) ctl.hide(WindowInsetsCompat.Type.navigationBars()) else ctl.show(WindowInsetsCompat.Type.navigationBars())
        }
        apply()
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) apply() }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            ctl?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
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
 * The top line where the status bar was: clock on the left, battery on the right, small and muted,
 * like the chapter title line. Nothing when both are off. Height [height].
 */
@Composable
fun StatusLine(chrome: ReadingChrome, cut: CutoutRoom, color: Color, modifier: Modifier = Modifier, height: Dp = 30.dp) {
    if (!chrome.statusLine) return
    val ctx = LocalContext.current
    val time by rememberClock(ctx, chrome.clock)
    val battery by rememberBattery(ctx, chrome.battery)
    Box(modifier.fillMaxWidth().height(height)) {
        if (chrome.clock) Text(
            time, color = color, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = maxOf(14.dp, cut.left + 6.dp)),
        )
        if (chrome.battery && battery.isNotEmpty()) Text(
            battery, color = color, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = maxOf(14.dp, cut.right + 6.dp)),
        )
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
