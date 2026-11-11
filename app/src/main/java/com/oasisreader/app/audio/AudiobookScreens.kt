package com.oasisreader.app.audio

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private data class AudioItem(
    val id: Int?, val title: String, val author: String?, val libraryId: String?, val cover: String?, val progress: Double?,
    val completed: Boolean = false,
    val added: String? = null,       // latest_added (for merging several categories by date)
    val folder: String? = null,      // anchor_dir (for merging by folder name)
)
private data class AudioLibrary(val id: String, val name: String)

private fun JsonElement.toItem(): AudioItem? {
    val o = this as? JsonObject ?: return null
    val title = o.str("display_name", "series_alias", "series_name", "title", "name") ?: return null
    return AudioItem(
        id = o.int("representative_book_id", "audiobook_id", "book_id", "id", "first_book_id"),
        title = title,
        author = o.str("author", "artist"),
        // the list carries the category in series_key ("<library>:<hash>")
        libraryId = o.str("library_id") ?: o.str("series_key")?.substringBefore(':')?.takeIf { k -> k.all(Char::isDigit) },
        cover = o.str("cover_image", "cover", "cover_url", "thumbnail"),
        progress = o.dbl("total_progress_pct", "progress_pct", "progress"),
        completed = (o.int("is_completed") ?: 0) != 0,
        added = o.str("latest_added"),
        folder = o.str("anchor_dir"),
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
    // categories: several at once (bookoasis takes one, or all: several are picked from "all" in its order)
    var libIds by remember(server) {
        mutableStateOf(
            prefs.getStringSet("libs_${server.id}", null)?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: setOfNotNull(prefs.getString("library_${server.id}", null)?.toIntOrNull()),
        )
    }
    var readFilter by remember(server) { mutableStateOf(prefs.getString("read_${server.id}", "") ?: "") }   // "" | "listening" | "done"
    var sort by remember { mutableStateOf(runCatching { AudioSort.valueOf(prefs.getString("sort", null) ?: "") }.getOrDefault(AudioSort.NAME)) }
    var sortDesc by remember { mutableStateOf(prefs.getBoolean("sort_desc", false)) }
    var presets by remember(server) { mutableStateOf(com.oasisreader.app.meta.CatalogPresets.load(context, server.id)) }
    var savingPreset by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var active by remember { mutableStateOf("") }
    // what the search looks in: bookoasis takes a prefix ("작가:", "주제:"); none = the title
    var field by remember { mutableStateOf(prefs.getString("search_field", "title") ?: "title") }
    val items = remember(server) { mutableStateListOf<AudioItem>() }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var total by remember { mutableStateOf<Int?>(null) }
    val totals = remember(server) { HashMap<String, Int>() }   // all that match, when the server says and no app-side filter applies
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
    fun keep(it: AudioItem): Boolean =
        when (readFilter) { "done" -> it.completed; "listening" -> !it.completed; else -> true }
    // several categories: bookoasis takes one library_id, so each is asked on its own (OR) and the
    // results merged in the chosen order; each keeps its own next page (a merged page can be a
    // little out of order at the seams, never empty while any category has more)
    val cursors = remember(server) { HashMap<String, Int>() }       // library ("all" = none chosen) → next page
    val done = remember(server) { HashSet<String>() }
    val sortKey: (AudioItem) -> String = { a ->
        when (sort) {
            AudioSort.NAME -> a.title
            AudioSort.FOLDER -> a.folder ?: a.title
            AudioSort.ADDED -> a.added.orEmpty()
        }
    }
    suspend fun fetch(lib: String, pg: Int): Pair<List<AudioItem>, Boolean> {
        val o = web.get("/api/media/list", mapOf(
            "type" to "audiobook", "library_id" to lib,
            "search" to searchWithField(active, field),
            "sort" to sort.api(sortDesc), "page" to pg, "limit" to 40,
        ))
        val list = o.firstList("items", "series", "data", "list", "books").mapNotNull { it.toItem() }
        if (pg == 1) {
            val pgo = o["pagination"] as? JsonObject
            (o.int("total", "total_count", "totalCount", "count") ?: pgo?.int("total", "total_count", "count"))?.let { totals[lib] = it }
        }
        val more = (o["has_more"] as? JsonPrimitive)?.contentOrNull == "true" || (o["has_more"] == null && list.size >= 40)
        return list to more
    }
    /** p == 1: start over; otherwise one more round from every category that still has more. */
    suspend fun load(p: Int) {
        loading = true; error = null
        try {
            val libs = if (libIds.isEmpty()) listOf("all") else libIds.sorted().map { it.toString() }
            if (p == 1) { items.clear(); cursors.clear(); done.clear(); totals.clear(); page = 0; libs.forEach { cursors[it] = 1 } }
            var added = 0
            var rounds = 0
            do {
                val batch = ArrayList<AudioItem>()
                for (lib in libs) {
                    if (lib in done) continue
                    val pg = cursors[lib] ?: 1
                    val (list, more) = fetch(lib, pg)
                    batch += list.filter(::keep)
                    cursors[lib] = pg + 1
                    if (!more) done += lib
                }
                val cmp = compareBy<AudioItem, String>(com.oasisreader.app.ui.ShelfSearch.natural) { sortKey(it) }
                val sorted = if (libs.size > 1) batch.sortedWith(if (sortDesc) cmp.reversed() else cmp) else batch
                items.addAll(sorted); added += sorted.size
                page++
                rounds++
                // the 읽음 filter can leave a round empty: read on a little, then stop (more stays offered)
            } while (added < 24 && done.size < libs.size && rounds < 6)
            hasMore = done.size < libs.size
            total = if (readFilter.isEmpty() && totals.keys.containsAll(libs)) libs.sumOf { totals[it] ?: 0 } else null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally { loading = false }
    }
    LaunchedEffect(server, libIds, readFilter, active, field, sort, sortDesc) { load(1) }

    BackHandler { if (open != null) open = null else onBack() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(server.name.ifBlank { "오디오북" }, onBack = { if (open != null) open = null else onBack() }) {
            com.oasisreader.app.ui.EmojiButton("⚙️", "서버 설정", onOpenSettings)
            if (AudioPlayback.book != null) com.oasisreader.app.ui.EmojiButton("🎧", "재생 중", onPlayer)
        }
        val o = open
        if (o != null) AudiobookDetail(web, o, onPlayer) else AudioShelf(
            filters = {
                fun setLibs(v: Set<Int>) { libIds = v; prefs.edit().putStringSet("libs_${server.id}", v.map { it.toString() }.toSet()).apply() }
                com.oasisreader.app.meta.PresetChip(
                    presets = presets, libs = libIds, formats = emptySet(),
                    onPick = { p -> setLibs(p?.libraries?.toSet() ?: emptySet()) },
                    onSaveCurrent = { savingPreset = true },
                )
                if (savingPreset) com.oasisreader.app.meta.PresetDialog(
                    initial = com.oasisreader.app.meta.CatalogPreset("", libIds.sorted()),
                    libraries = null,
                    onSave = { p -> presets = presets + p; com.oasisreader.app.meta.CatalogPresets.save(context, server.id, presets); savingPreset = false },
                    onDelete = null,
                    onDismiss = { savingPreset = false },
                )
                SearchFieldChip(field) { field = it; prefs.edit().putString("search_field", it).apply() }
                CategoryChip(libraries, libIds) { setLibs(it) }
                ReadStateChip(readFilter) { readFilter = it; prefs.edit().putString("read_${server.id}", it).apply() }
                com.oasisreader.app.ui.SortChip(
                    options = AudioSort.entries, selected = sort, desc = sortDesc,
                    label = { it.label }, kind = { it.kind }, defaultDesc = { it.defaultDesc },
                    short = { when (it) { AudioSort.FOLDER -> "폴더"; AudioSort.ADDED -> "추가일"; else -> it.label } },
                    onChange = { o, d -> sort = o; sortDesc = d; prefs.edit().putString("sort", o.name).putBoolean("sort_desc", d).apply() },
                )
            },
            query = query, onQuery = { query = it }, onSearch = { active = query.trim() },
            items, hasMore, loading, page, error, web,
            count = when {
                loading && items.isEmpty() -> null
                total != null -> "🎧 ${"%,d".format(total)}권"
                items.isNotEmpty() || !loading -> "🎧 " + com.oasisreader.app.ui.loadedCount(items.size, hasMore, "권")
                else -> null
            },
            load = { p -> scope.launch { load(p) } },
            onOpen = { open = it },
        )
    }
}

@Composable
private fun AudioShelf(
    filters: @Composable () -> Unit,
    query: String, onQuery: (String) -> Unit, onSearch: () -> Unit,
    items: List<AudioItem>, hasMore: Boolean, loading: Boolean, page: Int, error: String?, web: BookOasisWeb,
    count: String?,
    load: (Int) -> Unit, onOpen: (AudioItem) -> Unit,
) {
    Column {
        com.oasisreader.app.ui.CompactSearchField(
            value = query, onValueChange = onQuery, placeholder = "검색 (🔍 칩에서 제목·작가·주제)", onSearch = onSearch,
            onClear = onSearch,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(com.oasisreader.app.ui.MiniChipGap),
            verticalAlignment = Alignment.CenterVertically,
        ) { filters() }
        com.oasisreader.app.ui.CountLine(count)
        when {
            error != null && items.isEmpty() -> Column(Modifier.padding(24.dp)) {
                Text("⚠️ $error", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { load(1) }) { Text("다시 시도") }
            }
            loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            // nothing yet but more to look through (the 읽음 filter): offer to read on, never a dead end
            items.isEmpty() && hasMore -> Column(Modifier.padding(24.dp)) {
                Text("아직 맞는 오디오북을 못 찾았습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { load(page + 1) }) { Text("🔎 더 찾아보기") }
            }
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
                        Text((if (it.completed) "✅ " else "") + it.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
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
    val rowId = AudioLibraryLink.bookId(web.server.id, b?.id ?: item.id ?: -1)
    val trackState = rememberTrackStates(rowId)
    val askNotif = rememberNotificationAsk()
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
                                        .onSuccess { android.widget.Toast.makeText(context, "서재에 담았습니다", android.widget.Toast.LENGTH_SHORT).show() }
                                        .onFailure { com.oasisreader.app.ui.Notice.show(context, "담지 못했습니다: ${it.message}") }
                                }
                            }, contentPadding = pad) { Text("📚 서재+", maxLines = 1, softWrap = false) }
                            else Text("📚 서재에 있음", maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                        // 오프라인: the tracks on the phone (puts it on the 서재 first)
                        val dl = AudioDownloads.progress[rowId]
                        val dlErr = AudioDownloads.errors[rowId]
                        val kept = onShelf?.let { it.pinned && it.fileBytes > 0 } == true
                        dlErr?.let { Text("⚠️ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
                        when {
                            dl != null || AudioDownloads.isPaused(context, rowId) -> Row {
                                if (AudioDownloads.isPaused(context, rowId)) TextButton(onClick = { AudioDownloads.resume(context, rowId) }) { Text("▶️ 이어 받기") }
                                else TextButton(onClick = { AudioDownloads.pause(context, rowId) }) { Text("⏸️ 받는 중 ${((dl ?: 0f) * 100).toInt()}%") }
                                TextButton(onClick = { AudioDownloads.cancel(context, rowId) }) { Text("✖️ 취소") }
                            }
                            kept -> Text("⬇️ 폰에 받음 (오프라인으로 들을 수 있음)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                            else -> TextButton(onClick = {
                                scope.launch {
                                    runCatching { onShelf ?: AudioLibraryLink.add(context, web, b, pct, completed) }
                                        .onSuccess { row ->
                                            askNotif()
                                            AudioDownloads.enqueue(context, row, book = b)
                                            com.oasisreader.app.ui.Notice.show(context, "받기를 시작했습니다. 앱을 닫아도 계속 받고, 알림에서 진행을 볼 수 있습니다.")
                                        }
                                        .onFailure { com.oasisreader.app.ui.Notice.show(context, "받지 못했습니다: ${it.message}") }
                                }
                            }) { Text(if ((onShelf?.fileBytes ?: 0) > 0) "⬇️ 나머지 트랙도 받기" else "⬇️ 폰에 받기 (오프라인)") }
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
                    val (st, frac) = trackState(t.id)
                    TrackDownloadButton(
                        st, frac,
                        onDownload = {
                            askNotif()
                            scope.launch {
                                runCatching { onShelf ?: AudioLibraryLink.add(context, web, b, pct, completed) }
                                    .onSuccess { row -> AudioDownloads.enqueue(context, row, listOf(t.id), book = b) }
                                    .onFailure { com.oasisreader.app.ui.Notice.show(context, "받지 못했습니다: ${it.message}") }
                            }
                        },
                        onCancel = { AudioDownloads.dequeueTrack(context, rowId, t.id) },
                        onRemove = { scope.launch { AudioDownloads.removeTrack(context, rowId, t.id) } },
                    )
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
    // the top bar fades out 2.5 s after the last touch (its place stays, so nothing moves under
    // the finger); any touch brings it back, and while hidden its buttons do not react
    var barShown by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(barShown, touchedAt, b) {
        if (b == null || !barShown) return@LaunchedEffect
        kotlinx.coroutines.delay(2_500)
        barShown = false
    }
    val barAlpha by androidx.compose.animation.core.animateFloatAsState(if (barShown) 1f else 0f, label = "bar")
    var picking by remember { mutableStateOf(false) }
    // The place in the track changes twice a second: only the small pieces below that show it
    // (BookPlaceLine, PlayerHeadline, PlayerSeek) read it, so the rest of the screen stays put.
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                barShown = true; touchedAt = System.currentTimeMillis()
            }
        },
    ) {
        Box {
            Box(Modifier.graphicsLayer { alpha = barAlpha }) {
                TopBar("재생 중", onBack = onBack) {
                    if (b != null) com.oasisreader.app.ui.EmojiButton("⏹️", "듣기 끝내기") { AudioPlayback.close(context); onBack() }
                }
                // the whole book's place (TODO 34), part of the bar: it fades out with it
                if (b != null) Box(
                    Modifier.align(Alignment.BottomEnd).height(56.dp).padding(end = 54.dp),
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
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            val coverMod = Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f)
            val localCover = b.artworkFile?.let { java.io.File(it) }?.takeIf { it.isFile }
            when {
                // 소장 오디오북 (and books with a 서재 cover): the phone's copy
                localCover != null && (b.own || web == null) -> com.oasisreader.app.ui.BookCover(localCover, b.title, coverMod)
                web != null -> RemoteCover(b.coverUrl, b.title, { u -> web.bytes(u) }, coverMod)
                else -> Box(coverMod, contentAlignment = Alignment.Center) { Text("🎧", fontSize = 64.sp) }
            }
            Spacer(Modifier.height(10.dp))
            PlayerHeadline(b) { picking = true }
            AudioPlayback.error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
            Text("  ▾", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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

/** 속도 · 수면 타이머. */
@Composable
private fun PlayerChips() {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var sOpen by remember { mutableStateOf(false) }
        Box {
            AssistChip(onClick = { sOpen = true }, label = { Text("속도 ${AudioPlayback.speed}배") })
            DropdownMenu(sOpen, { sOpen = false }) {
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
            DropdownMenu(tOpen, { tOpen = false }) {
                listOf(15, 30, 45, 60, 90).forEach { m -> DropdownMenuItem(text = { Text("${m}분 뒤 멈춤") }, onClick = { tOpen = false; AudioPlayback.sleepIn(m) }) }
                DropdownMenuItem(text = { Text("이 트랙 끝에서 멈춤") }, onClick = { tOpen = false; AudioPlayback.sleepAtEndOfTrack() })
                DropdownMenuItem(text = { Text("타이머 끄기") }, onClick = { tOpen = false; AudioPlayback.sleepIn(0) })
            }
        }
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
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "음량" }, verticalAlignment = Alignment.CenterVertically) {
        Text("🔈", fontSize = 14.sp)
        ThinSlider(
            value = vol.toFloat(), range = 0f..max.toFloat(),
            onChange = { v ->
                val n = Math.round(v).coerceIn(0, max)
                if (n != vol) { vol = n; runCatching { am.setStreamVolume(stream, n, 0) } }
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text("🔊", fontSize = 14.sp)
    }
}

/** The 장 (or track) list in a popup: the one playing is marked and in view, a tap jumps there (TODO 35). */
@Composable
private fun PlacePicker(b: AudioBook, onClose: () -> Unit) {
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
        Row(Modifier.padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            // a small cover: the 서재 copy when there is one (offline too), else the server's
            val context = LocalContext.current
            val local = b.artworkFile?.let { java.io.File(it) }?.takeIf { it.isFile }
            val coverMod = Modifier.padding(vertical = 6.dp).size(40.dp).clip(RoundedCornerShape(6.dp))
            val w = AudioPlayback.web
            when {
                local != null -> com.oasisreader.app.ui.BookCover(local, b.title, coverMod)
                w != null && b.coverUrl != null -> RemoteCover(b.coverUrl, b.title, { u -> w.bytes(u) }, coverMod)
                else -> Text("🎧", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(b.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${AudioPlayback.trackIndex + 1} / ${b.tracks.size} · ${hms(AudioPlayback.positionMs / 1000.0)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { AudioPlayback.toggle() }) { Text(if (AudioPlayback.playing) "⏸" else "▶", fontSize = 20.sp) }
            // stop and put the player away (the place is saved)
            IconButton(onClick = { AudioPlayback.close(context) }, modifier = Modifier.semantics { contentDescription = "재생 끝내기" }) {
                Text("✕", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 1:05:09 / 4:07 */
internal fun hms(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

/** Sorts bookoasis offers for its lists (repositories/series_list_options.py). */
private enum class AudioSort(val label: String, val kind: com.oasisreader.app.ui.SortKind, val defaultDesc: Boolean) {
    NAME("이름", com.oasisreader.app.ui.SortKind.TEXT, false),
    FOLDER("폴더 이름", com.oasisreader.app.ui.SortKind.TEXT, false),
    ADDED("추가한 날짜", com.oasisreader.app.ui.SortKind.DATE, true);

    fun api(desc: Boolean) = when (this) {
        NAME -> if (desc) "desc" else "asc"
        FOLDER -> if (desc) "folder_desc" else "folder_asc"
        ADDED -> if (desc) "date_desc" else "date_asc"
    }
}

/** Categories with check boxes (none = 전체), like the book catalog. */
@Composable
private fun CategoryChip(libraries: List<AudioLibrary>, selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        com.oasisreader.app.ui.MiniChip(
            "🗂️ " + when (selected.size) {
                0 -> "전체"
                1 -> libraries.firstOrNull { it.id.toIntOrNull() in selected }?.name ?: "1개"
                else -> "${selected.size}개"
            },
            selected = selected.isNotEmpty(), onClick = { open = true },
            description = "카테고리",
        )
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text("전체") },
                leadingIcon = { androidx.compose.material3.Checkbox(checked = selected.isEmpty(), onCheckedChange = null) },
                onClick = { onChange(emptySet()) },
            )
            HorizontalDivider()
            libraries.forEach { l ->
                val id = l.id.toIntOrNull() ?: return@forEach
                DropdownMenuItem(
                    text = { Text(l.name) },
                    leadingIcon = { androidx.compose.material3.Checkbox(checked = id in selected, onCheckedChange = null) },
                    onClick = { onChange(if (id in selected) selected - id else selected + id) },
                )
            }
            DropdownMenuItem(text = { Text("완료", color = MaterialTheme.colorScheme.primary) }, onClick = { open = false })
        }
    }
}

/** 전체 / 듣는 중·안 들음 / 다 들음 (bookoasis marks a book done when all of it was heard). */
@Composable
private fun ReadStateChip(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf("" to "전체", "listening" to "다 듣지 않음", "done" to "다 들음")
    Box {
        com.oasisreader.app.ui.MiniChip(
            "🔖 " + (options.firstOrNull { it.first == value }?.second ?: "전체"),
            selected = value.isNotEmpty(), onClick = { open = true },
        )
        DropdownMenu(open, { open = false }) {
            options.forEach { (v, label) ->
                DropdownMenuItem(text = { Text(label) }, leadingIcon = { com.oasisreader.app.ui.CheckMark(v == value) }, onClick = { open = false; onChange(v) })
            }
        }
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
                Text("☁️ 북오아시스: ${where(c.serverIdx, c.serverSec)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { AudioPlayback.choosePlace(useServer = false) }) { Text("이 폰 위치") } },
        dismissButton = { TextButton(onClick = { AudioPlayback.choosePlace(useServer = true) }) { Text("북오아시스 위치") } },
    )
}

private val SEARCH_FIELDS = listOf("title" to "제목", "author" to "작가", "topic" to "주제")

/**
 * bookoasis searches the title unless the text starts with a prefix (작가: / 주제: / author: …),
 * so the chosen field becomes that prefix; a prefix typed by hand is left as it is.
 */
internal fun searchWithField(text: String, field: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val typed = Regex("^[^\\s:：]{1,8}[:：]").containsMatchIn(t)
    return when {
        typed || field == "title" -> t
        field == "author" -> "작가:$t"
        field == "topic" -> "주제:$t"
        else -> t
    }
}

/** 🔍 제목 / 작가 / 주제: where the search looks. */
@Composable
private fun SearchFieldChip(field: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        com.oasisreader.app.ui.MiniChip(
            "🔍 " + (SEARCH_FIELDS.firstOrNull { it.first == field }?.second ?: "제목"),
            onClick = { open = true }, selected = field != "title", description = "검색 대상",
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SEARCH_FIELDS.forEach { (k, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label + "에서 찾기") },
                    leadingIcon = { com.oasisreader.app.ui.CheckMark(k == field) },
                    onClick = { open = false; onPick(k) },
                )
            }
        }
    }
}
