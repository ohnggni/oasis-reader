package com.oasisreader.app.audio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.AudioMarkEntity
import com.oasisreader.app.data.db.forgetRemovedIfEmpty
import com.oasisreader.app.ui.CountLine
import com.oasisreader.app.ui.MiniChip
import com.oasisreader.app.ui.MiniChipGap
import com.oasisreader.app.ui.Notice
import com.oasisreader.app.ui.ShelfActions
import kotlinx.coroutines.launch

/** The stretch a 책갈피 covers: [backSec] before [posSec] (0 = just the place). Start and length. */
fun markSpan(posSec: Double, backSec: Double): Pair<Double, Double> {
    val pos = posSec.coerceAtLeast(0.0)
    val start = (pos - backSec.coerceAtLeast(0.0)).coerceAtLeast(0.0)
    return start to (pos - start)
}

/** "3. 제2장 · 12:34" or "3. 제2장 · 12:04~12:34". */
fun markLabel(track: Int, trackTitle: String, startSec: Double, lengthSec: Double): String =
    "${track + 1}." + (trackTitle.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") + " · " + hms(startSec) +
        if (lengthSec >= 1.0) "~" + hms(startSec + lengthSec) else ""

/**
 * 오디오북 책갈피 (TODO 36): an audiobook has no words to highlight, so what is kept is the place
 * (track + seconds, or the stretch just heard) with a note.
 */
object AudioMarks {
    fun label(m: AudioMarkEntity) = markLabel(m.track, m.trackTitle, m.startSec, m.lengthSec)

    /** One mark as a line of text (copying, sharing). */
    fun line(m: AudioMarkEntity): String = "[" + label(m) + "]" + (m.note?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")

    /** Marks the place playing now ([backSec] > 0: the stretch just heard). Null when no book is loaded. */
    suspend fun add(context: Context, backSec: Double): AudioMarkEntity? {
        val b = AudioPlayback.book ?: return null
        val bookId = b.rowId().ifEmpty { return null }
        val track = AudioPlayback.trackIndex
        val (start, length) = markSpan(AudioPlayback.positionMs / 1000.0, backSec)
        // the 장 inside the track when the book has them (one long m4b), else the track's name
        val title = b.chapters.getOrNull(AudioPlayback.chapterAt(b, track, start))?.title ?: b.tracks.getOrNull(track)?.title.orEmpty()
        val m = AudioMarkEntity(
            id = java.util.UUID.randomUUID().toString(), bookId = bookId, track = track,
            startSec = start, lengthSec = length, trackTitle = title, createdAt = System.currentTimeMillis(),
        )
        AppDatabase.get(context.applicationContext).audioMarks().upsert(m)
        return m
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("책갈피", text))
        Notice.show(context, "복사했습니다.")
    }
}

/** One 책갈피: its note, ▶ listen from there, copy, delete. */
@Composable
fun AudioMarkDialog(m: AudioMarkEntity, title: String, onPlay: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val db = AppDatabase.get(context.applicationContext)
    var note by remember(m.id) { mutableStateOf(m.note.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun close() {
        val n = note.trim().ifEmpty { null }
        if (n != m.note) ShelfActions.scope.launch { db.audioMarks().upsert(m.copy(note = n)) }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { close() },
        title = { Text("📌 " + AudioMarks.label(m), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, label = { Text("📝 메모 (안 적어도 됩니다)") }, maxLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(MiniChipGap)) {
                    if (onPlay != null) MiniChip("▶ 여기부터 듣기", onClick = { close(); onPlay() })
                    MiniChip("📋 복사", onClick = { AudioMarks.copy(context, "「$title」 " + AudioMarks.line(m.copy(note = note.trim().ifEmpty { null }))) })
                    MiniChip("🗑️ 지우기", onClick = { confirmDelete = true })
                }
            }
        },
        confirmButton = { TextButton(onClick = { close() }) { Text(if (note.trim() != m.note.orEmpty()) "메모 저장" else "닫기") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        text = { Text("이 책갈피를 지울까요?") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                ShelfActions.scope.launch { db.audioMarks().delete(m.id); db.forgetRemovedIfEmpty(m.bookId) }
                onDismiss()
            }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
    )
}

@Composable
private fun MarkRow(m: AudioMarkEntity, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("📌 " + AudioMarks.label(m), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        m.note?.takeIf { it.isNotBlank() }?.let {
            Text("📝 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The 책갈피 of the book in the player: a tap opens one (note, ▶ listen from there). */
@Composable
fun AudioMarksDialog(bookId: String, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val db = AppDatabase.get(context.applicationContext)
    val marks by remember(bookId) { db.audioMarks().observeFor(bookId) }.collectAsState(initial = emptyList())
    var picked by remember { mutableStateOf<AudioMarkEntity?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📌 책갈피 ${marks.size}개", style = MaterialTheme.typography.titleSmall) },
        text = {
            if (marks.isEmpty()) Text("아직 책갈피가 없습니다.\n듣다가 📌 책갈피를 누르면 그 자리가 남습니다.")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(marks, key = { it.id }) { m -> MarkRow(m) { picked = m } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = {
            if (marks.isNotEmpty()) TextButton(onClick = { AudioMarks.copy(context, "「$title」\n\n" + marks.joinToString("\n") { AudioMarks.line(it) }) }) { Text("📋 모두 복사") }
        },
    )
    picked?.let { m ->
        AudioMarkDialog(m, title, onPlay = { AudioPlayback.goTo(m.track, m.startSec); onDismiss() }, onDismiss = { picked = null })
    }
}

/** 하이라이트 화면의 🎧 오디오북 책갈피: every book's marks, by book (the newest first). */
@Composable
fun AudioMarksPanel(marks: List<AudioMarkEntity>, query: String, titleOf: (String) -> String, onShelf: (String) -> Boolean, onPlay: (AudioMarkEntity) -> Unit) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<AudioMarkEntity?>(null) }
    val groups = remember(marks, query) {
        val q = query.trim()
        marks.filter { m -> q.isEmpty() || (m.note?.contains(q, true) == true) || m.trackTitle.contains(q, true) || titleOf(m.bookId).contains(q, true) }
            .groupBy { it.bookId }.entries.sortedByDescending { e -> e.value.maxOf { it.createdAt } }
            .map { it.key to it.value.sortedWith(compareBy<AudioMarkEntity> { m -> m.track }.thenBy { m -> m.startSec }) }
    }
    CountLine("📌 ${groups.sumOf { it.second.size }}개 · 오디오북 ${groups.size}권")
    if (groups.isEmpty()) Text(
        "오디오북 책갈피가 없습니다.\n오디오북을 듣다가 재생 화면의 📌 책갈피를 누르면 그 자리와 메모가 여기에 모입니다.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
    )
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        groups.forEach { (bookId, list) ->
            item(key = "ah_$bookId") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
                    Text(
                        (if (onShelf(bookId)) "🎧 " else "🗑️ ") + titleOf(bookId),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MiniChip("📋 모두 복사", onClick = { AudioMarks.copy(context, "「${titleOf(bookId)}」\n\n" + list.joinToString("\n") { AudioMarks.line(it) }) })
                }
            }
            items(list, key = { it.id }) { m -> MarkRow(m) { picked = m } }
        }
    }
    picked?.let { m ->
        AudioMarkDialog(m, titleOf(m.bookId), onPlay = if (onShelf(m.bookId)) ({ onPlay(m) }) else null, onDismiss = { picked = null })
    }
}
