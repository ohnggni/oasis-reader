package com.oasisreader.app.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.data.Server
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.ui.RemoteCover
import com.oasisreader.app.ui.ThinSlider
import com.oasisreader.app.ui.TopBar
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

// ── reading bookoasis JSON loosely (field names differ a little between lists) ──
private fun JsonObject.str(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "-" } }
private fun JsonObject.int(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } }
private fun JsonObject.dbl(vararg keys: String): Double? =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } }
/** The first array of objects in a response ({"items": [...]}, {"series": [...]} …). */
private fun JsonObject.firstList(vararg prefer: String): List<JsonObject> {
    val arr = prefer.firstNotNullOfOrNull { this[it] as? JsonArray } ?: values.firstOrNull { it is JsonArray } as? JsonArray
    return arr.orEmpty().mapNotNull { it as? JsonObject }
}

private data class AudioItem(val id: Int?, val title: String, val author: String?, val libraryId: String?, val cover: String?, val progress: Double?)
private data class AudioLibrary(val id: String, val name: String)

private fun JsonElement.toItem(): AudioItem? {
    val o = this as? JsonObject ?: return null
    val title = o.str("series_alias", "series_name", "title", "name") ?: return null
    return AudioItem(
        id = o.int("representative_book_id", "audiobook_id", "book_id", "id", "first_book_id"),
        title = title,
        author = o.str("author", "artist"),
        libraryId = o.str("library_id"),
        cover = o.str("cover_image", "cover", "cover_url", "thumbnail"),
        progress = o.dbl("total_progress_pct", "progress_pct", "progress"),
    )
}

/**
 * 오디오북: the audiobook shelves of bookoasis (its own web API, not OPDS), a book's tracks,
 * and the player. [onPlayer] opens the full player.
 */
@Composable
fun AudiobookScreen(serverId: String, onBack: () -> Unit, onPlayer: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val server = remember(serverId) { ServerSettings(context.applicationContext).get(serverId) }
    if (server == null || server.username.isBlank()) {
        BackHandler(onBack = onBack)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TopBar("오디오북", onBack = onBack)
            Text("북오아시스 계정(아이디·비밀번호)이 필요합니다. 서버 설정에서 넣어 주세요.", modifier = Modifier.padding(24.dp))
            TextButton(onClick = onOpenSettings, modifier = Modifier.padding(horizontal = 16.dp)) { Text("서버 설정") }
        }
        return
    }
    val web = remember(server) { BookOasisWeb.of(server) }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("audio", android.content.Context.MODE_PRIVATE) }

    var libraries by remember(server) { mutableStateOf<List<AudioLibrary>>(emptyList()) }
    var libId by remember(server) { mutableStateOf(prefs.getString("library_${server.id}", null)) }
    var query by remember { mutableStateOf("") }
    var active by remember { mutableStateOf("") }
    var sortDesc by remember { mutableStateOf(prefs.getBoolean("sort_desc", false)) }
    val items = remember(server) { mutableStateListOf<AudioItem>() }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<AudioItem?>(null) }

    LaunchedEffect(server) {
        runCatching { web.get("/api/media/libraries", mapOf("type" to "audiobook")) }
            .onSuccess { o ->
                libraries = o.firstList("libraries").mapNotNull { l ->
                    val id = l.str("id") ?: return@mapNotNull null
                    AudioLibrary(id, l.str("name") ?: id)
                }
            }
            .onFailure { error = it.message }
    }
    suspend fun load(p: Int) {
        loading = true; error = null
        try {
            val o = web.get("/api/media/list", mapOf(
                "type" to "audiobook", "library_id" to libId, "search" to active.ifBlank { null },
                "sort" to if (sortDesc) "desc" else "asc", "page" to p, "limit" to 40,
            ))
            val list = o.firstList("items", "series", "data", "list", "books").mapNotNull { it.toItem() }
            if (p == 1) items.clear()
            items.addAll(list)
            hasMore = (o["has_more"] as? JsonPrimitive)?.contentOrNull == "true" || (o["has_more"] == null && list.size >= 40)
            page = p
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally { loading = false }
    }
    LaunchedEffect(server, libId, active, sortDesc) { load(1) }

    BackHandler { if (open != null) open = null else onBack() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(server.name.ifBlank { "오디오북" }, onBack = { if (open != null) open = null else onBack() }) {
            com.oasisreader.app.ui.EmojiButton("⚙️", "서버 설정", onOpenSettings)
            if (AudioPlayback.book != null) com.oasisreader.app.ui.EmojiButton("🎧", "재생 중", onPlayer)
        }
        val o = open
        if (o != null) AudiobookDetail(web, o, onPlayer) else AudioShelf(
            libraries, libId,
            { id -> libId = id; prefs.edit().apply { if (id == null) remove("library_${server.id}") else putString("library_${server.id}", id) }.apply() },
            query, { query = it }, { active = query.trim() },
            sortDesc, { sortDesc = !sortDesc; prefs.edit().putBoolean("sort_desc", sortDesc).apply() },
            items, hasMore, loading, page, error, web,
            load = { p -> scope.launch { load(p) } },
            onOpen = { open = it },
        )
    }
}

@Composable
private fun AudioShelf(
    libraries: List<AudioLibrary>, libId: String?, onLib: (String?) -> Unit,
    query: String, onQuery: (String) -> Unit, onSearch: () -> Unit,
    sortDesc: Boolean, onSort: () -> Unit,
    items: List<AudioItem>, hasMore: Boolean, loading: Boolean, page: Int, error: String?, web: BookOasisWeb,
    load: (Int) -> Unit, onOpen: (AudioItem) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("제목·저자 검색") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var lOpen by remember { mutableStateOf(false) }
            Box {
                AssistChip(onClick = { lOpen = true }, label = { Text("카테고리: ${libraries.firstOrNull { it.id == libId }?.name ?: "전체"} ▾") })
                DropdownMenu(lOpen, { lOpen = false }) {
                    DropdownMenuItem(text = { Text((if (libId == null) "✓ " else "    ") + "전체") }, onClick = {
                        lOpen = false; onLib(null)
                    })
                    libraries.forEach { l ->
                        DropdownMenuItem(text = { Text((if (l.id == libId) "✓ " else "    ") + l.name) }, onClick = {
                            lOpen = false; onLib(l.id)
                        })
                    }
                }
            }
            AssistChip(
                onClick = onSort,
                label = { Text(if (sortDesc) "↕ 이름 ↓" else "↕ 이름 ↑") },
            )
        }
        when {
            error != null && items.isEmpty() -> Column(Modifier.padding(24.dp)) {
                Text("⚠️ $error", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { load(1) }) { Text("다시 시도") }
            }
            loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Text("오디오북이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp),
            ) {
                itemsIndexed(items, key = { i, it -> "${it.id}:${it.title}:$i" }) { i, it ->
                    if (i == items.lastIndex && hasMore && !loading) LaunchedEffect(page) { load(page + 1) }
                    Column(Modifier.padding(6.dp).clickable { onOpen(it) }) {
                        RemoteCover(it.cover?.let(web::absolute), it.title, { u -> web.bytes(u) }, Modifier.fillMaxWidth().aspectRatio(1f))
                        it.progress?.takeIf { p -> p > 0 }?.let { p ->
                            LinearProgressIndicator(progress = { (p / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp))
                        }
                        Text(it.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        it.author?.let { a -> Text(a, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** One audiobook: cover, description, where you are, its tracks. */
@Composable
private fun AudiobookDetail(web: BookOasisWeb, item: AudioItem, onPlayer: () -> Unit) {
    val context = LocalContext.current
    var book by remember(item) { mutableStateOf<AudioBook?>(null) }
    var summary by remember(item) { mutableStateOf<String?>(null) }
    var pct by remember(item) { mutableStateOf(0.0) }
    var completed by remember(item) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val onShelf by remember(item) {
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
    when {
        error != null -> Text("⚠️ $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
        b == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp)) {
            item {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { AudioPlayback.open(context, web, b); onPlayer() }, enabled = b.tracks.isNotEmpty()) {
                                Text(if (b.startTrackId != null) "▶ 이어 듣기" else "▶ 처음부터 듣기")
                            }
                            if (onShelf == null) androidx.compose.material3.OutlinedButton(onClick = {
                                scope.launch {
                                    runCatching { AudioLibraryLink.add(context, web, b, pct, completed) }
                                        .onSuccess { android.widget.Toast.makeText(context, "서재에 담았습니다", android.widget.Toast.LENGTH_SHORT).show() }
                                        .onFailure { android.widget.Toast.makeText(context, "담지 못했습니다: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
                                }
                            }) { Text("📚 서재에 담기") }
                            else Text("📚 서재에 있음", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                }
            }
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
                }
            }
        }
    }
}

/** The full player: cover, track, position, ⏪15 ⏯ 30⏩, tracks, speed, sleep timer. */
@Composable
fun AudioPlayerScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val b = AudioPlayback.book
    val web = AudioPlayback.web
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("재생 중", onBack = onBack) {
            if (b != null) com.oasisreader.app.ui.EmojiButton("⏹️", "듣기 끝내기") { AudioPlayback.close(context); onBack() }
        }
        if (b == null || web == null) Text("재생 중인 오디오북이 없습니다.", modifier = Modifier.padding(24.dp))
        else Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            RemoteCover(b.coverUrl, b.title, { u -> web.bytes(u) }, Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(16.dp))
            Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val t = b.tracks.getOrNull(AudioPlayback.trackIndex)
            Text(
                "${AudioPlayback.trackIndex + 1} / ${b.tracks.size}" + (t?.title?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            AudioPlayback.error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Big("⏮", "이전 트랙", 22) { AudioPlayback.previous() }
                Big("↺15", "15초 뒤로", 16) { AudioPlayback.seekBy(-15_000) }
                Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.primary) {
                    IconButton(onClick = { AudioPlayback.toggle() }, modifier = Modifier.size(64.dp).semantics { contentDescription = if (AudioPlayback.playing) "일시정지" else "재생" }) {
                        Text(if (AudioPlayback.playing) "⏸" else "▶", fontSize = 26.sp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Big("30↻", "30초 앞으로", 16) { AudioPlayback.seekBy(30_000) }
                Big("⏭", "다음 트랙", 22) { AudioPlayback.next() }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var sOpen by remember { mutableStateOf(false) }
                Box {
                    AssistChip(onClick = { sOpen = true }, label = { Text("속도 ${AudioPlayback.speed}배") })
                    DropdownMenu(sOpen, { sOpen = false }) {
                        listOf(0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                            DropdownMenuItem(text = { Text((if (s == AudioPlayback.speed) "✓ " else "    ") + "${s}배") }, onClick = { sOpen = false; AudioPlayback.changeSpeed(context, s) })
                        }
                    }
                }
                var tOpen by remember { mutableStateOf(false) }
                val left = if (AudioPlayback.sleepAt > 0) ((AudioPlayback.sleepAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1) else 0
                Box {
                    AssistChip(onClick = { tOpen = true }, label = { Text(when { left > 0 -> "🌙 ${left}분"; AudioPlayback.sleepAtTrackEnd -> "🌙 트랙 끝"; else -> "🌙 타이머" }) })
                    DropdownMenu(tOpen, { tOpen = false }) {
                        listOf(15, 30, 45, 60, 90).forEach { m -> DropdownMenuItem(text = { Text("${m}분 뒤 멈춤") }, onClick = { tOpen = false; AudioPlayback.sleepIn(m) }) }
                        DropdownMenuItem(text = { Text("이 트랙 끝에서 멈춤") }, onClick = { tOpen = false; AudioPlayback.sleepAtEndOfTrack() })
                        DropdownMenuItem(text = { Text("타이머 끄기") }, onClick = { tOpen = false; AudioPlayback.sleepIn(0) })
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(thickness = 0.5.dp)
            b.tracks.forEachIndexed { i, tr ->
                val now = i == AudioPlayback.trackIndex
                Row(
                    Modifier.fillMaxWidth().clickable { AudioPlayback.track(i) }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (now) "▶" else "${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 28.dp))
                    Text(tr.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        fontWeight = if (now) FontWeight.Bold else FontWeight.Normal)
                    if (tr.durationSec > 0) Text(hms(tr.durationSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Big(glyph: String, label: String, size: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp).semantics { contentDescription = label }) {
        Text(glyph, fontSize = size.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Small bar at the bottom of the library while an audiobook is loaded: title, ⏯, tap for the player. */
@Composable
fun AudioMiniBar(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val b = AudioPlayback.book ?: return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🎧", fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(b.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${AudioPlayback.trackIndex + 1} / ${b.tracks.size} · ${hms(AudioPlayback.positionMs / 1000.0)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { AudioPlayback.toggle() }) { Text(if (AudioPlayback.playing) "⏸" else "▶", fontSize = 20.sp) }
        }
    }
}

/** 1:05:09 / 4:07 */
internal fun hms(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}
