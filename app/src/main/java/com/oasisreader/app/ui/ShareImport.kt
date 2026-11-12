package com.oasisreader.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.oasisreader.app.data.BookStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Is a file with this name (or, with no usable name, this type) a book the 서재 takes? */
fun isBookFile(name: String?, mime: String?): Boolean {
    val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    if (ext.isNotEmpty() && ext.length <= 5) return ext in ShareImport.EXT
    return mime?.lowercase() in ShareImport.MIME
}

/**
 * Books that arrive from other apps (TODO 49): 공유 › OG Reader in a file manager or a cloud app
 * (one file or several), or "다른 앱으로 열기". They go into the 서재 like ☰ › 파일에서 가져오기.
 * Shared text is not for here: that is a 독후감 ([ReviewInbox]).
 */
object ShareImport {
    val EXT = setOf("epub", "cbz", "zip", "pdf", "txt")
    val MIME = setOf(
        "application/epub+zip", "application/pdf", "application/zip", "application/x-zip-compressed",
        "application/x-cbz", "application/vnd.comicbook+zip",
    )

    @Suppress("DEPRECATION")
    private fun uris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> if (intent.type?.startsWith("text/") == true) emptyList() else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        Intent.ACTION_VIEW -> listOfNotNull(intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" })
        else -> emptyList()
    }

    /** True when [intent] brought files and they were taken. */
    fun offer(context: Context, intent: Intent?): Boolean {
        val list = intent?.let { uris(it) }.orEmpty()
        if (list.isEmpty()) return false
        run(context, list)
        return true
    }

    fun run(context: Context, list: List<Uri>) {
        val app = context.applicationContext
        Notice.show(app, if (list.size == 1) "서재에 담는 중…" else "${list.size}개를 서재에 담는 중…")
        ShelfActions.scope.launch {
            var ok = 0
            var last: String? = null
            var skipped = 0
            var error: String? = null
            for (u in list) {
                val fits = withContext(Dispatchers.IO) { isBookFile(nameOf(app, u), runCatching { app.contentResolver.getType(u) }.getOrNull()) }
                if (!fits) { skipped++; continue }
                try {
                    last = BookStore.get(app).importUri(u).id; ok++
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }
            val notes = listOfNotNull(
                skipped.takeIf { it > 0 }?.let { "책 파일이 아닌 ${it}개는 건너뜀" },
                error?.let { "실패: $it" },
            ).joinToString(" · ")
            val id = last
            if (id != null) Notice.added("${ok}권을 서재에 담았습니다" + (if (notes.isEmpty()) "" else " · $notes"), id)
            else Notice.show(app, "서재에 담지 못했습니다. " + notes.ifEmpty { "EPUB · CBZ/ZIP · PDF · TXT 파일만 담을 수 있습니다." } + " (EPUB · CBZ/ZIP · PDF · TXT)")
        }
    }

    private fun nameOf(c: Context, uri: Uri): String? = runCatching {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment
}
