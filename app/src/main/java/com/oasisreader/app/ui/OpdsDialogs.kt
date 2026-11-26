package com.oasisreader.app.ui

import com.oasisreader.app.data.Server
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.MutableState
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.Downloads
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.data.OpdsEntry
import kotlinx.coroutines.launch

/** One entry: cover, summary, 읽기 · 바로 읽기 · 서재에 담기. */
@Composable
internal fun OpdsEntryDialog(
    detailState: MutableState<OpdsEntry?>, ownedSet: Set<String>, progress: Map<String, Float>, client: OpdsClient,
    db: AppDatabase, scope: CoroutineScope, app: Context, server: Server, path: OpdsPath, onOpenBook: (String) -> Unit,
) {
    var detail by detailState
    detail?.let { e ->
        val inLibrary = e.id in ownedSet
        val p = progress[e.id]
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(e.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (e.thumb != null) {
                        RemoteCover(e.thumb, e.title, load = { client.bytes(it) },
                            modifier = Modifier.width(150.dp).height(220.dp).align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(10.dp))
                    }
                    e.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            e.readable == null -> "⚠️ 읽을 수 있는 파일이 없습니다 (" + e.acquisitions.joinToString { it.type.substringAfter('/') } + ")"
                            e.isComic -> "🖼 웹툰·만화 (CBZ/ZIP)"
                            e.readable?.let { a -> a.type.startsWith("application/pdf") || a.href.substringBefore('?').lowercase().endsWith(".pdf") } == true -> "📄 PDF"
                            e.readable?.let { a -> a.type.startsWith("text/plain") || a.href.substringBefore('?').lowercase().endsWith(".txt") } == true -> "📝 TXT"
                            else -> "📖 EPUB"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    e.summary?.takeUnless { e.isComic }?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                when {
                    inLibrary -> TextButton(onClick = {
                        detail = null
                        scope.launch { db.books().findBySource(e.id, null)?.let { onOpenBook(it.id) } }
                    }) { EmojiText("📖 읽기") }
                    p != null -> TextButton(onClick = {}, enabled = false) { Text("받는 중…") }
                    e.readable != null -> Row {
                        TextButton(onClick = {
                            detail = null
                            Downloads.start(app, server, e, pinned = false, onDone = { onOpenBook(it.id) }, origin = path.here())
                        }) { EmojiText("📖 바로 읽기") }
                        TextButton(onClick = { Downloads.start(app, server, e, origin = path.here()) }) { EmojiText("⬇️ 서재에 담기") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("닫기") } },
        )
    }
}

/** ⭐ 즐겨찾기 편집: rename, move up, remove. */
@Composable
internal fun FavoritesEditDialog(list: List<OpdsFavorites.Place>, onChange: (List<OpdsFavorites.Place>) -> Unit, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⭐ 즐겨찾기 편집") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                list.forEachIndexed { i, f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(f.labels.drop(1).joinToString(" › "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { if (i > 0) onChange(list.toMutableList().also { l -> val t = l[i]; l[i] = l[i - 1]; l[i - 1] = t }) }, enabled = i > 0) { Text("🔼") }
                        TextButton(onClick = { renaming = i }) { Text("✏️") }
                        TextButton(onClick = { onChange(list.filterIndexed { j, _ -> j != i }) }) { EmojiText("🗑️") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("완료") } },
    )
    renaming?.let { i ->
        CollectionNameDialog("즐겨찾기 이름", list[i].name, onDismiss = { renaming = null }) { name ->
            renaming = null
            onChange(list.toMutableList().also { it[i] = it[i].copy(name = name) })
        }
    }
}
