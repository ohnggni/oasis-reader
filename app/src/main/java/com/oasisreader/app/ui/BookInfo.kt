package com.oasisreader.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.epub.EpubParser
import com.oasisreader.app.meta.MetaApi
import com.oasisreader.app.meta.MetaBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything known about a book in the library: what the app stored, the EPUB's own metadata,
 * and — for books from bookoasis with an oasis-meta server — series, file date and synopsis.
 */
@Composable
fun BookInfoDialog(b: BookEntity, store: BookStore, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val epubMeta by produceState(emptyMap<String, String>(), b.id) {
        if (b.format == BookStore.FORMAT_EPUB || b.format == BookStore.FORMAT_TXT) {
            value = withContext(Dispatchers.IO) { EpubParser.extraMeta(store.contentDir(b.id)) }
        }
    }
    // bookoasis download link -> (db, id) -> oasis-meta of the server it came from
    val meta by produceState<MetaBook?>(null, b.id) {
        val url = b.sourceUrl ?: return@produceState
        val (db, id) = MetaApi.idFromDownload(url) ?: return@produceState
        val server = ServerSettings(context).forUrl(url)?.takeIf { it.metaUrl.isNotBlank() } ?: return@produceState
        value = runCatching { MetaApi(server, db).book(id) }.getOrNull()
    }
    val fmt = remember { SimpleDateFormat("yyyy.M.d HH:mm", Locale.KOREA) }
    fun date(t: Long?) = t?.let { fmt.format(Date(it)) }
    var showSuspect by remember(b.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(b.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.Top) {
                    BookCover(
                        file = if (b.hasCover) store.coverFile(b.id) else null, title = b.title,
                        modifier = Modifier.width(84.dp).height(122.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        b.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        meta?.seriesName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                b.finishedAt != null -> "다 읽음 (${date(b.finishedAt)})"
                                b.lastReadAt == null -> "아직 읽지 않음"
                                else -> "${(b.progress * 100).toInt()}% 읽음"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Line("형식", when (b.format) {
                    BookStore.FORMAT_COMIC -> "만화 (CBZ/ZIP)"
                    BookStore.FORMAT_PDF -> "PDF"
                    BookStore.FORMAT_TXT -> "TXT"
                    else -> "EPUB"
                })
                Line("용량", "%.1f MB".format(b.fileBytes / 1048576.0))
                Line("보관", if (b.pinned) "서재에 보관" else "임시 보관 (공간이 부족하면 파일만 정리됩니다)")
                Line("서재에 담은 날", date(b.addedAt))
                Line("마지막으로 읽은 날", date(b.lastReadAt))
                meta?.let { m ->
                    Line("파일 날짜", com.oasisreader.app.meta.metaDate(m.fileMtime))
                    Line("쪽수", if (m.totalPages > 0) "${m.totalPages}쪽" else null)
                    Line("파일명", m.fileName)
                }
                b.sourceUrl?.let { u -> Line("받은 곳", ServerSettings(context).forUrl(u)?.name ?: u.substringAfter("://").substringBefore('/')) }
                epubMeta.filterKeys { it != "소개" }.forEach { (k, v) -> Line(k, v) }

                // synopsis: the server's checked one first, the book's own description otherwise
                val summary = meta?.summary?.trim()?.ifBlank { null }
                val suspect = meta?.summaryTrust == "suspect"
                val own = epubMeta["소개"]
                if (summary != null || own != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("줄거리", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    when {
                        summary != null && suspect && !showSuspect -> {
                            Text("⚠️ 다른 책 소개가 잘못 들어갔을 수 있습니다", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { showSuspect = true }) { Text("그래도 보기") }
                            own?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        summary != null -> Text(summary, style = MaterialTheme.typography.bodySmall)
                        else -> Text(own!!, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun Line(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
