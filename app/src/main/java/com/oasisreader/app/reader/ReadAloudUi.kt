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
import androidx.compose.ui.draw.clip
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
fun ReadAloudBar(
    ra: ReadAloud, fill: Color, onVoice: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
    title: String = "", chapter: String = "", cover: java.io.File? = null,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(ReadAloud.PREFS, android.content.Context.MODE_PRIVATE) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset(prefs.getFloat("bar_x", 0f), prefs.getFloat("bar_y", 0f))) }
    // 95: the big card first when reading starts, the simple bar after a while
    var open by remember { mutableStateOf(true) }
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
    val drag = Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragEnd = { off = clamp(off); prefs.edit().putFloat("bar_x", off.x).putFloat("bar_y", off.y).apply() },
        ) { change, d ->
            change.consume()
            off = clamp(off + d); touch++
        }
    }
    val border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (eink) 1f else 0.4f))
    if (!open) {
        // the simple bar (82): ⠿ ⏯, more see-through (92)
        Surface(
            modifier = modifier
                .onSizeChanged { size = it }
                .offset { val c = clamp(off); androidx.compose.ui.unit.IntOffset(c.x.toInt(), c.y.toInt()) }
                .then(drag),
            shape = RoundedCornerShape(24.dp),
            color = fill.copy(alpha = if (eink) 1f else 0.42f),
            border = border,
            shadowElevation = if (eink) 0.dp else 1.dp,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
            ) {
                Row(
                    Modifier.height(34.dp).padding(horizontal = 4.dp).clickable { open = true; touch++ },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DragGrip(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) { open = true; touch++ }
                    BarButton(if (ra.playing) "⏸" else "▶", if (ra.playing) "일시정지" else "계속 읽기", tap { ra.toggle() })
                }
            }
        }
        return
    }
    // 92: opened, like Readest — cover · title · chapter, ⏮ ⏯ ⏭ ✕, and 빠르기 · 수면 · 목소리 under
    // the title; the card spans the width at the bar's height and folds back after a while
    Surface(
        modifier = modifier
            .offset { androidx.compose.ui.unit.IntOffset(0, clamp(off).y.toInt()) }
            .fillMaxWidth()
            .padding(end = 10.dp)
            .then(drag),
        shape = RoundedCornerShape(22.dp),
        color = fill.copy(alpha = if (eink) 1f else 0.92f),
        border = border,
        shadowElevation = if (eink) 0.dp else 3.dp,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
        ) {
            Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                com.oasisreader.app.ui.BookCover(
                    cover?.takeIf { it.exists() }, title,
                    Modifier.size(width = 40.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)).clickable { open = false },
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(title.ifBlank { "소리 내 읽기" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (chapter.isNotBlank()) Text(
                        chapter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var speedOpen by remember { mutableStateOf(false) }
                        var sleepMenu by remember { mutableStateOf(false) }
                        LaunchedEffect(speedOpen, sleepMenu) { menuOpen = speedOpen || sleepMenu }
                        Box {
                            TextButton(
                                onClick = { speedOpen = true; touch++ },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp),
                            ) { Text(ReadAloud.rateLabel(ra.rate), style = MaterialTheme.typography.labelMedium) }
                            OgMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) { RateSlider(ra) }
                        }
                        SleepButton(ra) { sleepMenu = it }
                        BarButton("🎙", "목소리", tap(onVoice))
                    }
                }
                BarButton("⏮", "이전 문장", tap { ra.skip(-1) })
                // the main button, big and filled
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 4.dp).size(46.dp).clickable(onClick = tap { ra.toggle() })
                        .semantics { contentDescription = if (ra.playing) "일시정지" else "계속 읽기" },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        EmojiText(if (ra.playing) "⏸" else "▶", fontSize = 20.sp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                BarButton("⏭", "다음 문장", tap { ra.skip(1) })
                BarButton("✕", "소리 내 읽기 끝내기", onClose)
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
 * 목소리 (88 → 2.10 정리): 전체 / 여성 / 남성, the engine in a dropdown (Azure too), that engine's
 * voices as rows (▶ plays a sample, the row picks it — another engine is switched to only then),
 * then 읽는 방법 as one-line rows whose values open small menus.
 */
@Composable
fun VoiceDialog(ra: ReadAloud, onDismiss: () -> Unit) {
    var rev by remember { mutableIntStateOf(0) }           // engine changed / voices loaded
    var all by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }            // 0 전체 · 1 여성 · 2 남성
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
    // the engine whose voices are listed: the one in use to begin with
    var view by remember { mutableStateOf(if (AzureVoice.on(ctx)) AZURE else ra.engine().orEmpty()) }
    LaunchedEffect(engine) { if (view.isEmpty() && engine != null) view = engine }
    LaunchedEffect(view, engine, catalogRev) { if (view != AZURE && view.isNotEmpty() && view != engine) catalog.load(view) }
    val sources = listOf(AZURE to "Azure 음성") + engines.map { (pkg, label) -> pkg to (if (pkg == AiVoice.PKG) "$label (AI)" else label) }
    val viewLabel = sources.firstOrNull { it.first == view }?.second ?: "엔진"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("목소리") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("전체", "여성", "남성").forEachIndexed { i, label ->
                        com.oasisreader.app.ui.OgChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
                    }
                }

                // the engine: a dropdown; the one in use is marked
                var engMenu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("엔진", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { engMenu = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                            Text("$viewLabel ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OgMenu(expanded = engMenu, onDismissRequest = { engMenu = false }) {
                            sources.forEach { (pkg, label) ->
                                val inUse = if (pkg == AZURE) azureMode else !azureMode && pkg == engine
                                DropdownMenuItem(
                                    text = { Text(if (inUse) "$label · 사용 중" else label) },
                                    leadingIcon = { com.oasisreader.app.ui.CheckMark(pkg == view) },
                                    onClick = { engMenu = false; catalog.stop(); view = pkg },
                                )
                            }
                        }
                    }
                }

                // the AI voice card only when there is something to do: not installed yet, or
                // in use but not ready (no voices / did not open)
                val aiInstalled = engines.any { it.first == AiVoice.PKG }
                val aiStuck = !azureMode && engine == AiVoice.PKG && (ra.available == false || (ra.available == true && voices.isEmpty()))
                if ((view == AiVoice.PKG || !aiInstalled) && (!aiInstalled || aiStuck)) AiVoiceCard(ra, engines, engine, voices.size, onChanged = { picked = null; rev++ })

                if (view == AZURE) {
                    AzureVoiceRows(
                        ra, tab, azureMode,
                        onSetup = { setup = true },
                        onPlay = { catalog.stop() },
                        onPick = { azureMode = true; catalog.stop(); rev++ },
                    )
                } else if (view.isNotEmpty()) {
                    val pkg = view
                    val inUse = !azureMode && pkg == engine
                    val list: List<Voice>? = if (pkg == engine) voices.takeIf { ra.available == true }
                        else catalog.voices[pkg]?.let { ra.filterVoices(it, all) }
                    val rows = list?.filter { VoiceGender.shows(tab, VoiceGender.of(pkg, it.name, it.features.orEmpty())) }
                    when {
                        list == null && (catalog.failed[pkg] == true || (pkg == engine && ra.available == false)) ->
                            Text("열 수 없음", style = MaterialTheme.typography.bodySmall)
                        list == null -> Text("불러오는 중…", style = MaterialTheme.typography.bodySmall)
                        list?.isEmpty() == true -> Text("목소리 없음 — 휴대폰 설정 › 텍스트 음성 변환에서 받기", style = MaterialTheme.typography.bodySmall)
                        rows.isNullOrEmpty() -> Text("이 성별 목소리 없음", style = MaterialTheme.typography.bodySmall)
                        else -> rows.orEmpty().forEach { v ->
                            VoiceRow(
                                name = voiceLabel(v, list ?: emptyList()),
                                gender = VoiceGender.of(pkg, v.name, v.features.orEmpty()),
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
                    TextButton(onClick = { all = !all }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                        Text(if (all) "한국어만 보기" else "다른 언어도 보기", style = MaterialTheme.typography.labelMedium)
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

                HorizontalDivider(thickness = 0.5.dp)
                // 읽는 방법: one line each, the value opens a small menu (2.10)
                PickRow("음높이", ReadAloud.PITCHES.map { it.second }, ReadAloud.PITCHES.indexOfFirst { it.first == ra.pitch }.coerceAtLeast(0)) { i ->
                    ra.changePitch(ReadAloud.PITCHES[i].first)
                }
                // 84: the pause after a sentence, for this engine (engines add their own silence)
                var gap by remember(rev, azureMode) { mutableIntStateOf(ra.gapLevel()) }
                PickRow("문장 사이", listOf("없음", "짧게", "보통", "길게"), gap) { i -> gap = i; ra.changeGap(i) }
                // 83: what tells a new chapter has begun
                val cues = listOf(ReadAloud.CUE_BOTH to "소리 + 제목", ReadAloud.CUE_SOUND to "소리", ReadAloud.CUE_TITLE to "제목", 0 to "없음")
                PickRow("장 넘김", cues.map { it.second }, cues.indexOfFirst { it.first == ra.chapterCue }.coerceAtLeast(0)) { i ->
                    ra.changeChapterCue(cues[i].first)
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
    if (setup) AzureSetupDialog(ra, onDismiss = { setup = false; azureMode = AzureVoice.on(ctx) })
}

private const val AZURE = "azure"

/** 제목 · 값 › on one line; the value opens a small menu (2.10, like the settings screen). */
@Composable
private fun PickRow(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            Text(options.getOrElse(selected) { "" } + " ›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OgMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { com.oasisreader.app.ui.CheckMark(i == selected) },
                        onClick = { open = false; onPick(i) },
                    )
                }
            }
        }
    }
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
