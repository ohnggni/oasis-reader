package com.oasisreader.app.data

import android.content.Context
import android.net.ConnectivityManager

/** Receiving settings both queues (books and audiobooks) read. */
object DownloadPrefs {
    /** 받기 관리 › "와이파이에서만 받기". */
    fun wifiOnly(context: Context) = context.getSharedPreferences("audio", Context.MODE_PRIVATE).getBoolean("wifi_only", false)

    /** "와이파이에서만 받기" is on and there is no unmetered connection now: queued work would wait (103). */
    fun mustWait(context: Context): Boolean {
        if (!wifiOnly(context)) return false
        val net = context.getSystemService(ConnectivityManager::class.java) ?: return false
        // the same test as WorkManager's NetworkType.UNMETERED
        return net.activeNetwork == null || net.isActiveNetworkMetered
    }
}
