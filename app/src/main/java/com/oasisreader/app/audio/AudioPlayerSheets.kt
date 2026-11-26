package com.oasisreader.app.audio

import com.oasisreader.app.ui.OgMenu
import com.oasisreader.app.ui.EmojiText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 속도 · 수면 타이머. */
@Composable
internal fun PlayerChips() {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var sOpen by remember { mutableStateOf(false) }
        Box {
            AssistChip(onClick = { sOpen = true }, label = { Text("속도 ${AudioPlayback.speed}배") })
            OgMenu(sOpen, { sOpen = false }) {
                listOf(0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                    DropdownMenuItem(text = { Text("${s}배") }, leadingIcon = { com.oasisreader.app.ui.CheckMark(s == AudioPlayback.speed) }, onClick = { sOpen = false; AudioPlayback.changeSpeed(context, s) })
                }
            }
        }
        var tOpen by remember { mutableStateOf(false) }
        // the minutes left count down with the player's tick (read only while a timer runs)
        val tickMs = if (AudioPlayback.sleepAt > 0) AudioPlayback.positionMs else 0L
        val left = if (AudioPlayback.sleepAt > 0 && tickMs >= 0) ((AudioPlayback.sleepAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1) else 0
        Box {
            AssistChip(onClick = { tOpen = true }, label = { Text(when { left > 0 -> "🌙 ${left}분"; AudioPlayback.sleepAtTrackEnd -> "🌙 트랙 끝"; else -> "🌙 타이머" }) })
            OgMenu(tOpen, { tOpen = false }) {
                listOf(15, 30, 45, 60, 90).forEach { m -> DropdownMenuItem(text = { Text("${m}분") }, onClick = { tOpen = false; AudioPlayback.sleepIn(m) }) }
                DropdownMenuItem(text = { Text("이 트랙 끝에서") }, onClick = { tOpen = false; AudioPlayback.sleepAtEndOfTrack() })
                DropdownMenuItem(text = { Text("타이머 끄기") }, onClick = { tOpen = false; AudioPlayback.sleepIn(0) })
            }
        }
        // 🔖 책갈피 (TODO 36): the place playing now, or the stretch just heard, with a note
        val b = AudioPlayback.book
        if (b != null && b.rowId().isNotEmpty()) {
            var mOpen by remember { mutableStateOf(false) }
            var listOpen by remember { mutableStateOf(false) }
            var added by remember { mutableStateOf<com.oasisreader.app.data.db.AudioMarkEntity?>(null) }
            fun mark(backSec: Double) {
                com.oasisreader.app.ui.ShelfActions.scope.launch {
                    val m = AudioMarks.add(context, backSec)
                    if (m == null) com.oasisreader.app.ui.Notice.show(context, "책갈피를 남기지 못했습니다.") else added = m
                }
            }
            Box {
                AssistChip(onClick = { mOpen = true }, label = { EmojiText("🔖 책갈피") })
                OgMenu(mOpen, { mOpen = false }) {
                    DropdownMenuItem(text = { EmojiText("🔖 지금 자리") }, onClick = { mOpen = false; mark(0.0) })
                    DropdownMenuItem(text = { Text("⏪ 30초 전") }, onClick = { mOpen = false; mark(30.0) })
                    DropdownMenuItem(text = { EmojiText("📋 책갈피 목록") }, onClick = { mOpen = false; listOpen = true })
                }
            }
            // just added: a note can be typed right away (closing keeps the mark)
            added?.let { m -> AudioMarkDialog(m, b.title, onPlay = null, onDismiss = { added = null }) }
            if (listOpen) AudioMarksDialog(b.rowId(), b.title) { listOpen = false }
        }
    }
}

/** The 장 (or track) list in a popup: the one playing is marked and in view, a tap jumps there (TODO 35). */
@Composable
internal fun PlacePicker(b: AudioBook, onClose: () -> Unit) {
    val byChapter = b.chapters.isNotEmpty()
    val cur by remember(b) { derivedStateOf { if (byChapter) AudioPlayback.chapterIndex() else AudioPlayback.trackIndex } }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (cur - 2).coerceAtLeast(0))
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                Text(
                    if (byChapter) "장 ${b.chapters.size}개" else "트랙 ${b.tracks.size}개",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
                )
                HorizontalDivider(thickness = 0.5.dp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), state = listState) {
                    if (byChapter) itemsIndexed(b.chapters) { i, c ->
                        PlaceRow(i, c.title, hms(c.startSec), i == cur) { AudioPlayback.goChapter(i); onClose() }
                    } else itemsIndexed(b.tracks) { i, tr ->
                        PlaceRow(i, tr.title, if (tr.durationSec > 0) hms(tr.durationSec) else "", i == cur) { AudioPlayback.track(i); onClose() }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("닫기") }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(i: Int, title: String, time: String, now: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (now) "▶" else "${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 30.dp))
        Text(title.ifBlank { "${i + 1}" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            fontWeight = if (now) FontWeight.Bold else FontWeight.Normal)
        if (time.isNotEmpty()) Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Which place to go on from, when the server's place looks like a player that started over
 * elsewhere (AudioProgressSync.decide → ASK). Shown over any screen; the book waits, paused.
 */
@Composable
fun PlaceChoiceDialog() {
    val c = AudioPlayback.placeChoice ?: return
    val b = AudioPlayback.book
    fun where(idx: Int, sec: Double): String {
        val t = b?.tracks?.getOrNull(idx)?.title?.takeIf { it.isNotBlank() }
        return "${idx + 1}번 트랙 ${hms(sec)}" + (t?.let { " · $it" } ?: "")
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { AudioPlayback.placeChoice = null },
        title = { Text("어디서부터 들을까요?") },
        text = {
            Column {
                Text(
                    "북오아시스에 저장된 위치가 책의 맨 앞으로 바뀌어 있습니다. 웹이나 다른 기기에서 처음부터 재생된 것 같습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("📱 이 폰: ${where(c.phoneIdx, c.phoneSec)}", style = MaterialTheme.typography.bodyMedium)
                EmojiText("☁️ 북오아시스: ${where(c.serverIdx, c.serverSec)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { AudioPlayback.choosePlace(useServer = false) }) { Text("이 폰 위치") } },
        dismissButton = { TextButton(onClick = { AudioPlayback.choosePlace(useServer = true) }) { Text("북오아시스 위치") } },
    )
}
