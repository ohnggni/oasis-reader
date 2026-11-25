package com.oasisreader.app.reader

import com.oasisreader.app.ui.EmojiText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.HighlightEntity
import com.oasisreader.app.epub.EpubPackage
import com.oasisreader.app.epub.TocItem

@Composable
internal fun Failure(message: String, onRetry: (() -> Unit)?, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        if (onRetry != null) Button(onClick = onRetry) { Text("다시 시도") }
        TextButton(onClick = onBack) { Text("뒤로") }
    }
}

@Composable
internal fun MissingFiles(canFetch: Boolean, auto: Boolean, busy: Boolean, onFetch: () -> Unit, onBack: () -> Unit) {
    // a "바로 읽기" book whose file was dropped from the cache: fetch it without asking
    LaunchedEffect(Unit) { if (auto && canFetch && !busy) onFetch() }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmojiText("📦", fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            // 바로 읽기 · 등록만 (TODO 78): received without asking — say so
            if (canFetch && auto && busy) "서버에서 받는 중입니다…"
            else if (canFetch) "이 책 파일이 이 폰에 없습니다.\n서버에서 다시 받을 수 있습니다."
            else "이 책 파일이 이 폰에 없습니다.\n파일에서 다시 가져와 주세요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (canFetch) {
            if (busy) CircularProgressIndicator()
            else Button(onClick = onFetch) { EmojiText("⬇️ 다시 받기") }
        }
        TextButton(onClick = onBack) { Text("뒤로") }
    }
}

@Composable
internal fun BookHighlightsDialog(bookId: String, db: AppDatabase, onPick: (HighlightEntity) -> Unit, onDismiss: () -> Unit) {
    val list by remember(bookId) { db.highlights().observeFor(bookId) }.collectAsState(initial = null)
    var exporting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이 책의 하이라이트") },
        text = {
            val items = list
            when {
                items == null -> CircularProgressIndicator()
                items.isEmpty() -> Text("아직 없습니다. 글을 길게 눌러 고른 뒤 색을 누르면 생깁니다.", style = MaterialTheme.typography.bodyMedium)
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(items) { _, h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(h) }.padding(vertical = 8.dp),
                        ) {
                            Box(
                                Modifier.width(4.dp).height(40.dp)
                                    .background(runCatching { Color(android.graphics.Color.parseColor(h.color)) }.getOrDefault(Color.Yellow))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(h.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = {
            if (!list.isNullOrEmpty()) TextButton(onClick = { exporting = true }) { Text("내보내기") }
        },
    )
    if (exporting) com.oasisreader.app.ui.HighlightExportDialog(bookId) { exporting = false }
}

@Composable
internal fun TocDialog(pkg: EpubPackage, currentIndex: Int, onPick: (TocItem) -> Unit, onDismiss: () -> Unit, current: TocItem? = null) {
    // fall back to the spine when the book has no toc
    val items = remember(pkg) {
        pkg.toc.ifEmpty { pkg.spine.mapIndexed { i, s -> TocItem("${i + 1}. " + s.path.substringAfterLast('/'), s.path) } }
    }
    val currentTitle = remember(pkg, currentIndex) { chapterTitleFor(pkg, currentIndex) }
    // the section on screen (2.9), else the file's first entry
    val startAt = remember(items, current) {
        current?.let { c -> items.indexOfFirst { it == c } }?.takeIf { it >= 0 }
            ?: items.indexOfFirst { it.title == currentTitle }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startAt - 3).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("목차") },
        text = {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(items) { i, t ->
                    val here = i == startAt && currentTitle.isNotEmpty()
                    Text(
                        t.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (here) FontWeight.Bold else FontWeight.Normal,
                        color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(t) }
                            .padding(start = (t.level * 16).dp, top = 10.dp, bottom = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
