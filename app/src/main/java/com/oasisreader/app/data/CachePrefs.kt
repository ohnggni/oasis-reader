package com.oasisreader.app.data

import android.content.Context

/** How much space "바로 읽기" books may take before the oldest files are dropped. */
class CachePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("cache", Context.MODE_PRIVATE)

    var limitMb: Int
        get() = prefs.getInt("limit_mb", DEFAULT_MB)
        set(v) = prefs.edit().putInt("limit_mb", v).apply()

    val limitBytes: Long get() = limitMb.toLong() * 1024 * 1024

    companion object {
        const val DEFAULT_MB = 2048
        val CHOICES = listOf(512, 1024, 2048, 5120, 10240, 20480, 51200)
        fun label(mb: Int) = if (mb >= 1024) "${mb / 1024}GB" else "${mb}MB"
    }
}
