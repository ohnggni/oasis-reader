package com.oasisreader.app.reader

import android.speech.tts.Voice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Where the floating player was dragged to (kept while the app runs). */
private var barOffset = androidx.compose.ui.geometry.Offset.Zero

/**
 * The small floating player shown while reading aloud: sentence ⏮ ⏯ ⏭, speed, sleep timer,
 * voice, stop. Slightly see-through; drag it (anywhere but a button) to move it out of the way.
 */
@Composable
fun ReadAloudBar(ra: ReadAloud, fill: Color, onVoice: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var off by remember { mutableStateOf(barOffset) }
    Surface(
        modifier = modifier
            .offset { androidx.compose.ui.unit.IntOffset(off.x.toInt(), off.y.toInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    off += drag; barOffset = off
                }
            },
        shape = RoundedCornerShape(24.dp),
        color = fill.copy(alpha = 0.86f),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("≡", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.padding(start = 8.dp, end = 2.dp))
            BarButton("⏮", "이전 문장") { ra.skip(-1) }
            BarButton(if (ra.playing) "⏸" else "▶", if (ra.playing) "일시정지" else "계속 읽기") { ra.toggle() }
            BarButton("⏭", "다음 문장") { ra.skip(1) }
            var speedOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { speedOpen = true }) { Text(ReadAloud.rateLabel(ra.rate), style = MaterialTheme.typography.labelLarge) }
                DropdownMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                    ReadAloud.RATES.forEach { r ->
                        DropdownMenuItem(
                            text = { Text((if (r == ra.rate) "✓ " else "    ") + ReadAloud.rateLabel(r)) },
                            onClick = { speedOpen = false; ra.changeRate(r) },
                        )
                    }
                }
            }
            SleepButton(ra)
            BarButton("🎙", "목소리", onVoice)
            BarButton("✕", "소리 내 읽기 끝내기", onClose)
        }
    }
}

@Composable
private fun SleepButton(ra: ReadAloud) {
    var open by remember { mutableStateOf(false) }
    // minutes left, refreshed every 20 s while a timer runs
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ra.sleepAt) { while (ra.sleepAt > 0) { now = System.currentTimeMillis(); delay(20_000) } }
    val left = if (ra.sleepAt > 0) ((ra.sleepAt - now + 59_999) / 60_000).toInt().coerceAtLeast(1) else 0
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "수면 타이머" }) {
            Text(
                when { left > 0 -> "🌙${left}분"; ra.sleepAtChapterEnd -> "🌙장"; else -> "🌙" },
                style = MaterialTheme.typography.labelLarge,
                color = if (left > 0 || ra.sleepAtChapterEnd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("수면 타이머", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            ReadAloud.SLEEP.forEach { m ->
                DropdownMenuItem(text = { Text("${m}분 뒤 멈춤") }, onClick = { open = false; ra.sleepIn(m) })
            }
            DropdownMenuItem(text = { Text("이 장 끝에서 멈춤") }, onClick = { open = false; ra.sleepAtEndOfChapter() })
            if (left > 0 || ra.sleepAtChapterEnd) DropdownMenuItem(text = { Text("타이머 끄기") }, onClick = { open = false; ra.sleepIn(0) })
        }
    }
}

@Composable
private fun BarButton(glyph: String, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp).semantics { contentDescription = label }) {
        Text(glyph, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Engine (Samsung, Google …), voice with a sample, and pitch. */
@Composable
fun VoiceDialog(ra: ReadAloud, onDismiss: () -> Unit) {
    var rev by remember { mutableIntStateOf(0) }           // engine changed / voices loaded
    var all by remember { mutableStateOf(false) }
    // a new engine needs a moment to list its voices
    LaunchedEffect(ra.available, rev) { if (ra.available == null) { delay(400); rev++ } }
    val engines = remember(rev, ra.available) { ra.engines() }
    val engine = remember(rev, ra.available) { ra.engine() }
    val voices = remember(rev, ra.available, all) { ra.voices(all) }
    var picked by remember(rev) { mutableStateOf(ra.voiceName()) }
    val sample = "안녕하세요. 이 목소리로 책을 읽어 드립니다."

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("목소리") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (engines.size > 1) {
                    Text("음성 엔진", style = MaterialTheme.typography.labelLarge)
                    engines.forEach { (pkg, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { ra.setEngine(pkg); picked = null; rev++ },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = pkg == engine, onClick = { ra.setEngine(pkg); picked = null; rev++ })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("목소리", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    FilterChip(selected = all, onClick = { all = !all }, label = { Text("다른 언어도") })
                }
                when {
                    ra.available == null -> Text("엔진을 여는 중…", style = MaterialTheme.typography.bodySmall)
                    voices.isEmpty() -> Text(
                        "이 엔진에 맞는 목소리가 없습니다. 휴대폰 설정 › 텍스트 음성 변환에서 음성 데이터를 받거나 다른 엔진을 고르세요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        item {
                            VoiceRow("엔진 기본 목소리", null, picked == null, onPick = { picked = null; ra.setVoice(null) }, onPlay = { ra.preview(null, sample) })
                        }
                        items(voices, key = { it.name }) { v ->
                            VoiceRow(voiceLabel(v, voices), voiceNote(v), picked == v.name,
                                onPick = { picked = v.name; ra.setVoice(v) }, onPlay = { ra.preview(v, sample) })
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("음높이", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                    ReadAloud.PITCHES.forEach { (p, label) ->
                        FilterChip(selected = ra.pitch == p, onClick = { ra.changePitch(p) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun VoiceRow(label: String, note: String?, selected: Boolean, onPick: () -> Unit, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onPick)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onPlay) { Text("▶ 듣기") }
    }
}

/** "한국어 3" style names (engines' own names are codes like ko-KR-SMTl03 / ko-kr-x-ism-local). */
private fun voiceLabel(v: Voice, list: List<Voice>): String {
    val lang = v.locale.getDisplayLanguage(java.util.Locale.KOREAN).ifBlank { v.locale.toString() }
    val same = list.filter { it.locale.language == v.locale.language }
    val n = same.indexOf(v) + 1
    val country = v.locale.getDisplayCountry(java.util.Locale.KOREAN)
    return "$lang ${n}" + (if (country.isNotBlank() && v.locale.language != "ko") " ($country)" else "")
}

private fun voiceNote(v: Voice): String = listOfNotNull(
    when {
        v.quality >= Voice.QUALITY_VERY_HIGH -> "아주 좋은 음질"
        v.quality >= Voice.QUALITY_HIGH -> "좋은 음질"
        else -> null
    },
    if (v.isNetworkConnectionRequired) "인터넷 필요" else null,
    v.name,
).joinToString(" · ")
