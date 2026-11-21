package com.oasisreader.app.data

import android.content.Context

/** How much space "바로 읽기" books may take before the oldest files are dropped. */
class CachePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("cache", Context.MODE_PRIVATE)

    var limitMb: Int
        get() = prefs.getInt("limit_mb", DEFAULT_MB)
        set(v) = prefs.edit().putInt("limit_mb", v).apply()

    /**
     * 다 읽은 책 파일 지우기, for phones with little room: [DROP_OFF], [DROP_CACHE] (바로 읽기 임시 책,
     * TODO 72) or [DROP_SHELF] (서재에 담은 책도, TODO 77). Off by default; the 2.1 on/off carries over.
     */
    var dropMode: Int
        get() = if (prefs.contains("drop_mode")) prefs.getInt("drop_mode", DROP_OFF)
            else if (prefs.getBoolean("drop_finished", false)) DROP_CACHE else DROP_OFF
        set(v) = prefs.edit().putInt("drop_mode", v).apply()

    val dropFinished: Boolean get() = dropMode != DROP_OFF

    /** 다음 회차 미리 받기 (TODO 78): reading a 서재 book, its next one without a file is received ahead. On by default. */
    var prefetchNext: Boolean
        get() = prefs.getBoolean("prefetch_next", true)
        set(v) = prefs.edit().putBoolean("prefetch_next", v).apply()

    val limitBytes: Long get() = limitMb.toLong() * 1024 * 1024

    companion object {
        const val DROP_OFF = 0
        const val DROP_CACHE = 1
        const val DROP_SHELF = 2
        const val DEFAULT_MB = 2048
        val CHOICES = listOf(256, 512, 1024, 2048, 5120, 10240, 20480, 51200)
        fun label(mb: Int) = if (mb >= 1024) "${mb / 1024}GB" else "${mb}MB"
    }
}
