package com.oasisreader.app.reader

import com.oasisreader.app.ui.OgMenu

import com.oasisreader.app.ui.EmojiText
import android.speech.tts.Voice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
            DragGrip(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            BarButton("⏮", "이전 문장") { ra.skip(-1) }
            BarButton(if (ra.playing) "⏸" else "▶", if (ra.playing) "일시정지" else "계속 읽기") { ra.toggle() }
            BarButton("⏭", "다음 문장") { ra.skip(1) }
            var speedOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { speedOpen = true }) { Text(ReadAloud.rateLabel(ra.rate), style = MaterialTheme.typography.labelLarge) }
                OgMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                    ReadAloud.RATES.forEach { r ->
                        DropdownMenuItem(
                            text = { Text(ReadAloud.rateLabel(r)) },
                            leadingIcon = { com.oasisreader.app.ui.CheckMark(r == ra.rate) },
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
        OgMenu(expanded = open, onDismissRequest = { open = false }) {
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
        com.oasisreader.app.ui.EmojiText(glyph, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
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
    // back from the store / the AI voice app: pick it up (new engine, or new voices in it)
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (AiVoice.takeReturn(ctx) && ra.engines().any { it.first == AiVoice.PKG }) {
                if (ra.engine() != AiVoice.PKG) ra.setEngine(AiVoice.PKG) else ra.reload()
            }
            rev++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // where the voice comes from: the phone's engine, or Azure (only one shows at a time)
    var azureMode by remember { mutableStateOf(AzureVoice.on(ctx)) }
    var setup by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("목소리") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !azureMode, modifier = Modifier.weight(1f),
                        onClick = { if (azureMode) { AzureVoice.setOn(ctx, false); ra.azureChanged(); azureMode = false } },
                        label = { Text("휴대폰 목소리", maxLines = 1) },
                    )
                    FilterChip(
                        selected = azureMode, modifier = Modifier.weight(1f),
                        onClick = {
                            if (!AzureVoice.hasKey(ctx)) setup = true
                            else if (!azureMode) { AzureVoice.setOn(ctx, true); ra.azureChanged(); azureMode = true }
                        },
                        label = { Text("Azure 음성", maxLines = 1) },
                    )
                }

                if (azureMode) AzureVoicePanel(ra, onSetup = { setup = true })
                else {
                    // the AI voice card only while there is something to do (install / use / get voices)
                    val aiReady = engine == AiVoice.PKG && ra.available == true && voices.isNotEmpty()
                    if (!aiReady) AiVoiceCard(ra, engines, engine, voices.size, onChanged = { picked = null; rev++ })
                    if (engines.size > 1) {
                        Text("엔진", style = MaterialTheme.typography.labelLarge)
                        ChipFlow {
                            engines.forEach { (pkg, label) ->
                                FilterChip(
                                    selected = pkg == engine,
                                    onClick = { if (pkg != engine) { ra.setEngine(pkg); picked = null; rev++ } },
                                    label = { Text(if (pkg == AiVoice.PKG) "$label (AI)" else label, maxLines = 1) },
                                )
                            }
                        }
                    }
                    Text("목소리 · 누르면 골라서 들려줍니다", style = MaterialTheme.typography.labelLarge)
                    when {
                        ra.available == null -> Text("엔진을 여는 중…", style = MaterialTheme.typography.bodySmall)
                        voices.isEmpty() -> Text(
                            "이 엔진에 맞는 목소리가 없습니다. 휴대폰 설정 › 텍스트 음성 변환에서 음성 데이터를 받거나 다른 엔진을 고르세요.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else -> ChipFlow {
                            FilterChip(selected = picked == null, onClick = { picked = null; ra.setVoice(null); if (!ra.playing) ra.preview(null, sample) }, label = { Text("기본") })
                            voices.forEach { v ->
                                FilterChip(
                                    selected = picked == v.name,
                                    onClick = { picked = v.name; ra.setVoice(v); if (!ra.playing) ra.preview(v, sample) },
                                    label = { Text(voiceLabel(v, voices) + if (v.isNetworkConnectionRequired) " ☁" else "", maxLines = 1) },
                                )
                            }
                            FilterChip(selected = all, onClick = { all = !all }, label = { Text(if (all) "한국어만" else "다른 언어도") })
                        }
                    }
                }

                HorizontalDivider(thickness = 0.5.dp)
                Text("읽는 방법", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("음높이", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                    ReadAloud.PITCHES.forEach { (p, label) ->
                        FilterChip(selected = ra.pitch == p, onClick = { ra.changePitch(p) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { ra.changeNormalize(!ra.normalize) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("숫자·기호를 한글로 읽기", style = MaterialTheme.typography.bodyMedium)
                        Text("1/2 → 이분의 일, 3개 → 세 개", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = ra.normalize, onCheckedChange = { ra.changeNormalize(it) })
                }
                PronDictRow(ra)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
    if (setup) AzureSetupDialog(ra, onDismiss = { setup = false; azureMode = AzureVoice.on(ctx) })
}

/** Chips that wrap to the next line. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ChipFlow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) { content() }
}

/** "한국어 3" style names (engines' own names are codes like ko-KR-SMTl03 / ko-kr-x-ism-local). */
private fun voiceLabel(v: Voice, list: List<Voice>): String {
    val lang = v.locale.getDisplayLanguage(java.util.Locale.KOREAN).ifBlank { v.locale.toString() }
    val same = list.filter { it.locale.language == v.locale.language }
    val n = same.indexOf(v) + 1
    val country = v.locale.getDisplayCountry(java.util.Locale.KOREAN)
    return "$lang ${n}" + (if (country.isNotBlank() && v.locale.language != "ko") " ($country)" else "")
}

/**
 * The bar's drag handle: six dots (the common "move me" mark). It used to be "≡", which reads as a
 * menu button, so readers tapped it and nothing happened; a tap now says what it is for.
 */
@Composable
private fun DragGrip(color: Color) {
    val ctx = LocalContext.current
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(width = 22.dp, height = 36.dp)
            .clickable { com.oasisreader.app.ui.Notice.show(ctx, "막대를 끌어서 원하는 곳으로 옮길 수 있습니다") }
            .semantics { contentDescription = "끌어서 막대 옮기기" },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 8.dp, height = 14.dp)) {
            val r = size.width * 0.16f
            for (col in 0..1) for (row in 0..2) {
                drawCircle(
                    color,
                    radius = r,
                    center = androidx.compose.ui.geometry.Offset(
                        x = r + col * (size.width - 2 * r),
                        y = r + row * (size.height - 2 * r) / 2f,
                    ),
                )
            }
        }
    }
}
