package com.oasisreader.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.oasisreader.app.data.BookDetails
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.ReadingTimeEntity
import com.oasisreader.app.epub.EpubParser
import com.oasisreader.app.meta.MetaApi
import com.oasisreader.app.data.HighlightExport
import com.oasisreader.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class MarkdownExport(val title: String, val count: Int, val text: String)

/** One book's highlights as Markdown: share the text, or save a .md file. */
@Composable
fun HighlightExportDialog(bookId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var export by remember(bookId) { mutableStateOf<MarkdownExport?>(null) }
    var loading by remember(bookId) { mutableStateOf(true) }

    LaunchedEffect(bookId) {
        export = withContext(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.get(app)
                val book = db.books().get(bookId)
                val list = db.highlights().allFor(bookId)
                val store = BookStore.get(app)
                // book.json stays even when a "바로 읽기" file was dropped, so chapter titles still work
                val pkg = if (File(store.dir(bookId), "book.json").isFile) runCatching { store.readPackage(bookId) }.getOrNull() else null
                val title = book?.title ?: pkg?.title ?: "지운 책"
                MarkdownExport(
                    title = title,
                    count = list.size,
                    text = HighlightExport.markdown(
                        title, book?.author ?: pkg?.author, list, { HighlightExport.chapterTitle(pkg, it) },
                        details = loadDetails(app, store, book, title, db.readingTime().forBook(bookId)),
                    ),
                )
            }.getOrNull()
        }
        loading = false
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val e = export
        if (uri == null || e == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openOutputStream(uri)?.use { it.write(e.text.toByteArray()) } != null }.getOrDefault(false)
            }
            Toast.makeText(app, if (ok) "하이라이트 ${e.count}개를 내보냈습니다." else "저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
            if (ok) onDismiss()
        }
    }

    val e = export
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("하이라이트 내보내기") },
        text = {
            Column {
                when {
                    loading -> CircularProgressIndicator()
                    e == null -> Text("하이라이트를 읽지 못했습니다.")
                    e.count == 0 -> Text("내보낼 하이라이트가 없습니다.")
                    else -> {
                        Text("${e.title}\n하이라이트 ${e.count}개를 마크다운으로 내보냅니다.", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "블로그나 메모 앱에 붙여넣으면 제목·인용문으로 바뀝니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (e != null && e.count > 0) TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "${e.title} 하이라이트")
                    putExtra(Intent.EXTRA_TEXT, e.text)
                }
                runCatching { context.startActivity(Intent.createChooser(send, "하이라이트 공유")) }
                    .onFailure { Toast.makeText(app, "공유하지 못했습니다.", Toast.LENGTH_SHORT).show() }
                onDismiss()
            }) { Text("공유") }
            else TextButton(onClick = onDismiss) { Text("닫기") }
        },
        dismissButton = {
            if (e != null && e.count > 0) TextButton(onClick = { saver.launch(HighlightExport.fileName(e.title, "md")) }) { Text("파일로 저장") }
        },
    )
}

/** ⋮ menu on the highlights screen: every highlight as one JSON file (W3C Web Annotation based). */
@Composable
fun HighlightJsonExportMenu(enabled: Boolean) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val db = AppDatabase.get(app)
                    val store = BookStore.get(app)
                    val list = db.highlights().all()
                    val books = db.books().all().associateBy { it.id }
                    val times = db.readingTime().all().groupBy { it.bookId }
                    val details = list.map { it.bookId }.distinct().associateWith { id ->
                        val b = books[id]
                        if (b == null) BookDetails() else loadDetails(app, store, b, b.title, times[id].orEmpty())
                    }
                    val text = HighlightExport.json(books.values.toList(), list, { id ->
                        if (File(store.dir(id), "book.json").isFile) runCatching { store.readPackage(id) }.getOrNull() else null
                    }, { details[it] ?: BookDetails() })
                    app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream")
                    list.size
                }.getOrNull()
            }
            Toast.makeText(app, if (count != null) "하이라이트 ${count}개를 내보냈습니다." else "저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "더보기") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("전체 내보내기 (JSON)") },
                enabled = enabled,
                onClick = {
                    menu = false
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    saver.launch("og-reader-highlights-$day.json")
                },
            )
        }
    }
}

/**
 * Book details for an export: the EPUB's own metadata (only while its files are on the phone),
 * the bookoasis meta server's series and synopsis (books downloaded from a server with oasis-meta),
 * and the reading records. Anything that fails is simply left out. Call off the main thread.
 */
internal suspend fun loadDetails(
    app: android.content.Context,
    store: BookStore,
    book: BookEntity?,
    title: String,
    times: List<ReadingTimeEntity>,
): BookDetails {
    if (book == null) return BookDetails()
    val epub = if (book.format == BookStore.FORMAT_EPUB || book.format == BookStore.FORMAT_TXT)
        runCatching { EpubParser.extraMeta(store.contentDir(book.id)) }.getOrDefault(emptyMap()) else emptyMap()
    val meta = runCatching {
        val url = book.sourceUrl ?: return@runCatching null
        val (db, id) = MetaApi.idFromDownload(url) ?: return@runCatching null
        val server = ServerSettings(app).forUrl(url)?.takeIf { it.metaUrl.isNotBlank() } ?: return@runCatching null
        MetaApi(server, db).book(id)
    }.getOrNull()
    val read = times.filter { it.seconds > 0 }
    return BookDetails(
        series = BookDetails.series(meta?.seriesName, title),
        publisher = epub["출판사"],
        published = epub["발행일"],
        subjects = epub["분류"],
        description = BookDetails.description(meta?.summary, meta?.summaryTrust, epub["소개"]),
        firstReadDay = read.minOfOrNull { it.day },
        lastReadDay = read.maxOfOrNull { it.day },
        readingSeconds = read.sumOf { it.seconds },
        finishedAt = book.finishedAt,
        progress = book.progress.takeIf { book.finishedAt == null && book.lastReadAt != null },
    )
}
