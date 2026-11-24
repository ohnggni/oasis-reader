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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The small floating player shown while reading aloud (82, 2.8): see-through, folded to ⠿ ⏯ at
 * first — a tap opens it to sentence ⏮ ⏯ ⏭, speed, sleep timer, voice, stop, and it folds again
 * when left alone. It sits at the bottom-left edge (clear of the page number); drag it by the grip
 * or its background and the place is kept for next time.
 */
@Composable
fun ReadAloudBar(ra: ReadAloud, fill: Color, onVoice: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(ReadAloud.PREFS, android.content.Context.MODE_PRIVATE) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset(prefs.getFloat("bar_x", 0f), prefs.getFloat("bar_y", 0f))) }
    var open by remember { mutableStateOf(false) }
    var touch by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open, touch, menuOpen) { if (open && !menuOpen) { delay(3500); open = false } }
    // kept on screen: anchored bottom-left, so x goes right (≥ 0) and y goes up (≤ 0)
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenW = with(density) { conf.screenWidthDp.dp.toPx() }
    val screenH = with(density) { conf.screenHeightDp.dp.toPx() }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun clamp(o: androidx.compose.ui.geometry.Offset) = androidx.compose.ui.geometry.Offset(
        o.x.coerceIn(0f, (screenW - size.width - with(density) { 20.dp.toPx() }).coerceAtLeast(0f)),
        o.y.coerceIn(-(screenH * 0.8f), 0f),
    )
    val eink = com.oasisreader.app.ui.theme.ThemeMode.eink
    fun tap(f: () -> Unit): () -> Unit = { touch++; f() }
    Surface(
        modifier = modifier
            .onSizeChanged { size = it }
            .offset { val c = clamp(off); androidx.compose.ui.unit.IntOffset(c.x.toInt(), c.y.toInt()) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { off = clamp(off); prefs.edit().putFloat("bar_x", off.x).putFloat("bar_y", off.y).apply() },
                ) { change, drag ->
                    change.consume()
                    off = clamp(off + drag); touch++
                }
            },
        shape = RoundedCornerShape(24.dp),
        color = fill.copy(alpha = if (eink) 1f else 0.6f),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (eink) 1f else 0.5f)),
        shadowElevation = if (eink) 0.dp else 2.dp,
    ) {
        // thin (#223): the buttons keep their size, the space above and below them goes
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
        ) {
        Row(
            Modifier.height(34.dp).padding(horizontal = 4.dp).clickable(enabled = !open) { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DragGrip(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) { open = !open; touch++ }
            if (open) BarButton("⏮", "이전 문장", tap { ra.skip(-1) })
            BarButton(if (ra.playing) "⏸" else "▶", if (ra.playing) "일시정지" else "계속 읽기", tap { ra.toggle() })
            if (open) {
                BarButton("⏭", "다음 문장", tap { ra.skip(1) })
                var speedOpen by remember { mutableStateOf(false) }
                LaunchedEffect(speedOpen) { menuOpen = speedOpen }
                Box {
                    TextButton(
                        onClick = { speedOpen = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text(ReadAloud.rateLabel(ra.rate), style = MaterialTheme.typography.labelLarge) }
                    OgMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                        RateSlider(ra)
                    }
                }
                SleepButton(ra) { menuOpen = it }
                BarButton("🎙", "목소리", tap(onVoice))
                BarButton("✕", "소리 내 읽기 끝내기", onClose)
            }
        }
        }
    }
}

/**
 * 빠르기 (87): − slider + by 0.05 from 0.5 to 2.0. Buttons change it at once; the slider when let
 * go (each change starts the sentence again).
 */
@Composable
private fun RateSlider(ra: ReadAloud) {
    var v by remember { mutableStateOf(ra.rate) }
    LaunchedEffect(ra.rate) { v = ra.rate }
    val steps = Math.round((ReadAloud.RATE_MAX - ReadAloud.RATE_MIN) / ReadAloud.RATE_STEP) - 1
    Column(Modifier.width(280.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("빠르기 " + ReadAloud.rateLabel(v), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { ra.changeRate(ra.rate - ReadAloud.RATE_STEP) }, enabled = ra.rate > ReadAloud.RATE_MIN) { Text("−", style = MaterialTheme.typography.titleMedium) }
            androidx.compose.material3.Slider(
                value = v,
                onValueChange = { v = ReadAloud.snapRate(it) },
                onValueChangeFinished = { ra.changeRate(v) },
                valueRange = ReadAloud.RATE_MIN..ReadAloud.RATE_MAX,
                steps = steps,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { ra.changeRate(ra.rate + ReadAloud.RATE_STEP) }, enabled = ra.rate < ReadAloud.RATE_MAX) { Text("+", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun SleepButton(ra: ReadAloud, onOpenChange: (Boolean) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { onOpenChange(open) }
    // minutes left, refreshed every 20 s while a timer runs
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ra.sleepAt) { while (ra.sleepAt > 0) { now = System.currentTimeMillis(); delay(20_000) } }
    val left = if (ra.sleepAt > 0) ((ra.sleepAt - now + 59_999) / 60_000).toInt().coerceAtLeast(1) else 0
    Box {
        TextButton(
            onClick = { open = true },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp).semantics { contentDescription = "수면 타이머" },
        ) {
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
    IconButton(onClick = onClick, modifier = Modifier.size(width = 38.dp, height = 32.dp).semantics { contentDescription = label }) {
        com.oasisreader.app.ui.EmojiText(glyph, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * 목소리 (88, like Readest): every engine's voices in one list — Azure, then the phone's engines —
 * a row each (▶ plays a sample, the row picks it), 전체 / 여성 / 남성 on top, engines left out at
 * the bottom; then 읽는 방법.
 */
@Composable
fun VoiceDialog(ra: ReadAloud, onDismiss: () -> Unit) {
    var rev by remember { mutableIntStateOf(0) }           // engine changed / voices loaded
    var all by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }            // 0 전체 · 1 여성 · 2 남성
    var hidden by remember { mutableStateOf(ra.hiddenEngines()) }
    var catalogRev by remember { mutableIntStateOf(0) }
    // a new engine needs a moment to list its voices
    LaunchedEffect(ra.available, rev) { if (ra.available == null) { delay(400); rev++ } }
    val engines = remember(rev, ra.available) { ra.engines() }
    val engine = remember(rev, ra.available) { ra.engine() }
    val voices = remember(rev, ra.available, all) { ra.voices(all) }
    var picked by remember(rev) { mutableStateOf(ra.voiceName()) }
    val sample = "안녕하세요. 이 목소리로 책을 읽어 드립니다."
    val ctx = LocalContext.current
    val catalog = remember { VoiceCatalog(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { catalog.close() } }
    // back from the store / the AI voice app: pick it up (new engine, or new voices in it)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (AiVoice.takeReturn(ctx) && ra.engines().any { it.first == AiVoice.PKG }) {
                if (ra.engine() != AiVoice.PKG) ra.setEngine(AiVoice.PKG) else ra.reload()
            }
            catalog.close(); catalogRev++
            rev++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    var azureMode by remember { mutableStateOf(AzureVoice.on(ctx)) }
    var setup by remember { mutableStateOf(false) }
    var howOpen by remember { mutableStateOf(false) }
    // the engine in use always shows; the others unless left out
    val shown = engines.filter { (pkg, _) -> pkg !in hidden || (pkg == engine && !azureMode) }
    LaunchedEffect(shown.map { it.first }, engine, catalogRev) {
        shown.forEach { (pkg, _) -> if (pkg != engine) catalog.load(pkg) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("목소리") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("전체", "여성", "남성").forEachIndexed { i, label ->
                        FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
                    }
                }

                // 82: this book keeps its own 빠르기 · 목소리 (only when opened from a book)
                if (ra.bookId != null) Row(
                    Modifier.fillMaxWidth().clickable { ra.changeOwn(!ra.own); picked = ra.voiceName() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("이 책만 따로 (빠르기 · 목소리)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = ra.own, onCheckedChange = { ra.changeOwn(it); picked = ra.voiceName() })
                }

                // the AI voice card only when there is something to do: not installed yet, or
                // in use but not ready (no voices / did not open). Installed = just a section.
                val aiInstalled = engines.any { it.first == AiVoice.PKG }
                val aiStuck = !azureMode && engine == AiVoice.PKG && (ra.available == false || (ra.available == true && voices.isEmpty()))
                if (!aiInstalled || aiStuck) AiVoiceCard(ra, engines, engine, voices.size, onChanged = { picked = null; rev++ })

                if ("azure" !in hidden || azureMode) {
                    VoiceSection("Azure 음성", azureMode)
                    AzureVoiceRows(
                        ra, tab, azureMode,
                        onSetup = { setup = true },
                        onPlay = { catalog.stop() },
                        onPick = { azureMode = true; catalog.stop(); rev++ },
                    )
                }

                shown.forEach { (pkg, label) ->
                    val inUse = !azureMode && pkg == engine
                    VoiceSection(if (pkg == AiVoice.PKG) "$label (AI)" else label, inUse)
                    val list: List<Voice>? = if (pkg == engine) voices.takeIf { ra.available == true }
                        else catalog.voices[pkg]?.let { ra.filterVoices(it, all) }
                    val rows = list?.filter { VoiceGender.shows(tab, VoiceGender.of(pkg, it.name)) }
                    when {
                        list == null && (catalog.failed[pkg] == true || (pkg == engine && ra.available == false)) ->
                            Text("열 수 없음", style = MaterialTheme.typography.bodySmall)
                        list == null -> Text("불러오는 중…", style = MaterialTheme.typography.bodySmall)
                        list?.isEmpty() == true -> Text("목소리 없음 — 휴대폰 설정 › 텍스트 음성 변환에서 받기", style = MaterialTheme.typography.bodySmall)
                        rows.isNullOrEmpty() -> Text("이 성별 목소리 없음", style = MaterialTheme.typography.bodySmall)
                        else -> rows.orEmpty().forEach { v ->
                            VoiceRow(
                                name = voiceLabel(v, list ?: emptyList()),
                                gender = VoiceGender.of(pkg, v.name),
                                cloud = v.isNetworkConnectionRequired,
                                selected = inUse && picked == v.name,
                                onPlay = {
                                    catalog.stop()
                                    if (pkg == engine) ra.preview(v, sample)
                                    else { ra.quietForPreview(); catalog.preview(pkg, v, sample, ra.rate, ra.pitch) }
                                },
                                onPick = {
                                    catalog.stop()
                                    val switching = pkg != engine
                                    ra.chooseVoice(pkg, v.name)
                                    azureMode = false; picked = v.name
                                    if (switching) rev++
                                },
                            )
                        }
                    }
                }

                TextButton(onClick = { all = !all }) { Text(if (all) "한국어만 보기" else "다른 언어도 보기") }

                // engines to list (the one in use always shows)
                Text("보이는 엔진", style = MaterialTheme.typography.labelLarge)
                ChipFlow {
                    (listOf("azure" to "Azure") + engines).forEach { (pkg, label) ->
                        FilterChip(
                            selected = pkg !in hidden,
                            onClick = {
                                val hide = pkg !in hidden
                                ra.setHidden(pkg, hide); hidden = ra.hiddenEngines()
                            },
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }

                HorizontalDivider(thickness = 0.5.dp)
                // 읽는 방법: one line until opened
                val pitchLabel = ReadAloud.PITCHES.firstOrNull { it.first == ra.pitch }?.second ?: "보통"
                Row(
                    Modifier.fillMaxWidth().clickable { howOpen = !howOpen }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("읽는 방법", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "음높이 $pitchLabel · 숫자 읽기 ${if (ra.normalize) "켬" else "끔"} · 발음 사전 ${PronDict.entries.size}개",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(if (howOpen) "⌃" else "⌄", style = MaterialTheme.typography.titleMedium)
                }
                if (howOpen) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("음높이", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                        ReadAloud.PITCHES.forEach { (p, label) ->
                            FilterChip(selected = ra.pitch == p, onClick = { ra.changePitch(p) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                    // 84: the pause after a sentence, for this engine (engines add their own silence)
                    var gap by remember(rev, azureMode) { mutableIntStateOf(ra.gapLevel()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("문장 사이", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                        ChipFlow {
                            listOf("없음", "짧게", "보통", "길게").forEachIndexed { v, label ->
                                FilterChip(selected = gap == v, onClick = { gap = v; ra.changeGap(v) }, label = { Text(label) })
                            }
                        }
                    }
                    // 83: what tells a new chapter has begun
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("장 넘김", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                        ChipFlow {
                            listOf(ReadAloud.CUE_BOTH to "소리 + 제목", ReadAloud.CUE_SOUND to "소리", ReadAloud.CUE_TITLE to "제목", 0 to "없음").forEach { (v, label) ->
                                FilterChip(selected = ra.chapterCue == v, onClick = { ra.changeChapterCue(v) }, label = { Text(label) })
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { ra.changeNormalize(!ra.normalize) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("숫자 · 기호를 한글로", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = ra.normalize, onCheckedChange = { ra.changeNormalize(it) })
                    }
                    PronDictRow(ra)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
    if (setup) AzureSetupDialog(ra, onDismiss = { setup = false; azureMode = AzureVoice.on(ctx) })
}

/** A source's heading in the voice dialog; ✓ on the one in use. */
@Composable
internal fun VoiceSection(title: String, inUse: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, style = MaterialTheme.typography.labelLarge,
            color = if (inUse) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (inUse) Text("사용 중", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** One voice: ▶ sample · name · 여성/남성 · ☁ · ✔️ when chosen. The row picks it. */
@Composable
internal fun VoiceRow(name: String, gender: Int?, cloud: Boolean, selected: Boolean, onPlay: () -> Unit, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlay, modifier = Modifier.size(36.dp).semantics { contentDescription = "$name 들어 보기" }) {
            EmojiText("▶", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            name + if (cloud) " ☁" else "", style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        VoiceGender.label(gender)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.weight(1f))
        com.oasisreader.app.ui.CheckMark(selected)
    }
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

/**
 * The engine's own voice name when it is one a person can read (RD_민재); "한국어 3" only for
 * engines whose names are codes (ko-KR-SMTl03, ko-kr-x-ism-local, supertonic-M2-ko). The number
 * is the voice's place in [list], which ReadAloud.voices sorts the same way every time.
 */
private fun voiceLabel(v: Voice, list: List<Voice>): String {
    VoiceNames.display(v.name)?.let { return it }
    val lang = v.locale.getDisplayLanguage(java.util.Locale.KOREAN).ifBlank { v.locale.toString() }
    val same = list.filter { it.locale.language == v.locale.language }
    val n = same.indexOfFirst { it.name == v.name } + 1
    val country = v.locale.getDisplayCountry(java.util.Locale.KOREAN)
    return "$lang ${n}" + (if (country.isNotBlank() && v.locale.language != "ko") " ($country)" else "")
}

/**
 * The bar's drag handle: six dots (the common "move me" mark). It used to be "≡", which reads as a
 * menu button, so readers tapped it and nothing happened; a tap now says what it is for.
 */
@Composable
private fun DragGrip(color: Color, onTap: () -> Unit) {
    val ctx = LocalContext.current
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(width = 22.dp, height = 30.dp)
            .clickable(onClick = onTap)
            .semantics { contentDescription = "막대 펼치기 · 접기 (끌어서 옮기기)" },
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
