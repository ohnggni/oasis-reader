package com.oasisreader.app.meta

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import com.oasisreader.app.ui.grouped
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.oasisreader.app.comic.ComicParser
import com.oasisreader.app.data.Downloads
import com.oasisreader.app.data.Server
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.opds.OpdsClient
import com.oasisreader.app.ui.EmojiButton
import com.oasisreader.app.ui.RemoteCover
import com.oasisreader.app.ui.TopBar
import kotlinx.coroutines.launch

private enum class SeriesSort(val label: String, val kind: com.oasisreader.app.ui.SortKind, val defaultDesc: Boolean) {
    DATE("올라온 날짜", com.oasisreader.app.ui.SortKind.DATE, true),
    ADDED("서버에 추가된 날짜", com.oasisreader.app.ui.SortKind.DATE, true),
    NAME("이름", com.oasisreader.app.ui.SortKind.TEXT, false);

    fun api(desc: Boolean): String = when (this) {
        DATE -> if (desc) "mtime_desc" else "mtime_asc"
        ADDED -> if (desc) "added_desc" else "added_asc"
        NAME -> if (desc) "name_desc" else "name"
    }
}

/** "2022-05-20T01:45:53Z" -> "2022.5.20" in local time */
internal fun metaDate(s: String?): String? {
    if (s.isNullOrBlank()) return null
    return runCatching {
        val d = java.time.Instant.parse(s).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        "${d.year}.${d.monthValue}.${d.dayOfMonth}"
    }.getOrNull()
}

/** 📖 book · 🖼️ comic · 📄 PDF, from the file format. */
internal fun formatEmoji(format: String?): String = when (format?.lowercase()) {
    "cbz", "zip", "cbr", "rar", "cb7", "7z" -> "🖼️"
    "pdf" -> "📄"
    null, "" -> "📦"
    else -> "📖"
}

private fun mb(bytes: Long) = if (bytes <= 0) null else "%.0f MB".format(bytes / 1048576.0)

/**
 * The bookoasis catalog as it should be: libraries → series (newest first by real file date)
 * → volumes with clean titles. Data from oasis-meta, files from bookoasis.
 */
/** 📂 있던 폴더 열기: the series the meta catalog of that server should open with, once. */
object MetaCatalogNav {
    val pending = HashMap<String, MetaSeries>()
}

@Composable
fun MetaCatalogScreen(
    serverId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpdsFolders: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val server = remember(serverId) { ServerSettings(app).get(serverId) ?: Server("", "OPDS", "", "", "") }
    val api = remember(server) { MetaApi(server) }
    val opds = remember(server) { OpdsClient(server) }
    val prefs = remember { app.getSharedPreferences("meta_catalog", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(app) }

    var libraries by remember { mutableStateOf<List<MetaLibrary>?>(null) }
    val libNames: Map<Int, String> = libraries.orEmpty().associate { it.id to it.name }
    var libError by remember { mutableStateOf<String?>(null) }
    // chosen categories (none = 전체); older versions kept a single one under library_<server>
    var libIds by remember {
        mutableStateOf(
            prefs.getStringSet("libraries_$serverId", null)?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: setOfNotNull(prefs.getInt("library_$serverId", -1).takeIf { it >= 0 }),
        )
    }
    val libParam = libIds.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")
    // file formats (none = 전체), combined with the categories (both must match)
    var fmtKeys by remember {
        mutableStateOf(prefs.getStringSet("formats_$serverId", null)?.filter { k -> CatalogFormats.ALL.any { it.key == k } }?.toSet() ?: emptySet())
    }
    val fmtParam = CatalogFormats.param(fmtKeys)
    var presets by remember { mutableStateOf(CatalogPresets.load(app, serverId)) }
    var savingPreset by remember { mutableStateOf(false) }
    // older versions kept RECENT / OLDEST (date, newest or oldest first)
    val storedSort = prefs.getString("sort", null)
    var sort by remember {
        mutableStateOf(
            when (storedSort) { "RECENT", "OLDEST" -> SeriesSort.DATE; else -> runCatching { SeriesSort.valueOf(storedSort ?: "") }.getOrDefault(SeriesSort.DATE) },
        )
    }
    var sortDesc by remember {
        mutableStateOf(if (prefs.contains("sort_desc")) prefs.getBoolean("sort_desc", true) else storedSort != "OLDEST" && sort.defaultDesc)
    }
    var query by remember { mutableStateOf("") }
    var activeQuery by remember { mutableStateOf("") }
    var searchBooks by remember { mutableStateOf(false) }   // search volumes instead of series
    // series view: tiles (size adjustable) or a list, remembered per phone
    var seriesList by remember { mutableStateOf(prefs.getBoolean("series_list", false)) }
    var seriesTile by remember { mutableIntStateOf(prefs.getInt("series_tile", SERIES_TILE_DEFAULT).let { v -> SERIES_TILE_STEPS.minBy { kotlin.math.abs(it - v) } }) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var openSeries by remember { mutableStateOf<MetaSeries?>(MetaCatalogNav.pending.remove(serverId)) }   // 📂 있던 폴더 열기 opens a series
    var openBook by remember { mutableStateOf<MetaBook?>(null) }   // detail sheet over the list
    var reload by remember { mutableIntStateOf(0) }

    val seriesItems = remember { mutableStateListOf<MetaSeries>() }
    val bookItems = remember { mutableStateListOf<MetaBook>() }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var total by remember { mutableStateOf<Int?>(null) }        // all that match (from oasis-meta)
    var totalBooks by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        libError = null
        libraries = try { api.libraries() } catch (e: Exception) { libError = e.message; null }
    }

    suspend fun loadPage(p: Int) {
        loading = true; error = null
        try {
            if (activeQuery.isNotBlank() && searchBooks) {
                val r = api.books(libParam, null, activeQuery, "mtime_desc", p, limit = 100, format = fmtParam)
                if (p == 1) { bookItems.clear(); total = r.total; totalBooks = r.total }
                bookItems.addAll(r.items); hasMore = r.hasMore
            } else {
                val r = api.series(libParam, activeQuery.ifBlank { null }, sort.api(sortDesc), p, format = fmtParam)
                if (p == 1) { seriesItems.clear(); total = r.total; totalBooks = r.totalBooks }
                seriesItems.addAll(r.items); hasMore = r.hasMore
            }
            page = p
        } catch (e: Exception) {
            error = (e.message ?: e.javaClass.simpleName) +
                (if (libIds.size > 1 || fmtKeys.isNotEmpty()) "\n(여러 카테고리·형식 필터는 서버의 oasis-meta 최신 버전이 필요합니다)" else "")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(libParam, fmtParam, sort, sortDesc, activeQuery, searchBooks, reload) { loadPage(1) }

    val owned by remember { db.books().observeSourceUrls() }.collectAsState(initial = emptyList())
    val ownedSet = remember(owned) { owned.toSet() }
    val pinnedList by remember { db.books().observePinnedSourceUrls() }.collectAsState(initial = emptyList())
    val pinnedSet = remember(pinnedList) { pinnedList.toSet() }
    val progress by Downloads.progress.collectAsState()
    val store = remember { com.oasisreader.app.data.BookStore.get(app) }

    /** 바로 읽기: fetch into the cache and open (or just open). */
    fun readNow(b: MetaBook) {
        val href = api.downloadUrl(b) ?: return
        if (href in ownedSet) scope.launch { db.books().findBySource(null, href)?.let { onOpenBook(it.id) } }
        else Downloads.startUrl(app, server, api.key(b), href, b.name, pinned = false, onDone = { onOpenBook(it.id) }, origin = com.oasisreader.app.data.BookOrigin.meta(server.id, b.seriesName, b.libraryId))
    }

    /** 서재에 담기 / 보관: keep for good (a cached book with its file still here is just marked). */
    fun keep(b: MetaBook, quiet: Boolean = false, collectionId: String? = null) {
        val href = api.downloadUrl(b) ?: return
        if (href in ownedSet) scope.launch {
            val book = db.books().findBySource(null, href) ?: return@launch
            if (store.hasFiles(book.id)) {
                db.books().setPinned(book.id, true)
                if (!quiet) android.widget.Toast.makeText(context, "서재에 보관합니다: ${book.title}", android.widget.Toast.LENGTH_SHORT).show()
                collectionId?.let { com.oasisreader.app.ui.ShelfCollections.set(db, it, book.id, true) }
            } else Downloads.startUrl(app, server, api.key(b), href, b.name, collectionId = collectionId, origin = com.oasisreader.app.data.BookOrigin.meta(server.id, b.seriesName, b.libraryId))
        } else Downloads.startUrl(app, server, api.key(b), href, b.name, collectionId = collectionId, origin = com.oasisreader.app.data.BookOrigin.meta(server.id, b.seriesName, b.libraryId))
    }

    /**
     * A whole series to the 서재: the missing books are kept, and with [collection] every book
     * of the series on the 서재 (kept now or before) goes on the 컬렉션 of that name.
     */
    fun keepSeries(list: List<MetaBook>, collection: String?) {
        scope.launch {
            val col = collection?.let { com.oasisreader.app.ui.ShelfCollections.findOrCreate(db, it) }
            var n = 0
            list.forEach { b ->
                val href = api.downloadUrl(b) ?: return@forEach
                if (!b.readable) return@forEach
                if (href !in pinnedSet) { keep(b, quiet = true, collectionId = col?.id); n++ }
                else if (col != null) db.books().findBySource(null, href)?.let { com.oasisreader.app.ui.ShelfCollections.set(db, col.id, it.id, true) }
            }
            android.widget.Toast.makeText(
                context,
                (if (n > 0) "${n}권을 받습니다" else "모두 서재에 있습니다") + (col?.let { " · 📚 ${it.name}에 묶습니다" } ?: ""),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    var askSeries by remember { mutableStateOf<Pair<String, List<MetaBook>>?>(null) }
    askSeries?.let { (name, list) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askSeries = null },
            title = { Text("📚 컬렉션으로 묶을까요?") },
            text = { Text("「$name」 시리즈의 책을 서재에 담으면서 같은 이름의 컬렉션으로 묶습니다. 이미 서재에 있는 이 시리즈의 책도 함께 묶습니다.") },
            confirmButton = { TextButton(onClick = { askSeries = null; keepSeries(list, name) }) { Text("묶어서 받기") } },
            dismissButton = { TextButton(onClick = { askSeries = null; keepSeries(list, null) }) { Text("그냥 받기") } },
        )
    }

    fun openOrDownload(b: MetaBook) {
        val href = api.downloadUrl(b) ?: return
        if (href in ownedSet) {
            scope.launch { db.books().findBySource(null, href)?.let { onOpenBook(it.id) } }
        } else {
            Downloads.startUrl(app, server, api.key(b), href, b.name, origin = com.oasisreader.app.data.BookOrigin.meta(server.id, b.seriesName, b.libraryId))
        }
    }

    // ── series detail ─────────────────────────────────────────────
    @Composable
    fun BookDetailOverlay() {
        openBook?.let { b ->
            BookDetail(
                b, api, opds, owned = api.downloadUrl(b) in ownedSet, pinned = api.downloadUrl(b) in pinnedSet,
                progress = progress[api.key(b)],
                library = b.libraryId?.let { libNames[it] },
                onBack = { openBook = null },
                onRead = { readNow(b) },
                onKeep = { keep(b) },
            )
        }
    }

    openSeries?.let { s -> Box(Modifier.fillMaxSize()) {
        SeriesDetail(
            series = s, format = fmtParam, api = api, opds = opds, ownedSet = ownedSet, pinnedSet = pinnedSet, progress = progress,
            onBack = { openSeries = null },
            onBook = { openBook = it },
            onAction = ::openOrDownload,
            onDownloadAll = { list -> askSeries = s.seriesName to list },
        )
        BookDetailOverlay()
    }
        return
    }

    Box(Modifier.fillMaxSize()) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(server.name, onBack = onBack) {
            com.oasisreader.app.ui.HomeButton()
            EmojiButton("⚙️", "서버 설정", onOpenSettings)
        }
        com.oasisreader.app.ui.CatalogViewSwitch(organized = true, onOrganized = {}, onFolders = onOpdsFolders)
        com.oasisreader.app.ui.CompactSearchField(
            value = query,
            onValueChange = { query = it; if (it.isBlank()) activeQuery = "" },
            placeholder = "시리즈·제목·저자 검색",
            onSearch = {
                activeQuery = query.trim()
                // results take the screen: put the keyboard away
                keyboard?.hide(); focus.clearFocus()
            },
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(com.oasisreader.app.ui.MiniChipGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // categories: several at once (none = 전체), picked in a drop-down with check boxes
            fun setLibs(v: Set<Int>) { libIds = v; prefs.edit().putStringSet("libraries_$serverId", v.map { it.toString() }.toSet()).apply() }
            fun setFormats(v: Set<String>) { fmtKeys = v; prefs.edit().putStringSet("formats_$serverId", v).apply() }
            // saved combinations (made here or in the server settings)
            PresetChip(
                presets = presets, libs = libIds, formats = fmtKeys,
                onPick = { p -> setLibs(p?.libraries?.toSet() ?: emptySet()); setFormats(p?.formats?.toSet() ?: emptySet()) },
                onSaveCurrent = { savingPreset = true },
            )
            if (savingPreset) PresetDialog(
                initial = CatalogPreset("", libIds.sorted(), CatalogFormats.ALL.map { it.key }.filter { it in fmtKeys }),
                libraries = null,
                onSave = { p -> presets = presets + p; CatalogPresets.save(app, serverId, presets); savingPreset = false },
                onDelete = null,
                onDismiss = { savingPreset = false },
            )
            var libOpen by remember { mutableStateOf(false) }
            val libs = libraries.orEmpty()
            Box {
                com.oasisreader.app.ui.MiniChip(
                    "🗂️ " + when (libIds.size) {
                        0 -> "전체"
                        1 -> libs.firstOrNull { it.id in libIds }?.name ?: "1개"
                        else -> "${libIds.size}개"
                    },
                    selected = libIds.isNotEmpty(),
                    onClick = { libOpen = true },
                    description = "카테고리",
                )
                androidx.compose.material3.DropdownMenu(expanded = libOpen, onDismissRequest = { libOpen = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("전체") },
                        leadingIcon = { androidx.compose.material3.Checkbox(checked = libIds.isEmpty(), onCheckedChange = null) },
                        onClick = { setLibs(emptySet()) },
                    )
                    HorizontalDivider()
                    libs.forEach { l ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(l.name) },
                            leadingIcon = { androidx.compose.material3.Checkbox(checked = l.id in libIds, onCheckedChange = null) },
                            onClick = { setLibs(if (l.id in libIds) libIds - l.id else libIds + l.id) },
                        )
                    }
                    androidx.compose.material3.DropdownMenuItem(text = { Text("완료", color = MaterialTheme.colorScheme.primary) }, onClick = { libOpen = false })
                }
            }
            FormatFilterChip(fmtKeys) { setFormats(it) }
            if (!(activeQuery.isNotBlank() && searchBooks)) {
                com.oasisreader.app.ui.SortChip(
                    options = SeriesSort.entries, selected = sort, desc = sortDesc,
                    label = { it.label }, kind = { it.kind }, defaultDesc = { it.defaultDesc },
                    short = { when (it) { SeriesSort.DATE -> "올라온 날"; SeriesSort.ADDED -> "추가일"; else -> it.label } },
                    onChange = { o, d -> sort = o; sortDesc = d; prefs.edit().putString("sort", o.name).putBoolean("sort_desc", d).apply() },
                )
            }
        }
        // one line: how many (left) · 시리즈/권 · 타일·크기 (right)
        val countText = when {
            loading && seriesItems.isEmpty() && bookItems.isEmpty() -> null
            activeQuery.isNotBlank() && searchBooks -> total?.let { "📕 ${it.grouped()}권" } ?: "📕 " + com.oasisreader.app.ui.loadedCount(bookItems.size, hasMore, "권")
            total != null -> "📚 시리즈 ${total!!.grouped()}개" + (totalBooks?.let { " · ${it.grouped()}권" } ?: "")
            seriesItems.isNotEmpty() -> "📚 시리즈 " + com.oasisreader.app.ui.loadedCount(seriesItems.size, hasMore)
            else -> null
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(com.oasisreader.app.ui.MiniChipGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Text(
                    countText.orEmpty(), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (activeQuery.isNotBlank()) {
                com.oasisreader.app.ui.MiniChip("📚 시리즈", selected = !searchBooks, onClick = { searchBooks = false })
                com.oasisreader.app.ui.MiniChip("📕 권", selected = searchBooks, onClick = { searchBooks = true })
            }
            if (!(activeQuery.isNotBlank() && searchBooks)) {
                com.oasisreader.app.ui.MiniChip(
                    if (seriesList) "📃" else "🔲", plain = true,
                    onClick = { seriesList = !seriesList; prefs.edit().putBoolean("series_list", seriesList).apply() },
                    description = if (seriesList) "목록으로 보는 중 (누르면 타일)" else "타일로 보는 중 (누르면 목록)",
                )
                if (!seriesList) {
                    var sizeOpen by remember { mutableStateOf(false) }
                    Box {
                        com.oasisreader.app.ui.MiniChip("📐", onClick = { sizeOpen = true }, description = "타일 크기", plain = true)
                        androidx.compose.material3.DropdownMenu(expanded = sizeOpen, onDismissRequest = { sizeOpen = false }) {
                            Column(Modifier.width(260.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text("타일 크기", style = MaterialTheme.typography.labelLarge)
                                androidx.compose.material3.Slider(
                                    value = SERIES_TILE_STEPS.indexOf(seriesTile).toFloat(),
                                    onValueChange = {
                                        seriesTile = SERIES_TILE_STEPS[Math.round(it).coerceIn(0, SERIES_TILE_STEPS.size - 1)]
                                        prefs.edit().putInt("series_tile", seriesTile).apply()
                                    },
                                    valueRange = 0f..(SERIES_TILE_STEPS.size - 1).toFloat(),
                                    steps = SERIES_TILE_STEPS.size - 2,
                                )
                                Row {
                                    Text("작게", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.weight(1f))
                                    Text("크게", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

            }
        }

        val err = error ?: libError
        // how many match: from the server when it says, else what is loaded so far
        // where the search looked: the chosen categories, or all of them (same-named books, e.g. a novel
        // and a comic, are told apart by the 📁 line; narrowing to one category finds only that one)
        if (activeQuery.isNotBlank()) Text(
            if (libIds.isEmpty()) "📁 모든 카테고리에서 찾았습니다 · 카테고리 칩으로 좁힐 수 있습니다"
            else "📁 " + libIds.mapNotNull { libNames[it] }.joinToString(", ").ifBlank { "고른 카테고리" } + " 안에서 찾았습니다",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
        )
        val booksMode = activeQuery.isNotBlank() && searchBooks
        when {
            err != null && seriesItems.isEmpty() && bookItems.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("⚠️ $err", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { reload++ }) { Text("다시 시도") }
                TextButton(onClick = onOpdsFolders) { Text("📂 OPDS 폴더로 보기") }
                TextButton(onClick = onOpenSettings) { Text("서버 설정") }
            }
            activeQuery.isNotBlank() && searchBooks -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(bookItems, key = { it.id }) { b ->
                    val href = api.downloadUrl(b)
                    BookRow(b, api, opds, owned = href in ownedSet, progress = progress[api.key(b)], showSeries = true,
                        library = b.libraryId?.let { libNames[it] },
                        onClick = { openBook = b }, onAction = { openOrDownload(b) })
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                item { MoreFooter(loading, hasMore, empty = bookItems.isEmpty()) { scope.launch { loadPage(page + 1) } } }
            }
            seriesList -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(seriesItems.size, key = { i -> seriesItems[i].libraryId.toString() + "|" + seriesItems[i].seriesName }) { i ->
                    val s = seriesItems[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { openSeries = s }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MetaCover(api, opds, s.coverUrl, s.firstBookId, s.seriesName, modifier = Modifier.width(48.dp).height(70.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.seriesName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(libNames[s.libraryId]?.let { "📁 $it" }, "${s.bookCount}권", metaDate(s.latestMtime)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                item { MoreFooter(loading, hasMore, empty = seriesItems.isEmpty()) { scope.launch { loadPage(page + 1) } } }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(seriesTile.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (seriesTile < 100) 8.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (seriesTile < 100) 12.dp else 16.dp),
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            ) {
                items(seriesItems.size, key = { i -> seriesItems[i].libraryId.toString() + "|" + seriesItems[i].seriesName }) { i ->
                    val s = seriesItems[i]
                    Column(Modifier.clickable { openSeries = s }) {
                        MetaCover(
                            api, opds, s.coverUrl, s.firstBookId, s.seriesName,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(com.oasisreader.app.ui.ShelfSearch.breakAnywhere(s.seriesName), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull("${s.bookCount}권", metaDate(s.latestMtime)).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    MoreFooter(loading, hasMore, empty = seriesItems.isEmpty()) { scope.launch { loadPage(page + 1) } }
                }
            }
        }
    }
    BookDetailOverlay()
    }
}

@Composable
private fun MoreFooter(loading: Boolean, hasMore: Boolean, empty: Boolean, onMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(28.dp))
            hasMore -> TextButton(onClick = onMore) { Text("더 보기") }
            empty -> Text("결과가 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SeriesDetail(
    series: MetaSeries,
    format: String?,
    api: MetaApi,
    opds: OpdsClient,
    ownedSet: Set<String>,
    pinnedSet: Set<String>,
    progress: Map<String, Float>,
    onBack: () -> Unit,
    onBook: (MetaBook) -> Unit,
    onAction: (MetaBook) -> Unit,
    onDownloadAll: (List<MetaBook>) -> Unit,
) {
    BackHandler(onBack = onBack)
    var books by remember { mutableStateOf<List<MetaBook>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(series, attempt) {
        error = null
        books = try {
            // volume order: natural sort of the clean titles ("2권" before "10권")
            api.books(series.libraryId.toString(), series.seriesName, null, "title", 1, 500, format = format).items
                .sortedWith { a, b -> ComicParser.naturalOrder.compare(a.name, b.name) }
        } catch (e: Exception) { error = e.message; null }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(series.seriesName, onBack = onBack) { com.oasisreader.app.ui.HomeButton() }
        val list = books
        when {
            error != null -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("⚠️ $error", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { attempt++ }) { Text("다시 시도") }
            }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val missing = list.count { b -> b.readable && api.downloadUrl(b)?.let { it !in pinnedSet } == true }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${list.size}권" + (metaDate(series.latestMtime)?.let { " · 최근 $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (missing > 1) OutlinedButton(onClick = { onDownloadAll(list) }) { Text("⬇️ 서재에 없는 ${missing}권 모두 담기") }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                    items(list, key = { it.id }) { b ->
                        BookRow(b, api, opds, owned = api.downloadUrl(b) in ownedSet, progress = progress[api.key(b)],
                            showSeries = false, onClick = { onBook(b) }, onAction = { onAction(b) })
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    b: MetaBook,
    api: MetaApi,
    opds: OpdsClient,
    owned: Boolean,
    progress: Float?,
    showSeries: Boolean,
    library: String? = null, // shown with the series: "📁 만화 › 괴력난신" (so same-named books can be told apart)
    onClick: () -> Unit,     // details
    onAction: () -> Unit,    // download / open
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaCover(api, opds, b.coverUrl, b.id, b.name, modifier = Modifier.width(48.dp).height(70.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(formatEmoji(b.fileFormat) + " " + b.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                if (showSeries) listOfNotNull(library, b.seriesName).joinToString(" › ").ifBlank { null }?.let { "📁 $it" } else null,
                metaDate(b.fileMtime),
                b.fileFormat?.uppercase(),
                mb(b.fileSize),
                b.pageCount?.let { "${it}쪽" },
            ).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        when {
            owned -> Box(Modifier.size(48.dp).clickable(onClick = onAction), contentAlignment = Alignment.Center) {
                Text("📖", style = MaterialTheme.typography.titleMedium)
            }
            b.readable -> com.oasisreader.app.ui.DownloadControl(api.key(b), progress, onAction, size = 44.dp)
            else -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}


/**
 * Cover from oasis-meta's cover_url when given; otherwise bookoasis's own thumbnail,
 * found once through an OPDS search (CoverResolver) and remembered.
 */
@Composable
private fun MetaCover(api: MetaApi, opds: OpdsClient, coverPath: String?, bookId: Long?, name: String, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val url by androidx.compose.runtime.produceState(
        api.absolute(coverPath) ?: bookId?.let { CoverResolver.cached(context, api.db, it) },
        coverPath, bookId,
    ) {
        if (value != null || bookId == null) return@produceState
        value = CoverResolver.resolve(context, api.server, api.db, bookId, name)
    }
    RemoteCover(url, name, load = { opds.bytes(it) }, modifier = modifier)
}

/** One volume's details: big cover, everything oasis-meta knows, and the download / read button. */
@Composable
private fun BookDetail(
    b: MetaBook,
    api: MetaApi,
    opds: OpdsClient,
    owned: Boolean,
    pinned: Boolean,
    progress: Float?,
    library: String? = null,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onKeep: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            // swallow touches so the list underneath can't be tapped through
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        TopBar(b.name, onBack = onBack) { com.oasisreader.app.ui.HomeButton() }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MetaCover(api, opds, b.coverUrl, b.id, b.name, modifier = Modifier.width(180.dp).aspectRatio(0.68f))
            Spacer(Modifier.height(16.dp))
            Text(b.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            b.seriesName?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            when {
                progress != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (progress < 0f) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    else CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (progress < 0f) "받는 중…" else "받는 중 ${(progress * 100).toInt()}%")
                }
                owned -> {
                    Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) { Text("📖 읽기") }
                    if (!pinned) {
                        OutlinedButton(onClick = onKeep, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("📌 서재에 보관 (지금은 임시)") }
                    }
                }
                b.readable -> {
                    Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) { Text("📖 바로 읽기") }
                    OutlinedButton(onClick = onKeep, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("⬇️ 서재에 담기" + (mb(b.fileSize)?.let { " ($it)" } ?: ""))
                    }
                }
                else -> Text("${b.fileFormat?.uppercase() ?: "이"} 형식은 아직 읽을 수 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            Summary(api, b.id)
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            InfoLine("저자", b.author?.ifBlank { null }?.let { "$it  (서버 데이터라 틀릴 수 있습니다)" })
            InfoLine("📁 위치", listOfNotNull(library, b.seriesName).joinToString(" › ").ifBlank { null })
            InfoLine("형식", b.fileFormat?.uppercase())
            InfoLine("용량", mb(b.fileSize))
            InfoLine("쪽수", b.pageCount?.let { "${it}쪽" })
            InfoLine("올라온 날", metaDate(b.fileMtime))
            InfoLine("파일명", b.fileName)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * Synopsis from /books/{id}. "ok" is shown as is; "suspect" (taken from a Naver shopping search
 * candidate, often another book) stays folded behind a warning; none -> nothing at all.
 */
@Composable
private fun Summary(api: MetaApi, id: Long) {
    var state by remember(id) { mutableStateOf<MetaBook?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    LaunchedEffect(id) {
        state = runCatching { api.book(id) }.getOrNull()
        loading = false
    }
    val text = state?.summary?.trim()?.ifBlank { null }
    when {
        loading -> Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        text == null -> Unit
        else -> {
            val suspect = state?.summaryTrust == "suspect"
            var open by remember(id) { mutableStateOf(!suspect) }
            var expanded by remember(id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                Text("줄거리", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                if (!open) {
                    Text(
                        "⚠️ 다른 책 소개가 잘못 들어갔을 수 있습니다",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) { Text("그래도 보기") }
                } else {
                    if (suspect) {
                        Text(
                            "⚠️ 다른 책 소개일 수 있습니다",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    val long = text.length > 400
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (long && !expanded) 10 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = long) { expanded = !expanded },
                    )
                    if (long) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (expanded) "접기" else "더 보기")
                    }
                }
            }
        }
    }
}

private val SERIES_TILE_STEPS = listOf(80, 96, 112, 130, 150, 176)
private const val SERIES_TILE_DEFAULT = 112
