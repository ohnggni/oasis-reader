package com.oasisreader.app.data

import android.content.Context

/** Receiving settings both queues (books and audiobooks) read. */
object DownloadPrefs {
    /** 받기 관리 › "와이파이에서만 받기". */
    fun wifiOnly(context: Context) = context.getSharedPreferences("audio", Context.MODE_PRIVATE).getBoolean("wifi_only", false)
}
