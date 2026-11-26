package com.oasisreader.app.meta

import com.oasisreader.app.data.MetaBook
import com.oasisreader.app.data.MetaApi
import com.oasisreader.app.ui.EmojiText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.ui.TopBar

/** One volume's details: big cover, everything oasis-meta knows, and the download / read button. */
@Composable
internal fun BookDetail(
    b: MetaBook,
    api: MetaApi,
    opds: OpdsClient,
    owned: Boolean,
    pinned: Boolean,
    progress: Float?,
    library: String? = null,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onKeep: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            // swallow touches so the list underneath can't be tapped through
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        TopBar(b.name, onBack = onBack) { com.oasisreader.app.ui.HomeButton() }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MetaCover(api, opds, b.coverUrl, b.id, b.name, modifier = Modifier.width(180.dp).aspectRatio(0.68f))
            Spacer(Modifier.height(16.dp))
            Text(b.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            b.seriesName?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            when {
                progress != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (progress < 0f) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    else CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (progress < 0f) "받는 중…" else "받는 중 ${(progress * 100).toInt()}%")
                }
                owned -> {
                    Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) { EmojiText("📖 읽기") }
                    if (!pinned) {
                        OutlinedButton(onClick = onKeep, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { EmojiText("📌 서재에 보관 (지금은 임시)") }
                    }
                }
                b.readable -> {
                    Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) { EmojiText("📖 바로 읽기") }
                    OutlinedButton(onClick = onKeep, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        EmojiText("⬇️ 서재에 담기" + (mb(b.fileSize)?.let { " ($it)" } ?: ""))
                    }
                }
                else -> Text("${b.fileFormat?.uppercase() ?: "이"} 형식은 아직 읽을 수 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            Summary(api, b.id)
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            InfoLine("저자", b.author?.ifBlank { null }?.let { "$it  (서버 데이터라 틀릴 수 있습니다)" })
            InfoLine("📁 위치", listOfNotNull(library, b.seriesName).joinToString(" › ").ifBlank { null })
            InfoLine("형식", b.fileFormat?.uppercase())
            InfoLine("용량", mb(b.fileSize))
            InfoLine("쪽수", b.pageCount?.let { "${it}쪽" })
            InfoLine("올라온 날", metaDate(b.fileMtime))
            InfoLine("파일명", b.fileName)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * Synopsis from /books/{id}. "ok" is shown as is; "suspect" (taken from a Naver shopping search
 * candidate, often another book) stays folded behind a warning; none -> nothing at all.
 */
@Composable
private fun Summary(api: MetaApi, id: Long) {
    var state by remember(id) { mutableStateOf<MetaBook?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    LaunchedEffect(id) {
        state = runCatching { api.book(id) }.getOrNull()
        loading = false
    }
    val text = state?.summary?.trim()?.ifBlank { null }
    when {
        loading -> Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        text == null -> Unit
        else -> {
            val suspect = state?.summaryTrust == "suspect"
            var open by remember(id) { mutableStateOf(!suspect) }
            var expanded by remember(id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                Text("줄거리", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                if (!open) {
                    Text(
                        "⚠️ 다른 책 소개가 잘못 들어갔을 수 있습니다",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) { Text("그래도 보기") }
                } else {
                    if (suspect) {
                        Text(
                            "⚠️ 다른 책 소개일 수 있습니다",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    val long = text.length > 400
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (long && !expanded) 10 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = long) { expanded = !expanded },
                    )
                    if (long) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (expanded) "접기" else "더 보기")
                    }
                }
            }
        }
    }
}
