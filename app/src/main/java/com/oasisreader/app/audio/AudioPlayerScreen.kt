package com.oasisreader.app.audio

import com.oasisreader.app.ui.EmojiText
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.ui.RemoteCover
import com.oasisreader.app.ui.ThinSlider
import com.oasisreader.app.ui.TopBar

/** The full player: cover, track, position, ⏪15 ⏯ 30⏩, tracks, speed, sleep timer. */
@Composable
fun AudioPlayerScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val b = AudioPlayback.book
    val web = AudioPlayback.web
    // the top bar fades out 2.5 s after the last touch (its place stays, so nothing moves under
    // the finger); any touch brings it back, and while hidden its buttons do not react
    var barShown by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(barShown, touchedAt, b) {
        if (b == null || !barShown) return@LaunchedEffect
        kotlinx.coroutines.delay(2_500)
        barShown = false
    }
    val barAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (barShown) 1f else 0f,
        animationSpec = if (com.oasisreader.app.ui.theme.ThemeMode.eink) androidx.compose.animation.core.snap<Float>() else androidx.compose.animation.core.spring<Float>(),
        label = "bar",
    )
    // While the bar is hidden the screen sits 16 dp higher (its empty place made it hang low). A touch
    // that starts while it is hidden keeps the screen up until the finger lifts, so the button under
    // the finger is not moved away from it (heldLift).
    var heldLift by remember { mutableStateOf(false) }
    val lift by androidx.compose.animation.core.animateFloatAsState(
        if (!barShown || heldLift) 1f else 0f,
        animationSpec = if (com.oasisreader.app.ui.theme.ThemeMode.eink) androidx.compose.animation.core.snap<Float>() else androidx.compose.animation.core.spring<Float>(),
        label = "lift",
    )
    var picking by remember { mutableStateOf(false) }
    // The place in the track changes twice a second: only the small pieces below that show it
    // (BookPlaceLine, PlayerHeadline, PlayerSeek) read it, so the rest of the screen stays put.
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                heldLift = !barShown
                barShown = true; touchedAt = System.currentTimeMillis()
                do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                heldLift = false
            }
        },
    ) {
        Box {
            Box(Modifier.graphicsLayer { alpha = barAlpha }) {
                TopBar("재생 중", onBack = onBack, height = 48.dp) {
                    if (b != null) com.oasisreader.app.ui.EmojiButton("⏹️", "듣기 끝내기") { AudioPlayback.close(context); onBack() }
                }
                // the whole book's place (TODO 34), part of the bar: it fades out with it
                if (b != null) Box(
                    Modifier.align(Alignment.BottomEnd).height(48.dp).padding(end = 54.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) { BookPlaceLine(b) }
            }
            // hidden: a touch on the bar only brings it back
            if (!barShown) Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
            })
        }
        if (b == null) Text("재생 중인 오디오북이 없습니다.", modifier = Modifier.padding(24.dp))
        else Column(
            Modifier.fillMaxSize().graphicsLayer { translationY = -lift * 16.dp.toPx() }
                .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            val coverMod = Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f)
            val localCover = b.artworkFile?.let { java.io.File(it) }?.takeIf { it.isFile }
            when {
                // 소장 오디오북 (and books with a 서재 cover): the phone's copy
                localCover != null && (b.own || web == null) -> com.oasisreader.app.ui.BookCover(localCover, b.title, coverMod)
                web != null -> RemoteCover(b.coverUrl, b.title, { u -> web.bytes(u) }, coverMod)
                else -> Box(coverMod, contentAlignment = Alignment.Center) { EmojiText("🎧", fontSize = 64.sp) }
            }
            Spacer(Modifier.height(10.dp))
            PlayerHeadline(b) { picking = true }
            AudioPlayback.error?.let { EmojiText("⚠️ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(6.dp))
            PlayerSeek()
            Spacer(Modifier.height(8.dp))
            PlayerButtons(b)
            Spacer(Modifier.height(8.dp))
            // above the chips: at the bottom edge a slide on it ran into the home gesture
            VolumeRow()
            Spacer(Modifier.height(8.dp))
            PlayerChips()
            Spacer(Modifier.height(24.dp))
        }
    }
    if (picking && b != null) PlacePicker(b) { picking = false }
}

/** "1:02:03 / 9:40:00 · 11%" of the whole book (TODO 34); "약" while some track lengths are still unknown. */
@Composable
private fun BookPlaceLine(b: AudioBook) {
    val place = bookPlace(
        b.tracks.map { it.durationSec }, AudioPlayback.trackIndex,
        AudioPlayback.positionMs / 1000.0, AudioPlayback.durationMs / 1000.0,
    ) ?: return
    val pct = (place.doneSec / place.totalSec * 100).toInt().coerceIn(0, 100)
    Text(
        (if (place.exact) "" else "약 ") + "${hms(place.doneSec)} / ${hms(place.totalSec)} · $pct%",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
    )
}

/** Title and the 장 (or track) playing now; a tap opens the list to jump (TODO 35). */
@Composable
private fun PlayerHeadline(b: AudioBook, onPick: () -> Unit) {
    // 장 표시: the chapter inside the track (m4b, metadata.json), else the track
    val ci by remember(b) { derivedStateOf { if (b.chapters.isNotEmpty()) AudioPlayback.chapterIndex() else -1 } }
    val ti = AudioPlayback.trackIndex
    val ch = b.chapters.getOrNull(ci)
    val t = b.tracks.getOrNull(ti)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = if (b.chapters.isNotEmpty()) "장 목록 열기" else "트랙 목록 열기", onClick = onPick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ch != null) "${ci + 1}장 / ${b.chapters.size} · ${ch.title}"
                else "${ti + 1} / ${b.tracks.size}" + (t?.title?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            com.oasisreader.app.ui.OgIcons.icon("down")?.let {
                androidx.compose.material3.Icon(it, contentDescription = "장 목록", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp).size(22.dp))
            }
        }
    }
}

/** The track's seek bar with the place and the length under it. */
@Composable
private fun PlayerSeek() {
    val dur = AudioPlayback.durationMs.coerceAtLeast(1L)
    var drag by remember { mutableStateOf<Float?>(null) }
    ThinSlider(
        value = drag ?: (AudioPlayback.positionMs.toFloat() / dur).coerceIn(0f, 1f), range = 0f..1f,
        onChange = { drag = it },
        modifier = Modifier.fillMaxWidth(),
    )
    LaunchedEffect(drag) {
        val d = drag ?: return@LaunchedEffect
        kotlinx.coroutines.delay(350)          // seek when the finger rests / lets go
        AudioPlayback.seekTo((d * dur).toLong()); drag = null
    }
    Row(Modifier.fillMaxWidth()) {
        Text(hms(AudioPlayback.positionMs / 1000.0), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text(hms(dur / 1000.0), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PlayerButtons(b: AudioBook) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Big("⏮", if (b.chapters.isNotEmpty()) "이전 장" else "이전 트랙", 22) { AudioPlayback.previous() }
        Big("↺15", "15초 뒤로", 16) { AudioPlayback.seekBy(-15_000) }
        // ⏯: a plain high-contrast disc (dark on light, light on dark) with a drawn icon
        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.onSurface, shadowElevation = 2.dp) {
            IconButton(onClick = { AudioPlayback.toggle() }, modifier = Modifier.size(68.dp).semantics { contentDescription = if (AudioPlayback.playing) "일시정지" else "재생" }) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(if (AudioPlayback.playing) com.oasisreader.app.R.drawable.ic_ra_pause else com.oasisreader.app.R.drawable.ic_ra_play),
                    contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(34.dp),
                )
            }
        }
        Big("30↻", "30초 앞으로", 16) { AudioPlayback.seekBy(30_000) }
        Big("⏭", if (b.chapters.isNotEmpty()) "다음 장" else "다음 트랙", 22) { AudioPlayback.next() }
    }
}

/** 🔈 ━━ 🔊: the phone's media volume (TODO 39), in step with the volume buttons. */
@Composable
private fun VolumeRow() {
    val context = LocalContext.current
    val am = remember { context.applicationContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    val stream = android.media.AudioManager.STREAM_MUSIC
    val max = remember { am.getStreamMaxVolume(stream).coerceAtLeast(1) }
    var vol by remember { mutableIntStateOf(am.getStreamVolume(stream)) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val app = context.applicationContext
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) { vol = am.getStreamVolume(stream) }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            app, r, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,   // the system's own broadcast still arrives
        )
        onDispose { runCatching { app.unregisterReceiver(r) } }
    }
    // in a car (Android Auto) the car's volume decides: this slider would move nothing audible (TODO 67)
    val inCar = rememberCarConnected()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().alpha(if (inCar) 0.4f else 1f).semantics { contentDescription = "음량" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔈", fontSize = 14.sp)
            ThinSlider(
                value = vol.toFloat(), range = 0f..max.toFloat(),
                onChange = { v ->
                    val n = Math.round(v).coerceIn(0, max)
                    if (n != vol) { vol = n; runCatching { am.setStreamVolume(stream, n, 0) } }
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                enabled = !inCar,
            )
            Text("🔊", fontSize = 14.sp)
        }
        if (inCar) Text(
            "음량은 차량에서 조절하세요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun Big(glyph: String, label: String, size: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp).semantics { contentDescription = label }) {
        Text(glyph, fontSize = size.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
