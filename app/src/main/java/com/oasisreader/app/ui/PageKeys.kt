package com.oasisreader.app.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * Page turning from keys: Bluetooth page-turner remotes, keyboards, media buttons and
 * (when switched on) the volume buttons. The activity hands every key here first, before
 * the WebView sees it, so arrow keys never scroll the paged book by themselves.
 *
 *   next: → ↓ Page Down, Space, media "next"      previous: ← ↑ Page Up, Shift+Space, media "previous"
 *   volume down = next, volume up = previous (setting, off by default)
 *
 * A screen that can turn pages registers with [PageTurnKeys]; with none registered keys
 * behave as usual. The handler gets +1 / -1 and whether the key was a left/right arrow
 * (a right-to-left comic flips those two).
 */
object PageKeys {
    private var handler: ((dir: Int, horizontal: Boolean) -> Unit)? = null
    private var owner: Any? = null

    fun register(key: Any, h: (Int, Boolean) -> Unit) { owner = key; handler = h }
    fun unregister(key: Any) { if (owner === key) { owner = null; handler = null } }

    private fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun volumeKeys(c: Context): Boolean = prefs(c).getBoolean("volumeKeys", false)
    fun setVolumeKeys(c: Context, on: Boolean) = prefs(c).edit().putBoolean("volumeKeys", on).apply()

    /** +1 / -1 for a page key, 0 otherwise. */
    fun direction(e: KeyEvent, volume: Boolean): Int = when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_NAVIGATE_NEXT -> 1
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS -> -1
        KeyEvent.KEYCODE_SPACE -> if (e.isShiftPressed) -1 else 1
        KeyEvent.KEYCODE_VOLUME_DOWN -> if (volume) 1 else 0
        KeyEvent.KEYCODE_VOLUME_UP -> if (volume) -1 else 0
        else -> 0
    }

    /** From Activity.dispatchKeyEvent: true when the key was used (down and up are both swallowed). */
    fun dispatch(c: Context, e: KeyEvent): Boolean {
        val h = handler ?: return false
        val dir = direction(e, volumeKeys(c))
        if (dir == 0) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            val horizontal = e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            h(dir, horizontal)
        }
        return true
    }
}

/** While this is on screen, page keys call [onTurn] (+1 next / -1 previous; horizontal = a ←/→ arrow). */
@Composable
fun PageTurnKeys(enabled: Boolean = true, onTurn: (dir: Int, horizontal: Boolean) -> Unit) {
    val latest = rememberUpdatedState(onTurn)
    DisposableEffect(enabled) {
        val key = Any()
        if (enabled) PageKeys.register(key) { d, h -> latest.value(d, h) }
        onDispose { PageKeys.unregister(key) }
    }
}
