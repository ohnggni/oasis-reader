package com.oasisreader.app.audio

import com.oasisreader.app.ui.WifiAsk
import kotlinx.coroutines.CoroutineScope
import com.oasisreader.app.data.db.BookEntity
import androidx.compose.runtime.State
import com.oasisreader.app.ui.EmojiText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.ui.RemoteCover
import kotlinx.coroutines.launch

/** One audiobook: cover, description, where you are, its tracks. */
@Composable
internal fun AudiobookDetail(web: BookOasisWeb, item: AudioItem, onPlayer: () -> Unit) {
    val context = LocalContext.current
    var book by remember(item) { mutableStateOf<AudioBook?>(null) }
    var summary by remember(item) { mutableStateOf<String?>(null) }
    val pctState = remember(item) { mutableStateOf(0.0) }
    var pct by pctState
    val completedState = remember(item) { mutableStateOf(false) }
    var completed by completedState
    val scope = rememberCoroutineScope()
    val onShelfState = remember(item) {
        com.oasisreader.app.data.db.AppDatabase.get(context.applicationContext).books()
            .observe(AudioLibraryLink.bookId(web.server.id, item.id ?: -1))
    }.collectAsState(initial = null)
    var error by remember(item) { mutableStateOf<String?>(null) }
    LaunchedEffect(item) {
        try {
            val d = AudioLibraryLink.detail(web, web.server.id, item.id, item.title, item.libraryId)
            summary = d.summary
            pct = d.percent
            completed = d.completed
            book = if (d.book.coverUrl == null && item.cover != null) d.book.copy(coverUrl = web.absolute(item.cover)) else d.book
        } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
    }
    val b = book
    val rowId = AudioLibraryLink.bookId(web.server.id, b?.id ?: item.id ?: -1)
    val trackState = rememberTrackStates(rowId)
    val askNotif = rememberNotificationAsk()
    when {
        error != null -> EmojiText("⚠️ $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
        b == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp)) {
            item { AudiobookHeader(web, b, rowId, onShelfState, pctState, completedState, scope, askNotif, onPlayer) }
            summary?.let { s ->
                item {
                    var more by remember { mutableStateOf(false) }
                    Text(
                        s, style = MaterialTheme.typography.bodySmall, maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp).clickable { more = !more },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            item { HorizontalDivider(thickness = 0.5.dp) }
            itemsIndexed(b.tracks, key = { _, t -> t.id }) { i, t ->
                AudiobookTrackRow(web, b, i, t, rowId, trackState, onShelfState, pctState, completedState, scope, askNotif, onPlayer)
            }
        }
    }
}

/** Cover, title, where you are, 이어 듣기 · 서재+ and receiving. */
@Composable
private fun AudiobookHeader(
    web: BookOasisWeb, b: AudioBook, rowId: String, onShelfState: State<BookEntity?>,
    pctState: State<Double>, completedState: State<Boolean>, scope: CoroutineScope, askNotif: () -> Unit,
    onPlayer: () -> Unit,
) {
    val context = LocalContext.current
    val onShelf by onShelfState
    val pct by pctState
    val completed by completedState
    Row(Modifier.padding(16.dp)) {
        RemoteCover(b.coverUrl, b.title, { u -> web.bytes(u) }, Modifier.width(120.dp).aspectRatio(1f))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            b.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val total = b.tracks.sumOf { it.durationSec }
            Text(
                listOfNotNull("트랙 ${b.tracks.size}개", total.takeIf { it > 0 }?.let { "총 ${hms(it)}" }, pct.takeIf { it > 0 }?.let { "${it.toInt()}% 들음" }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                Button(onClick = {
                    AudioLibraryLink.resume(context, web, b, onShelf)
                    onPlayer()
                }, enabled = b.tracks.isNotEmpty(), contentPadding = pad) {
                    Text(if (b.startTrackId != null) "▶ 이어 듣기" else "▶ 처음부터", maxLines = 1, softWrap = false)
                }
                if (onShelf == null) androidx.compose.material3.OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { AudioLibraryLink.add(context, web, b, pct, completed) }
                            .onSuccess { com.oasisreader.app.ui.Notice.added("서재에 담았습니다: ${b.title}", AudioLibraryLink.bookId(b.serverId, b.id)) }
                            .onFailure { com.oasisreader.app.ui.Notice.show(context, "담지 못했습니다: ${it.message}") }
                    }
                }, contentPadding = pad) { EmojiText("📚 서재+", maxLines = 1, softWrap = false) }
                else EmojiText("📚 서재에 있음", maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterVertically))
            }
            // 오프라인: the tracks on the phone (puts it on the 서재 first)
            val dl = AudioDownloads.progress[rowId]
            val dlErr = AudioDownloads.errors[rowId]
            val kept = onShelf?.let { it.pinned && it.fileBytes > 0 } == true
            dlErr?.let { EmojiText("⚠️ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            when {
                dl != null || AudioDownloads.isPaused(context, rowId) -> Row {
                    if (AudioDownloads.isPaused(context, rowId)) TextButton(onClick = { WifiAsk.run(context) { mobile -> AudioDownloads.resume(context, rowId, mobile) } }) { EmojiText("▶️ 이어 받기") }
                    else TextButton(onClick = { AudioDownloads.pause(context, rowId) }) { EmojiText("⏸️ 받는 중 ${((dl ?: 0f) * 100).toInt()}%") }
                    TextButton(onClick = { AudioDownloads.cancel(context, rowId) }) { EmojiText("✖️ 취소") }
                }
                kept -> EmojiText("⬇️ 폰에 받음 (오프라인으로 들을 수 있음)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                else -> TextButton(onClick = {
                    askNotif()
                    WifiAsk.run(context) { mobile ->
                        scope.launch {
                            runCatching { onShelf ?: AudioLibraryLink.add(context, web, b, pct, completed) }
                                .onSuccess { row ->
                                    AudioDownloads.enqueue(context, row, book = b, mobile = mobile)
                                    com.oasisreader.app.ui.Notice.show(context, "받기를 시작했습니다. 앱을 닫아도 계속 받고, 알림에서 진행을 볼 수 있습니다.")
                                }
                                .onFailure { com.oasisreader.app.ui.Notice.show(context, "받지 못했습니다: ${it.message}") }
                        }
                    }
                }) { Text(if ((onShelf?.fileBytes ?: 0) > 0) "⬇️ 나머지 트랙도 받기" else "⬇️ 폰에 받기 (오프라인)") }
            }
        }
    }
}

@Composable
private fun AudiobookTrackRow(
    web: BookOasisWeb, b: AudioBook, i: Int, t: AudioTrack, rowId: String, trackState: (Int) -> Pair<TrackDl, Float>,
    onShelfState: State<BookEntity?>, pctState: State<Double>, completedState: State<Boolean>, scope: CoroutineScope,
    askNotif: () -> Unit, onPlayer: () -> Unit,
) {
    val context = LocalContext.current
    val onShelf by onShelfState
    val pct by pctState
    val completed by completedState
    val now = AudioPlayback.book?.id == b.id && AudioPlayback.trackIndex == i
    Row(
        Modifier.fillMaxWidth().clickable { AudioPlayback.open(context, web, b, startIndex = i, startSec = 0.0); onPlayer() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (now) "▶" else "${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 28.dp))
        Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            fontWeight = if (now || t.id == b.startTrackId) FontWeight.Bold else FontWeight.Normal)
        if (t.durationSec > 0) Text(hms(t.durationSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val (st, frac) = trackState(t.id)
        TrackDownloadButton(
            st, frac,
            onDownload = {
                askNotif()
                WifiAsk.run(context) { mobile ->
                    scope.launch {
                        runCatching { onShelf ?: AudioLibraryLink.add(context, web, b, pct, completed) }
                            .onSuccess { row -> AudioDownloads.enqueue(context, row, listOf(t.id), book = b, mobile = mobile) }
                            .onFailure { com.oasisreader.app.ui.Notice.show(context, "받지 못했습니다: ${it.message}") }
                    }
                }
            },
            onCancel = { AudioDownloads.dequeueTrack(context, rowId, t.id) },
            onRemove = { scope.launch { AudioDownloads.removeTrack(context, rowId, t.id) } },
        )
    }
}
