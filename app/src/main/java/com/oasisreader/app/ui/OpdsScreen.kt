package com.oasisreader.app.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.Downloads
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.opds.OpdsClient
import com.oasisreader.app.opds.OpdsEntry
import com.oasisreader.app.opds.OpdsFeed
import kotlinx.coroutines.launch

private sealed interface FeedState {
    data object Loading : FeedState
    data class Ready(val feed: OpdsFeed) : FeedState
    data class Failed(val message: String) : FeedState
}

/** Browse / search the bookoasis catalog and pull books into the library. */
@Composable
fun OpdsScreen(
    serverId: String, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenBook: (String) -> Unit,
    onOrganized: (() -> Unit)? = null,   // the server has oasis-meta: offer the series view
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val server = remember(serverId) { ServerSettings(app).get(serverId) ?: com.oasisreader.app.data.Server("", "OPDS", "", "", "") }
    val client = remember(server) { OpdsClient(server) }
    val db = remember { AppDatabase.get(app) }
    val scope = rememberCoroutineScope()

    if (!server.isComplete) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TopBar(server.name, onBack = onBack)
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("「${server.name}」 주소나 로그인 정보가 아직 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenSettings) { Text("연결 설정하기") }
            }
        }
        BackHandler(onBack = onBack)
        return
    }

    // navigation stack of catalog pages
    // 📂 있던 폴더 열기 (from a book) starts right in that folder
    val startAt = remember(serverId) { OpdsFavorites.pending.remove(serverId)?.takeIf { it.urls.isNotEmpty() && it.urls.size == it.labels.size } }
    val stack = remember { mutableStateListOf(*(startAt?.urls ?: listOf(server.url)).toTypedArray()) }
    val labels = remember { mutableStateListOf(*(startAt?.labels ?: listOf(server.name)).toTypedArray()) }   // folder name shown for each stack level
    /** This folder, as the 출처 of a book received here (다음 권 / 있던 폴더 열기). */
    fun here(): String? = com.oasisreader.app.data.BookOrigin.opds(server.id, stack.toList(), labels.toList())
    fun open(url: String, label: String) { stack.add(url); labels.add(label) }
    fun popTo(level: Int) { while (stack.size > level + 1) { stack.removeAt(stack.size - 1); labels.removeAt(labels.size - 1) } }
    var state by remember { mutableStateOf<FeedState>(FeedState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    val extra = remember { mutableStateListOf<OpdsEntry>() }   // "more" pages appended
    var nextUrl by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var rootSearch by remember { mutableStateOf<OpdsFeed?>(null) }   // feed that carries the search link
    var detail by remember { mutableStateOf<OpdsEntry?>(null) }
    val sortPrefs = remember { app.getSharedPreferences("catalog", android.content.Context.MODE_PRIVATE) }
    var sort by remember {
        mutableStateOf(
            when (val v = sortPrefs.getString("sort", null)) {
                "NEWEST", "OLDEST" -> CatalogSort.DATE      // older versions: one entry per direction
                else -> runCatching { CatalogSort.valueOf(v ?: "") }.getOrDefault(CatalogSort.SERVER)
            },
        )
    }
    var sortDesc by remember {
        mutableStateOf(
            if (sortPrefs.contains("sort_desc")) sortPrefs.getBoolean("sort_desc", false)
            else sortPrefs.getString("sort", null) == "NEWEST",
        )
    }
    var loadingAll by remember { mutableStateOf<Int?>(null) }   // entries loaded so far while fetching every page
    var sortCapped by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(sortPrefs.getBoolean("grid", true)) }   // book covers as a grid         // page limit hit: stop auto-fetching

    val progress by Downloads.progress.collectAsState()
    val owned by remember { db.books().observeOpdsIds() }.collectAsState(initial = emptyList())
    val ownedSet = remember(owned) { owned.toSet() }

    val current = stack.last()
    LaunchedEffect(current, attempt) {
        state = FeedState.Loading
        extra.clear(); nextUrl = null; sortCapped = false
        state = try {
            val feed = client.fetch(current)
            nextUrl = feed.next
            if (feed.searchHref != null && (rootSearch == null || stack.size == 1)) rootSearch = feed
            FeedState.Ready(feed)
        } catch (e: Exception) {
            FeedState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // a client-side sort needs the whole list: fetch the remaining pages (bounded)
    LaunchedEffect(sort, sortDesc, nextUrl, state) {
        if ((sort == CatalogSort.SERVER && !sortDesc) || state !is FeedState.Ready || loadingMore || sortCapped) return@LaunchedEffect
        var next = nextUrl ?: return@LaunchedEffect
        val ready = state as FeedState.Ready
        var pages = 0
        try {
            while (pages < MAX_SORT_PAGES) {
                loadingAll = ready.feed.entries.size + extra.size
                val f = client.fetch(next)
                extra.addAll(f.entries)
                pages++
                next = f.next ?: break
            }
            if (pages >= MAX_SORT_PAGES) { sortCapped = true; nextUrl = next } else nextUrl = null
        } catch (e: Exception) {
            com.oasisreader.app.ui.Notice.show(context, "목록 전체를 받지 못했습니다: ${e.message}")
        } finally {
            loadingAll = null
        }
    }

    fun back() { if (stack.size > 1) popTo(stack.size - 2) else onBack() }
    BackHandler(onBack = ::back)

    // ⭐ 즐겨찾기 and the last folder seen (OpdsFavorites): the whole path is kept
    var favorites by remember(serverId) { mutableStateOf(OpdsFavorites.all(app, serverId)) }
    var lastPlace by remember(serverId) { mutableStateOf(OpdsFavorites.last(app, serverId)) }
    var editFavorites by remember { mutableStateOf(false) }
    fun goTo(p: OpdsFavorites.Place) {
        if (p.urls.isEmpty()) return
        stack.clear(); stack.addAll(p.urls); labels.clear(); labels.addAll(p.labels)
    }
    LaunchedEffect(current) {
        if (stack.size > 1 && labels.lastOrNull()?.startsWith("검색:") != true) OpdsFavorites.setLast(app, serverId, stack.toList(), labels.toList())
    }
    val isFav = favorites.any { it.urls.lastOrNull() == current }

    // ⬇️ 이 폴더 모두 담기 → 컬렉션으로 묶을지 묻기
    var askFolder by remember { mutableStateOf<Pair<String, List<OpdsEntry>>?>(null) }
    var gatheringAll by remember { mutableStateOf(false) }
    fun keepFolder(name: String, list: List<OpdsEntry>, collection: Boolean) {
        scope.launch {
            val col = if (collection) ShelfCollections.findOrCreate(db, name) else null
            var n = 0
            list.forEach { e ->
                val already = db.books().findBySource(e.id, null)
                if (already != null) {
                    if (!already.pinned) db.books().setPinned(already.id, true)
                    col?.let { ShelfCollections.set(db, it.id, already.id, true) }
                } else {
                    n++
                    Downloads.start(app, server, e, collectionId = col?.id, origin = here())
                }
            }
            android.widget.Toast.makeText(
                context, (if (n > 0) "${n}권을 받습니다" else "모두 서재에 있습니다") + (col?.let { " · 📚 ${it.name}에 묶습니다" } ?: ""),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    /** Every book of this folder (all its pages), then ask about the 컬렉션. */
    fun downloadFolder(ready: FeedState.Ready) {
        if (gatheringAll) return
        gatheringAll = true
        scope.launch {
            try {
                var next = nextUrl
                var pages = 0
                while (next != null && pages < MAX_SORT_PAGES) {
                    val f = client.fetch(next)
                    extra.addAll(f.entries); pages++
                    next = f.next
                }
                nextUrl = next
                val books = (ready.feed.entries + extra).filter { it.isBook && it.readable != null }.distinctBy { it.id }
                askFolder = labels.last() to books
            } catch (e: Exception) {
                com.oasisreader.app.ui.Notice.show(context, "목록 전체를 받지 못했습니다: ${e.message}")
            } finally { gatheringAll = false }
        }
    }
    askFolder?.let { (name, list) ->
        val missing = list.count { it.id !in ownedSet }
        AlertDialog(
            onDismissRequest = { askFolder = null },
            title = { Text("⬇️ 「$name」 모두 담기") },
            text = {
                Text(
                    "이 폴더의 책 ${list.size}권 중 서재에 없는 ${missing}권을 받습니다.\n" +
                        "같은 이름의 📚 컬렉션으로 묶을까요? (이미 서재에 있는 이 폴더 책도 함께 묶습니다)",
                )
            },
            confirmButton = { TextButton(onClick = { askFolder = null; keepFolder(name, list, true) }) { Text("묶어서 받기") } },
            dismissButton = { TextButton(onClick = { askFolder = null; keepFolder(name, list, false) }) { Text("그냥 받기") } },
        )
    }
    if (editFavorites) FavoritesEditDialog(
        favorites,
        onChange = { favorites = it; OpdsFavorites.save(app, serverId, it) },
        onDismiss = { editFavorites = false },
    )

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // the folder the user opened, by the name they tapped (feed titles like "Library Series" say little)
        TopBar(if (stack.size == 1) server.name else "📂 " + labels.last(), onBack = ::back) {
            if (stack.size > 1) androidx.compose.material3.IconButton(
                onClick = {
                    val added = OpdsFavorites.toggle(app, serverId, stack.toList(), labels.toList())
                    favorites = OpdsFavorites.all(app, serverId)
                    android.widget.Toast.makeText(context, if (added) "⭐ 즐겨찾기에 넣었습니다" else "즐겨찾기에서 뺐습니다", android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.semantics { contentDescription = if (isFav) "즐겨찾기에서 빼기" else "이 폴더 즐겨찾기" },
            ) { Text("⭐", style = MaterialTheme.typography.titleMedium, modifier = Modifier.alpha(if (isFav) 1f else 0.3f)) }
            EmojiButton("⚙️", "연결 설정", onOpenSettings)
        }
        if (onOrganized != null) com.oasisreader.app.ui.CatalogViewSwitch(organized = false, onOrganized = onOrganized, onFolders = {})
        // where am I: server › folder › folder (tap a part to go back there)
        if (stack.size > 1) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, l ->
                if (i > 0) Text("  ›  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val last = i == labels.size - 1
                Text(
                    (if (i == 0) "🏠 " else "") + l,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = if (last) Modifier else Modifier.clickable { popTo(i) },
                )
            }
        }

        // the server's first page: ⭐ favourites and the last folder, one tap away
        if (stack.size == 1 && (favorites.isNotEmpty() || lastPlace != null)) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(MiniChipGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lastPlace?.takeIf { lp -> favorites.none { it.urls.lastOrNull() == lp.urls.lastOrNull() } }?.let { lp ->
                MiniChip("🕘 ${lp.name}", onClick = { goTo(lp) }, description = "지난번 본 폴더")
            }
            favorites.forEach { f -> MiniChip("⭐ ${f.name}", onClick = { goTo(f) }) }
            if (favorites.isNotEmpty()) MiniChip("✏️", onClick = { editFavorites = true }, description = "즐겨찾기 편집", plain = true)
        }

        rootSearch?.let { sf ->
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            val focus = androidx.compose.ui.platform.LocalFocusManager.current
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("제목·저자 검색") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide(); focus.clearFocus()
                    val q = query.trim()
                    if (q.isNotEmpty()) scope.launch {
                        val u = runCatching { client.searchUrl(sf, q) }.getOrNull()
                        if (u != null) open(u, "검색: $q")
                        else android.widget.Toast.makeText(context, "검색 주소를 찾지 못했습니다", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        when (val s = state) {
            is FeedState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is FeedState.Failed -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { attempt++ }) { Text("다시 시도") }
                TextButton(onClick = onOpenSettings) { Text("연결 설정") }
            }
            is FeedState.Ready -> {
                val all = s.feed.entries + extra
                // some servers stamp every entry with the request time: then dates say nothing
                val datesUseful = remember(all.size, s) { datesMeaningful(all) }
                val effSort = if (!datesUseful && sort == CatalogSort.DATE) CatalogSort.SERVER else sort
                val entries = remember(all.size, s, effSort, sortDesc) { sortEntries(all, effSort, sortDesc) }
                if (all.size > 1) {
                    SortBar(
                        sort = effSort, desc = sortDesc, loading = loadingAll, datesUseful = datesUseful,
                        onSort = { o, d -> sort = o; sortDesc = d; sortPrefs.edit().putString("sort", o.name).putBoolean("sort_desc", d).apply() },
                        grid = grid, onGrid = { grid = it; sortPrefs.edit().putBoolean("grid", it).apply() },
                    )
                }
                // how many are here: books and folders (the server's count when it gives one)
                val nBooks = all.count { it.isBook }
                val nFolders = all.size - nBooks
                val more = nextUrl != null
                CountLine(
                    s.feed.totalResults?.let { "📚 ${"%,d".format(it)}개" }
                        ?: listOfNotNull(
                            nFolders.takeIf { it > 0 }?.let { "📂 폴더 " + loadedCount(it, more && nBooks == 0) },
                            nBooks.takeIf { it > 0 }?.let { "📕 " + loadedCount(it, more, "권") },
                        ).joinToString(" · ").ifEmpty { null },
                )
                val folderMissing = all.count { it.isBook && it.readable != null && it.id !in ownedSet }
                if (nBooks > 1 && (folderMissing > 0 || more)) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    MiniChip(
                        if (gatheringAll) "⏳ 목록을 모으는 중…" else "⬇️ 이 폴더 " + loadedCount(folderMissing, more, "권") + " 모두 담기",
                        onClick = { downloadFolder(s) }, enabled = !gatheringAll,
                    )
                }
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("항목이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (grid && entries.any { it.isBook }) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(112.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    ) {
                        items(entries.size) { i ->
                            val e = entries[i]
                            EntryTile(
                                entry = e,
                                client = client,
                                owned = e.id in ownedSet,
                                progress = progress[e.id],
                                onClick = { if (e.navHref != null && !e.isBook) open(e.navHref, e.title) else detail = e },
                                onDownload = { Downloads.start(app, server, e, origin = here()) },
                            )
                        }
                        nextUrl?.let { more ->
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (loadingMore) CircularProgressIndicator(Modifier.size(24.dp))
                                    else TextButton(onClick = {
                                        loadingMore = true
                                        scope.launch {
                                            try {
                                                val f = client.fetch(more)
                                                extra.addAll(f.entries); nextUrl = f.next
                                            } catch (e: Exception) {
                                                com.oasisreader.app.ui.Notice.show(context, e.message ?: "불러오기 실패")
                                            } finally { loadingMore = false }
                                        }
                                    }) { Text("더 보기") }
                                }
                            }
                        }
                    }
                } else LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                    items(entries) { e ->
                        EntryRow(
                            entry = e,
                            client = client,
                            owned = e.id in ownedSet,
                            showDate = effSort == CatalogSort.DATE,
                            progress = progress[e.id],
                            onClick = {
                                if (e.navHref != null && !e.isBook) open(e.navHref, e.title) else detail = e
                            },
                            onDownload = { Downloads.start(app, server, e, origin = here()) },
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    nextUrl?.let { more ->
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (loadingMore) CircularProgressIndicator(Modifier.size(24.dp))
                                else TextButton(onClick = {
                                    loadingMore = true
                                    scope.launch {
                                        try {
                                            val f = client.fetch(more)
                                            extra.addAll(f.entries); nextUrl = f.next
                                        } catch (e: Exception) {
                                            com.oasisreader.app.ui.Notice.show(context, e.message ?: "불러오기 실패")
                                        } finally { loadingMore = false }
                                    }
                                }) { Text("더 보기") }
                            }
                        }
                    }
                }
            }
        }
    }

    detail?.let { e ->
        val inLibrary = e.id in ownedSet
        val p = progress[e.id]
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(e.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (e.thumb != null) {
                        RemoteCover(e.thumb, e.title, load = { client.bytes(it) },
                            modifier = Modifier.width(150.dp).height(220.dp).align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(10.dp))
                    }
                    e.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            e.readable == null -> "⚠️ 읽을 수 있는 파일이 없습니다 (" + e.acquisitions.joinToString { it.type.substringAfter('/') } + ")"
                            e.isComic -> "🖼 웹툰·만화 (CBZ/ZIP)"
                            e.readable?.let { a -> a.type.startsWith("application/pdf") || a.href.substringBefore('?').lowercase().endsWith(".pdf") } == true -> "📄 PDF"
                            e.readable?.let { a -> a.type.startsWith("text/plain") || a.href.substringBefore('?').lowercase().endsWith(".txt") } == true -> "📝 TXT"
                            else -> "📖 EPUB"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    e.summary?.takeUnless { e.isComic }?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                when {
                    inLibrary -> TextButton(onClick = {
                        detail = null
                        scope.launch { db.books().findBySource(e.id, null)?.let { onOpenBook(it.id) } }
                    }) { Text("📖 읽기") }
                    p != null -> TextButton(onClick = {}, enabled = false) { Text("받는 중…") }
                    e.readable != null -> Row {
                        TextButton(onClick = {
                            detail = null
                            Downloads.start(app, server, e, pinned = false, onDone = { onOpenBook(it.id) }, origin = here())
                        }) { Text("📖 바로 읽기") }
                        TextButton(onClick = { Downloads.start(app, server, e, origin = here()) }) { Text("⬇️ 서재에 담기") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("닫기") } },
        )
    }
}

@Composable
private fun EntryRow(
    entry: OpdsEntry,
    client: OpdsClient,
    owned: Boolean,
    showDate: Boolean = false,
    progress: Float?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isBook) {
            RemoteCover(entry.thumb, entry.title, load = { client.bytes(it) }, modifier = Modifier.width(44.dp).height(64.dp))
        } else {
            Box(Modifier.width(44.dp).height(44.dp), contentAlignment = Alignment.Center) {
                Text("📁", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(entry.author, if (showDate) entry.updated?.let { shortDate(it) } else null).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (entry.isBook) {
            Spacer(Modifier.width(8.dp))
            when {
                owned -> Text("✅", style = MaterialTheme.typography.titleMedium)
                entry.readable != null -> DownloadControl(entry.id, progress, onDownload)
                else -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

enum class CatalogSort(val label: String, val kind: SortKind, val defaultDesc: Boolean) {
    SERVER("서버 순서", SortKind.ORDER, false),
    DATE("날짜", SortKind.DATE, true),
    TITLE("제목", SortKind.TEXT, false),
    AUTHOR("저자", SortKind.TEXT, false),
}

private const val MAX_SORT_PAGES = 40

private val collator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)

/** False when entries carry no dates, all the same date, or only "right now" (request time). */
private fun datesMeaningful(all: List<OpdsEntry>): Boolean {
    val ds = all.mapNotNull { epoch(it.updated) }
    if (ds.size < 2) return false
    if (ds.toSet().size == 1) return false
    val now = System.currentTimeMillis()
    return ds.any { now - it > 10 * 60_000L }
}

/** Sortable instant for an OPDS date (ISO-8601, date-only, or anything else = null). */
private fun epoch(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(s.take(19)).toEpochSecond(java.time.ZoneOffset.UTC) * 1000 }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(s.take(10)).toEpochDay() * 86_400_000L }.getOrNull()
}

private fun shortDate(s: String): String? = epoch(s)?.let {
    val d = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    "${d.year}.${d.monthValue}.${d.dayOfMonth}"
}

/**
 * Folders and books are sorted the same way (bookoasis keeps each book in its own folder),
 * folders first. Missing dates go last; folders have no author, so they fall back to the title.
 */
private fun sortEntries(all: List<OpdsEntry>, sort: CatalogSort, desc: Boolean): List<OpdsEntry> {
    val (books, folders) = all.partition { it.isBook }
    if (sort == CatalogSort.SERVER) return if (desc) folders.reversed() + books.reversed() else all
    val byTitle = Comparator<OpdsEntry> { a, b -> collator.compare(a.title, b.title) }
    val asc: Comparator<OpdsEntry> = when (sort) {
        CatalogSort.DATE -> compareBy<OpdsEntry> { epoch(it.updated) ?: 0L }.then(byTitle)
        CatalogSort.AUTHOR -> Comparator { a, b ->
            val c = collator.compare(a.author ?: "\uFFFF", b.author ?: "\uFFFF")
            if (c != 0) c else collator.compare(a.title, b.title)
        }
        else -> byTitle
    }
    val cmp = if (desc) asc.reversed() else asc
    // entries without a date go last either way
    fun sorted(l: List<OpdsEntry>) = if (sort != CatalogSort.DATE) l.sortedWith(cmp)
        else l.partition { epoch(it.updated) != null }.let { (dated, rest) -> dated.sortedWith(cmp) + rest.sortedWith(byTitle) }
    return sorted(folders) + sorted(books)
}

@Composable
private fun SortBar(
    sort: CatalogSort, desc: Boolean, loading: Int?, datesUseful: Boolean, onSort: (CatalogSort, Boolean) -> Unit,
    grid: Boolean, onGrid: (Boolean) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(MiniChipGap),
        ) {
            // same switch as in the library
            MiniChip(
                if (grid) "🔲 표지" else "📃 목록", onClick = { onGrid(!grid) }, plain = true,
                description = if (grid) "표지로 보는 중 (누르면 목록)" else "목록으로 보는 중 (누르면 표지)",
            )
            SortChip(
                options = CatalogSort.entries, selected = sort, desc = desc,
                label = { it.label }, kind = { it.kind }, defaultDesc = { it.defaultDesc },
                onChange = onSort,
                enabled = { datesUseful || it != CatalogSort.DATE },
            )
        }
        if (!datesUseful) {
            Text(
                "이 목록에는 항목별 날짜가 없어 날짜 정렬은 쓸 수 없습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (loading != null) {
            Text(
                "정렬하려고 목록 전체를 받는 중… ($loading)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun EntryTile(
    entry: OpdsEntry,
    client: OpdsClient,
    owned: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            if (entry.isBook) {
                RemoteCover(entry.thumb, entry.title, load = { client.bytes(it) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.68f))
            } else {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.68f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("📁", style = MaterialTheme.typography.displaySmall) }
            }
            if (entry.isBook) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(4.dp).heightIn(min = 36.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        owned -> Text("✅")
                        entry.readable != null -> DownloadControl(entry.id, progress, onDownload, size = 32.dp)
                        else -> Text("—")
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(entry.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}


/** ⭐ 즐겨찾기 편집: rename, move up, remove. */
@Composable
private fun FavoritesEditDialog(list: List<OpdsFavorites.Place>, onChange: (List<OpdsFavorites.Place>) -> Unit, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⭐ 즐겨찾기 편집") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                list.forEachIndexed { i, f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(f.labels.drop(1).joinToString(" › "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { if (i > 0) onChange(list.toMutableList().also { l -> val t = l[i]; l[i] = l[i - 1]; l[i - 1] = t }) }, enabled = i > 0) { Text("🔼") }
                        TextButton(onClick = { renaming = i }) { Text("✏️") }
                        TextButton(onClick = { onChange(list.filterIndexed { j, _ -> j != i }) }) { Text("🗑️") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("완료") } },
    )
    renaming?.let { i ->
        CollectionNameDialog("즐겨찾기 이름", list[i].name, onDismiss = { renaming = null }) { name ->
            renaming = null
            onChange(list.toMutableList().also { it[i] = it[i].copy(name = name) })
        }
    }
}
