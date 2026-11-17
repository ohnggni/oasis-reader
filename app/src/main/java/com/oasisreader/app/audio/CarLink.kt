package com.oasisreader.app.audio

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Is the phone connected to a car (Android Auto)? Then the car's own volume decides what is heard:
 * the phone's media volume neither follows the car's knob nor changes the car's sound (TODO 67).
 * Bluetooth is different (absolute volume keeps both in step), so only this is checked.
 *
 * Android Auto publishes its state on a content provider, the same one the androidx car-app
 * library reads (that library is not added): column "CarConnectionState" 0 = not connected,
 * 1 = the phone runs inside the car (native), 2 = projected (Android Auto). Any answer other
 * than "not connected" counts; a missing provider (no Android Auto installed) = not connected.
 */
object CarLink {
    private val uri: Uri = Uri.Builder().scheme("content").authority("androidx.car.app.connection").build()
    private const val COLUMN = "CarConnectionState"

    fun connected(context: Context): Boolean = runCatching {
        context.applicationContext.contentResolver.query(uri, arrayOf(COLUMN), null, null, null)?.use { c ->
            val i = c.getColumnIndex(COLUMN)
            c.moveToNext() && i >= 0 && c.getInt(i) != 0
        } ?: false
    }.getOrDefault(false)
}

/** [CarLink.connected], kept up to date while the screen is shown. */
@Composable
fun rememberCarConnected(): Boolean {
    val context = LocalContext.current
    var on by remember { mutableStateOf(CarLink.connected(context)) }
    DisposableEffect(Unit) {
        val app = context.applicationContext
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { on = CarLink.connected(app) }
        }
        val uri = Uri.Builder().scheme("content").authority("androidx.car.app.connection").build()
        runCatching { app.contentResolver.registerContentObserver(uri, true, observer) }
        on = CarLink.connected(app)
        onDispose { runCatching { app.contentResolver.unregisterContentObserver(observer) } }
    }
    return on
}
