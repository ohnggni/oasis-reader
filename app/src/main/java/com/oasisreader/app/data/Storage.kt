package com.oasisreader.app.data

import android.content.Context
import com.oasisreader.app.audio.AudioDownloads
import com.oasisreader.app.audio.AudioLibraryLink
import com.oasisreader.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the app keeps on the phone, by kind (설정 › 저장 공간). */
data class StorageUsage(
    val keptBooks: Int, val keptBytes: Long,      // 서재에 보관한 책 (EPUB·만화·PDF·TXT)
    val cacheBooks: Int, val cacheBytes: Long,    // 바로 읽기 (임시 보관)
    val audioBooks: Int, val audioBytes: Long,    // 폰에 받은 오디오북
    val otherBytes: Long,                         // 표지·글꼴·데이터베이스·설정 …
    val totalBytes: Long,
    val freeBytes: Long,
)

object Storage {
    private fun size(f: File): Long = if (!f.exists()) 0L else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun usage(context: Context): StorageUsage = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val store = BookStore.get(app)
        val rows = AppDatabase.get(app).books().all()
        val books = rows.filter { it.format != AudioLibraryLink.FORMAT && store.hasFiles(it.id) }
        val kept = books.filter { it.pinned }
        val cached = books.filter { !it.pinned }
        val keptBytes = kept.sumOf { store.filesBytes(it.id) }
        val cacheBytes = cached.sumOf { store.filesBytes(it.id) }
        val audio = AudioDownloads.bookIds(app).map { AudioDownloads.keptBytes(app, it) }.filter { it > 0 }
        val dataDir = File(app.applicationInfo.dataDir)
        val total = size(app.filesDir) + size(app.cacheDir) + size(File(dataDir, "databases")) + size(File(dataDir, "shared_prefs"))
        StorageUsage(
            kept.size, keptBytes, cached.size, cacheBytes, audio.size, audio.sum(),
            (total - keptBytes - cacheBytes - audio.sum()).coerceAtLeast(0), total, app.filesDir.usableSpace,
        )
    }

    /** 123 MB / 4.2 GB */
    fun label(bytes: Long): String =
        if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "%.0f MB".format(bytes / 1048576.0)
}
