package com.oasisreader.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.ui.MiniChip
import com.oasisreader.app.ui.MiniChipGap
import com.oasisreader.app.ui.TopBar
import kotlinx.coroutines.launch

/** Where one track stands on the phone. */
enum class TrackDl { KEPT, RUNNING, WAITING, NONE }

/** The state of every track of [bookId] (read again whenever the downloads change). */
@Composable
fun rememberTrackStates(bookId: String): (Int) -> Pair<TrackDl, Float> {
    val context = LocalContext.current
    val v = AudioDownloads.version
    val running = AudioDownloads.current[bookId]
    val kept = remember(bookId, v, running?.first) { AudioDownloads.keptTracks(context, bookId) }
    val wanted = remember(bookId, v) { AudioDownloads.wantedTracks(context, bookId) }
    return { id ->
        when {
            id in kept -> TrackDl.KEPT to 1f
            running != null && running.first == id -> TrackDl.RUNNING to running.second
            id in wanted || (AudioDownloads.ALL in wanted) -> TrackDl.WAITING to 0f
            else -> TrackDl.NONE to 0f
        }
    }
}

/**
 * Before the first download: ask once for the notification permission (Android 13+), so the
 * progress notification is seen. Downloads work without it too.
 */
@Composable
fun rememberNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(context)) {
            val prefs = context.getSharedPreferences("audio", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("asked_notifications", false)) {
                prefs.edit().putBoolean("asked_notifications", true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

private fun notificationsAllowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** The small control at the end of a track row: ⬇ to receive, ⏳ waiting, 45% coming, ✅ kept. */
@Composable
fun TrackDownloadButton(state: TrackDl, fraction: Float, onDownload: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val (label, desc) = when (state) {
        TrackDl.KEPT -> "✅" to "폰에 받음 (누르면 지우기)"
        TrackDl.RUNNING -> "${(fraction * 100).toInt()}%" to "받는 중 (누르면 멈춤)"
        TrackDl.WAITING -> "⏳" to "받기 대기 중 (누르면 취소)"
        TrackDl.NONE -> "⬇️" to "이 트랙 받기"
    }
    Box(
        Modifier.widthIn(min = 44.dp).height(36.dp).clip(RoundedCornerShape(8.dp)).clickable {
            when (state) {
                TrackDl.KEPT -> confirm = true
                TrackDl.RUNNING, TrackDl.WAITING -> onCancel()
                TrackDl.NONE -> onDownload()
            }
        }.semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = if (state == TrackDl.RUNNING) 12.sp else 16.sp, color = MaterialTheme.colorScheme.primary)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        text = { Text("이 트랙을 폰에서 지울까요? 지우면 스트리밍으로 듣습니다.") },
        confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("지우기", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("그대로 두기") } },
    )
}

/** ⬇️ 받기 관리: what is on the phone, what is coming, what failed — by book, then by track. */
@Composable
fun DownloadsScreen(onBack: () -> Unit, onStorage: () -> Unit = {}, onOpen: (String) -> Unit = {}) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val askNotif = rememberNotificationAsk()
    val v = AudioDownloads.version
    val rows by remember { AppDatabase.get(app).books().observeAll() }.collectAsState(initial = null)
    val ids = remember(v, rows) { AudioDownloads.bookIds(app) }
    val allAudio = rows.orEmpty().filter { it.format == AudioLibraryLink.FORMAT && it.id in ids }
        .map { it to bookState(app, it.id) }
        .sortedWith(compareBy({ it.second.order }, { it.first.title }))
    // 받기 관리 shows what is being received (the same for books and audiobooks): an audiobook that
    // is fully on the phone leaves this list — it is in 📦 보관함 관리 — and shows in ✅ 최근 받음 for a day
    val books = allAudio.filter { (_, st) -> !st.complete }
    val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
    val recent: List<Pair<BookEntity, Long>> = remember(rows, v) {
        val audio = allAudio.filter { it.second.complete }.mapNotNull { (b, _) ->
            val at = AudioDownloads.dir(app, b.id).listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
            (b to at).takeIf { at > dayAgo }
        }
        val other = rows.orEmpty().filter { it.format != AudioLibraryLink.FORMAT && it.sourceUrl != null && it.addedAt > dayAgo }.map { it to it.addedAt }
        (audio + other).sortedByDescending { it.second }
    }
    val direct by com.oasisreader.app.data.Downloads.direct.collectAsState()
    val bv = com.oasisreader.app.data.BookQueue.version
    val jobs = remember(bv) { com.oasisreader.app.data.BookQueue.all(app) }
    val dlProgress by com.oasisreader.app.data.Downloads.progress.collectAsState()
    val busy = books.any { it.second.pending && !it.second.paused } || jobs.any { it.state == com.oasisreader.app.data.BookQueue.State.WAITING }
    var notifOk by remember { mutableStateOf(notificationsAllowed(context)) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("⬇️ 받기 관리", onBack = onBack) {
            if (busy) com.oasisreader.app.ui.EmojiButton("⏸️", "모두 멈춤") {
                AudioDownloads.pauseAll(context); com.oasisreader.app.data.BookQueue.pauseAll(context)
            }
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                val bytes = allAudio.sumOf { AudioDownloads.keptBytes(app, it.first.id) }
                val free = app.filesDir.usableSpace
                Text(
                    "받는 중·대기 ${jobs.size + direct.size + books.size}개 · 폰에 받은 오디오북 ${allAudio.count { it.second.kept > 0 }}권 (${gb(bytes)}) · 남은 공간 ${gb(free)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    "앱을 닫아도 받기는 계속됩니다. ⏸️ 일시정지 · ▶️ 이어 받기 · ✖️ 받기 취소. 받은 파일은 이 앱 안에만 있습니다(오디오북은 이 폰에서만 풀리게 암호화).",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                var wifi by remember { mutableStateOf(AudioDownloadWorker.wifiOnly(context)) }
                Row(
                    Modifier.fillMaxWidth().clickable { wifi = !wifi; AudioDownloads.setWifiOnly(context, wifi); com.oasisreader.app.data.BookQueue.rekick(context) }.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("📶 와이파이에서만 받기", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (wifi) "모바일 데이터에서는 기다렸다가 와이파이에 연결되면 받습니다" else "모바일 데이터로도 받습니다",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(checked = wifi, onCheckedChange = { wifi = it; AudioDownloads.setWifiOnly(context, it); com.oasisreader.app.data.BookQueue.rekick(context) })
                }
                if (!notifOk && Build.VERSION.SDK_INT >= 33) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔔 알림을 켜면 받는 동안 진행 상황이 보입니다", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("켜기") }
                }
                TextButton(onClick = onStorage, modifier = Modifier.padding(horizontal = 4.dp)) { Text("📦 다 받은 책·오디오북은 보관함 관리에서 →") }
                HorizontalDivider(thickness = 0.5.dp)
            }
            // ⚡ 바로 읽기 (and 다음 권): received straight away, not queued
            if (direct.isNotEmpty()) item {
                Text("⚡ 바로 읽기", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            }
            items(direct.entries.toList(), key = { "direct_" + it.key }) { (key, title) ->
                val f = dlProgress[key]
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (f == null || f < 0f) "⬇️ 받는 중" else "⬇️ 받는 중 ${(f * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (f == null || f < 0f) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            // 📖 책 · 만화 · PDF being received or waiting
            if (jobs.isNotEmpty()) item {
                Text("📖 책 · 만화 · PDF", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            }
            items(jobs, key = { "job_" + it.key }) { job -> BookJobRow(job, dlProgress[job.key]) }
            if (books.isNotEmpty()) item {
                Text("🎧 오디오북", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            }
            if (books.isEmpty() && jobs.isEmpty() && direct.isEmpty()) item {
                Text(
                    "지금 받는 것이 없습니다.\n책은 서버 목록에서 ⬇️ 서재에 담기, 오디오북은 서재에서 길게 눌러 \"⬇️ 폰에 받기\"나 상세 화면에서 받으세요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            items(books, key = { it.first.id }) { (row, st) ->
                BookCard(row, st, onAll = { askNotif(); AudioDownloads.enqueue(context, row) }, onRemove = { scope.launch { AudioDownloads.remove(context, row) } })
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            // ✅ 최근 받음: finished in the last day (books and audiobooks), folded; tap to open
            if (recent.isNotEmpty()) item {
                var openRecent by rememberSaveable { mutableStateOf(false) }
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { openRecent = !openRecent }.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("✅ 최근 받음 ${recent.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (openRecent) "🔼" else "🔽", fontSize = 13.sp)
                    }
                    if (openRecent) {
                        val fmt = remember { java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA) }
                        recent.forEach { (b, at) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpen(b.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    (if (b.format == AudioLibraryLink.FORMAT) "🎧 " else "📖 ") + b.title,
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                Text(fmt.format(java.util.Date(at)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A book in 받기 관리. [order]: coming, waiting, failed, partly kept, kept. */
private data class BookDl(val tracks: Int, val kept: Int, val pending: Boolean, val running: Boolean, val error: String?, val paused: Boolean = false) {
    /** all tracks on the phone and nothing more to do */
    val complete get() = tracks > 0 && kept >= tracks && !pending && !running && error == null
    val order get() = when { running -> 0; pending && !paused -> 1; paused -> 2; error != null -> 3; kept < tracks -> 4; else -> 5 }
}

private fun bookState(app: Context, id: String): BookDl {
    val tracks = AudioDownloads.trackList(app, id)?.size ?: 0
    val kept = AudioDownloads.keptTracks(app, id).size
    val pending = AudioDownloads.wantedTracks(app, id).isNotEmpty()
    val q = AudioDownloads.readQueue(app, id)
    return BookDl(tracks, kept, pending, AudioDownloads.current[id] != null, AudioDownloads.errors[id] ?: q.error, paused = q.paused && pending)
}

@Composable
private fun BookCard(row: BookEntity, st: BookDl, onAll: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    var open by rememberSaveable(row.id) { mutableStateOf(st.running) }
    var confirm by remember { mutableStateOf(false) }
    val overall = AudioDownloads.progress[row.id]
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(row.id)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val bytes = AudioDownloads.keptBytes(context, row.id)
                val status = when {
                    st.running -> "⬇️ 받는 중 ${((overall ?: 0f) * 100).toInt()}%"
                    st.paused -> "⏸️ 일시정지"
                    st.pending -> if (AudioDownloadWorker.wifiOnly(context)) "⏳ 받기 대기 (와이파이 연결되면 시작)" else "⏳ 받기 대기 (연결되면 시작)"
                    st.error != null -> "⚠️ ${st.error}"
                    st.tracks > 0 && st.kept >= st.tracks -> "✅ 다 받음"
                    st.kept > 0 -> "◐ 일부 받음"
                    else -> "☁️ 받지 않음"
                }
                Text(
                    listOfNotNull(status, st.tracks.takeIf { it > 0 }?.let { "트랙 ${st.kept}/$it" }, bytes.takeIf { it > 0 }?.let { mb(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (st.error != null && !st.pending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(if (open) "🔼" else "🔽", fontSize = 13.sp)
        }
        if (st.running || (st.pending && !st.paused)) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { overall ?: 0f }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(MiniChipGap)) {
            when {
                st.paused -> MiniChip("▶️", onClick = { AudioDownloads.resume(context, row.id) }, description = "이어 받기")
                st.pending -> MiniChip("⏸️", onClick = { AudioDownloads.pause(context, row.id) }, description = "일시정지")
                st.tracks == 0 || st.kept < st.tracks -> MiniChip(if (st.error != null) "🔁" else "⬇️", onClick = onAll, description = if (st.error != null) "다시 받기" else "전부 받기")
            }
            if (st.pending) MiniChip("✖️", onClick = { AudioDownloads.cancel(context, row.id) }, description = "받기 취소")
            if (st.kept > 0 && !st.pending) MiniChip("🗑️", onClick = { confirm = true }, description = "받은 트랙 지우기")
        }
        if (open) {
            val tracks = AudioDownloads.trackList(context, row.id).orEmpty()
            if (tracks.isEmpty()) Text("트랙 목록은 받기를 시작하면 나옵니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            val stateOf = rememberTrackStates(row.id)
            val scope = rememberCoroutineScope()
            val askNotif = rememberNotificationAsk()
            tracks.forEachIndexed { i, t ->
                val (s, f) = stateOf(t.id)
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 26.dp))
                    Text(t.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (t.durationSec > 0) Text(hms(t.durationSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TrackDownloadButton(
                        s, f,
                        onDownload = { askNotif(); AudioDownloads.enqueue(context, row, listOf(t.id)) },
                        onCancel = { AudioDownloads.dequeueTrack(context, row.id, t.id) },
                        onRemove = { scope.launch { AudioDownloads.removeTrack(context, row.id, t.id) } },
                    )
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("폰에서 지우기") },
        text = { Text("「${row.title}」의 받은 트랙을 모두 지웁니다. 서재와 들은 위치는 그대로이고, 스트리밍으로 들을 수 있습니다.") },
        confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("지우기", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
    )
}

@Composable
private fun Cover(bookId: String) {
    val context = LocalContext.current
    val bmp = remember(bookId) {
        runCatching { android.graphics.BitmapFactory.decodeFile(BookStore.get(context.applicationContext).coverFile(bookId).path)?.asImageBitmap() }.getOrNull()
    }
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(width = 44.dp, height = 60.dp)) {
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(contentAlignment = Alignment.Center) { Text("🎧", fontSize = 20.sp) }
    }
}

private fun mb(bytes: Long) = "%.0f MB".format(bytes / 1048576.0)
private fun gb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "%.0f MB".format(bytes / 1048576.0)


/** A book · comic · PDF in the queue: title, how far, ⏸️/▶️ and ✖️. */
@Composable
private fun BookJobRow(job: com.oasisreader.app.data.BookQueue.Job, progress: Float?) {
    val context = LocalContext.current
    val part = remember(job.key, progress) { com.oasisreader.app.data.BookQueue.partFile(context, job.key).let { if (it.isFile) it.length() else 0L } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val status = when {
                    job.state == com.oasisreader.app.data.BookQueue.State.PAUSED -> "⏸️ 일시정지" + (if (part > 0) " · ${mb(part)} 받음" else "")
                    job.state == com.oasisreader.app.data.BookQueue.State.FAILED -> "⚠️ ${job.error ?: "받지 못했습니다"}"
                    progress != null && progress >= 0f -> "⬇️ 받는 중 ${(progress * 100).toInt()}%"
                    progress != null -> "⬇️ 받는 중" + (if (part > 0) " · ${mb(part)}" else "")
                    else -> "⏳ 받기 대기" + (job.error?.let { " · $it" } ?: "")
                }
                Text(status, style = MaterialTheme.typography.labelMedium,
                    color = if (job.state == com.oasisreader.app.data.BookQueue.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MiniChipGap)) {
                if (job.state == com.oasisreader.app.data.BookQueue.State.WAITING) MiniChip("⏸️", onClick = { com.oasisreader.app.data.BookQueue.pause(context, job.key) }, description = "일시정지")
                else MiniChip(if (job.state == com.oasisreader.app.data.BookQueue.State.FAILED) "🔁" else "▶️", onClick = { com.oasisreader.app.data.BookQueue.resume(context, job.key) }, description = "이어 받기")
                MiniChip("✖️", onClick = { com.oasisreader.app.data.BookQueue.cancel(context, job.key) }, description = "받기 취소")
            }
        }
        if (job.state == com.oasisreader.app.data.BookQueue.State.WAITING && progress != null) {
            Spacer(Modifier.height(4.dp))
            if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}
