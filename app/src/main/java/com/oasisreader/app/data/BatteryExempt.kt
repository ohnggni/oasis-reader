package com.oasisreader.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 배터리 제한 없음 (TODO 70). This app is a 서재: books are received to the phone, in the background.
 * Under the battery limit the system pauses that work (a download "waits" for ever), so the first
 * time something is queued the app asks once for "제한 없음"; 설정 › 받기 has the same switch.
 * The system itself shows the question; the app cannot grant it.
 */
object BatteryExempt {
    private const val PREFS = "battery"
    private const val ASKED = "asked"

    /** True while the one-time question should be shown (MainActivity draws it). */
    var asking by mutableStateOf(false)
        private set

    fun granted(c: Context): Boolean =
        runCatching { c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName) }.getOrDefault(true)

    /** Something was queued: ask once, unless it is already free of the limit. */
    fun noteQueued(c: Context) {
        val app = c.applicationContext
        if (granted(app)) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) return
        prefs.edit().putBoolean(ASKED, true).apply()
        asking = true
    }

    fun dismiss() { asking = false }

    /** The system's own question; falls back to the list of apps when the phone has no such screen. */
    fun request(c: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")).addFlags(flags)
        runCatching { c.startActivity(direct) }.onFailure {
            runCatching { c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(flags)) }
        }
    }
}
