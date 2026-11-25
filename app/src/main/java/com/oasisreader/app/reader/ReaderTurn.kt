package com.oasisreader.app.reader

import android.webkit.WebView
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.oasisreader.app.ui.TurnShot
import com.oasisreader.app.ui.TurnStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The page-turn picture and everything that goes with it: the style and speed chosen, the picture
 * of the page now on screen laid over the page while it turns, and the curl dragged with the
 * finger. The state lives here (one instance, remembered by the reader); the reader passes in
 * what the functions need from the screen.
 */
internal class PageTurnState(prefs: ReaderPrefs) {
    var turnStyle by mutableStateOf(prefs.turnStyle)
    var turnShot by mutableStateOf<TurnShot?>(null)
    var turnPicker by mutableStateOf(false)
    var turnSpeed by mutableStateOf(prefs.turnSpeed)
    var turnHold by mutableStateOf(false)   // chapter change: old page waits on top until the new one is ready
    var dragShot by mutableStateOf<TurnShot?>(null)   // curl under the finger
    var dragCssW by mutableFloatStateOf(0f)
    // the picture is taken asynchronously: finger moves / release that arrive before it are kept here
    var dragFinger by mutableStateOf<Offset?>(null)
    var dragRelease by mutableStateOf<Boolean?>(null)
    var dragForward by mutableStateOf(true)

    /**
     * Picture of the page now on screen as the turn overlay, then [then] moves the
     * text underneath. Copies the screen's pixels (WebView.draw() can render at
     * another scale); draw() is only the fallback.
     */
    fun capture(
        wv: WebView?, context: android.content.Context, densityF: Float, paper: Color, effTurn: TurnStyle,
        forward: Boolean, tapY: Float, interactive: Boolean = false, then: () -> Unit,
    ) {
        if (wv == null || wv.width <= 0) { then(); return }
        val bmp = android.graphics.Bitmap.createBitmap(wv.width, wv.height, android.graphics.Bitmap.Config.ARGB_8888)
        fun show() {
            // the page number is drawn live on top of the turn: blank it out of the
            // picture so it does not show twice (the running head is part of the page)
            val cv = android.graphics.Canvas(bmp)
            val pnt = android.graphics.Paint().apply { color = paper.toArgb() }
            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
            cv.drawRect(w * 0.2f, h - 34 * densityF, w * 0.8f, h, pnt)
            val shot = TurnShot(bmp.asImageBitmap(), forward, effTurn, tapY, turnSpeed, interactive)
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

    /** A curl dragged with the finger: x, y in page CSS px (scaled to the view). */
    fun drag(
        phase: Int, x: Float, y: Float, extra: Float, wv: WebView?, densityF: Float,
        captureInteractive: (forward: Boolean, tapY: Float, then: () -> Unit) -> Unit, commit: () -> Unit,
    ) {
        val k = if (wv != null && dragCssW > 0f) wv.width / dragCssW else densityF
        when (phase) {
            0 -> {   // start: extra = direction, y = start height (0..1), x = page width (css)
                dragCssW = x
                val forward = extra > 0
                dragShot = null; dragFinger = null; dragRelease = null; dragForward = forward
                captureInteractive(forward, y.coerceIn(0f, 1f), commit)
            }
            1 -> {
                val p = Offset(x * k, y * k)
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
    }

    /** The overlay's turn is over ([ok]) or was let go early: lay the old page back, fade the picture out. */
    fun overlayDone(done: TurnShot, ok: Boolean, scope: CoroutineScope, wv: () -> WebView?, overlayAlpha: Animatable<Float, *>) {
        if (ok) { if (turnShot === done) turnShot = null }
        else scope.launch {
            // let go early: the old page lies flat again on top; put it back underneath, then lift the picture.
            // The WebView draws on its own thread: wait until it has really drawn the old page again
            // (a fixed few frames was sometimes too early and the next page flashed), then fade the picture out.
            val view = wv()
            if (view != null) kotlinx.coroutines.withTimeoutOrNull(600) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
                    view.evaluateJavascript("Reader.jumpBy(${if (done.forward) -1 else 1})") {
                        view.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
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
    }
}
