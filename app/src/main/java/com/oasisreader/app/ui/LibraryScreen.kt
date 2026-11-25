package com.oasisreader.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.audio.AudioLibraryLink
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.CollectionEntity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The library. Tabs by 종류 (only the kinds it has), search (제목·저자·컬렉션 이름, 초성), cover grid
 * or list (by width until chosen), optionally 컬렉션별 — folder tiles in the grid, folding heads in the
 * list — a fast scroller, and 선택 모드 (long press) for doing several books at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<BookEntity>,
    loaded: Boolean,
    importing: Boolean,
    store: BookStore,
    onOpen: (BookEntity) -> Unit,
    onCatalog: () -> Unit,
    onImportFile: () -> Unit,
    onImportFolder: () -> Unit = {},
    onImportAudio: () -> Unit = {},
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onHighlights: () -> Unit,
    onReviews: () -> Unit = {},
    onReview: (BookEntity) -> Unit = {},
    onDownloads: () -> Unit = {},
    onStorage: () -> Unit = {},
    onDelete: (BookEntity, Boolean) -> Unit,
    onRename: (BookEntity, String) -> Unit,
    onPin: (BookEntity, Boolean) -> Unit = { _, _ -> },
    onSourceFolder: ((BookEntity) -> Unit)? = null,
    onSelecting: (Boolean) -> Unit = {},
) {
    var filterName by rememberSaveable { mutableStateOf(ShelfFilter.ALL.name) }
    val filter = ShelfFilter.valueOf(filterName)
    // progress sync: books another device has read further (checked at most hourly)
    val aheadMap by com.oasisreader.app.data.KoSync.ahead.collectAsState()
    val syncApp = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(loaded, books.size) {
        if (loaded && books.isNotEmpty()) com.oasisreader.app.data.KoSync.refreshLibrary(syncApp, books)
    }
    var menuFor by remember { mutableStateOf<BookEntity?>(null) }
    val askNotif = com.oasisreader.app.audio.rememberNotificationAsk()
    var infoFor by remember { mutableStateOf<BookEntity?>(null) }
    var exportFor by remember { mutableStateOf<BookEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<BookEntity?>(null) }
    var renaming by remember { mutableStateOf<BookEntity?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = ctx.applicationContext
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val shelfPrefs = remember { ctx.getSharedPreferences("library", android.content.Context.MODE_PRIVATE) }

    // 컬렉션: the user's own shelves (none chosen = the whole 서재)
    val colDb = remember { com.oasisreader.app.data.db.AppDatabase.get(ctx) }
    val collections by remember { colDb.collections().observeAll() }.collectAsState(initial = emptyList())
    val links by remember { colDb.collections().observeLinks() }.collectAsState(initial = emptyList())
    var colId by remember { mutableStateOf(shelfPrefs.getString("collection", null)) }
    fun setCol(id: String?) { colId = id; shelfPrefs.edit().putString("collection", id).apply() }
    val bookIds = remember(books) { books.map { it.id }.toSet() }
    val colCounts = remember(links, bookIds) { links.filter { it.bookId in bookIds }.groupingBy { it.collectionId }.eachCount() }
    val activeCol = colId?.takeIf { id -> collections.any { it.id == id } }
    val byCol: Map<String, Set<String>> = remember(links) { links.groupBy { it.collectionId }.mapValues { e -> e.value.map { it.bookId }.toSet() } }
    val inAnyCol = remember(links) { links.map { it.bookId }.toSet() }
    val colNames = remember(collections) { collections.associate { it.id to it.name } }
    val namesOf: Map<String, List<String>> = remember(links, colNames) { links.groupBy({ it.bookId }, { colNames[it.collectionId].orEmpty() }) }
    val base = remember(books, byCol, activeCol) {
        if (activeCol == null) books else byCol[activeCol].orEmpty().let { on -> books.filter { it.id in on } }
    }
    var newCol by remember { mutableStateOf(false) }
    var editCols by remember { mutableStateOf(false) }
    var pickColFor by remember { mutableStateOf<BookEntity?>(null) }
    var renameCol by remember { mutableStateOf<CollectionEntity?>(null) }
    var deleteCol by remember { mutableStateOf<CollectionEntity?>(null) }

    // 종류 탭: only the kinds this 서재 has; the view, size and order are remembered per tab
    var kindOrder by remember { mutableStateOf(ShelfKind.order(shelfPrefs.getString("kind_order", null))) }
    var editKindOrder by remember { mutableStateOf(false) }
    val kindCounts = remember(base) { base.groupingBy { ShelfKind.of(it) }.eachCount() }
    val presentKinds = kindOrder.filter { (kindCounts[it] ?: 0) > 0 }
    val showTabs = presentKinds.size > 1
    var tabName by remember { mutableStateOf(shelfPrefs.getString("tab", null) ?: shelfPrefs.getString("kind", null)) }
    val kind = if (showTabs) presentKinds.firstOrNull { it.name == tabName } else null
    fun setTab(k: ShelfKind?) { tabName = k?.name; shelfPrefs.edit().putString("tab", k?.name).apply() }
    val tabKey = kind?.name ?: "ALL"
    fun pk(k: String) = if (kind == null) k else "${kind.name}/$k"
    fun prefStr(k: String) = shelfPrefs.getString(pk(k), null) ?: shelfPrefs.getString(k, null)

    // tile size: smallest tile width in dp (3 across on a phone at the default)
    var tileDp by remember(tabKey) {
        mutableStateOf(shelfPrefs.getInt(pk("tile_dp"), shelfPrefs.getInt("tile_dp", TILE_DEFAULT)).let { v -> TILE_STEPS.minBy { kotlin.math.abs(it - v) } })
    }
    fun setTile(v: Int) { tileDp = v; shelfPrefs.edit().putInt(pk("tile_dp"), v).apply() }
    var gridChoice by remember(tabKey) { mutableStateOf(when (prefStr("layout")) { "grid" -> true; "list" -> false; else -> null }) }
    var sort by remember(tabKey) { mutableStateOf(runCatching { ShelfSort.valueOf(prefStr("sort") ?: "") }.getOrDefault(ShelfSort.RECENT)) }
    var sortDesc by remember(tabKey) {
        mutableStateOf(
            when {
                shelfPrefs.contains(pk("sort_desc")) -> shelfPrefs.getBoolean(pk("sort_desc"), true)
                shelfPrefs.contains("sort_desc") -> shelfPrefs.getBoolean("sort_desc", true)
                else -> sort.defaultDesc
            },
        )
    }
    // 묶어 보기: none / by 컬렉션 / by series (automatic, no 컬렉션 needed)
    var groupMode by remember(tabKey) {
        mutableStateOf(shelfPrefs.getString(pk("group"), null) ?: if (shelfPrefs.getBoolean(pk("grouped"), false)) "collection" else "none")
    }
    fun setGroup(m: String) { groupMode = m; shelfPrefs.edit().putString(pk("group"), m).apply() }
    val grouped = groupMode != "none"
    var stackFolders by remember { mutableStateOf(shelfPrefs.getString("folder_style", "grid") == "stack") }
    var closedCols by remember { mutableStateOf(shelfPrefs.getStringSet("closed_cols", emptySet()).orEmpty().toSet()) }
    fun toggleClosed(id: String) {
        closedCols = if (id in closedCols) closedCols - id else closedCols + id
        shelfPrefs.edit().putStringSet("closed_cols", closedCols).apply()
    }
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val tiles = gridChoice ?: wide

    // 🔍 검색
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = if (searchOpen) query.trim() else ""
    fun hitText(b: BookEntity) = ShelfSearch.matches(b.title, q) || ShelfSearch.matches(b.author, q)

    // 폴더 안 (컬렉션별 그리드에서 폴더를 누름)
    // kept outside the screen (ShelfMemory): reading a book and coming back lands in the same folder
    var openFolder by remember { mutableStateOf(ShelfMemory.openFolder) }
    LaunchedEffect(openFolder) { ShelfMemory.openFolder = openFolder }
    // series groups (묶기: 시리즈): books from the same server series / folder, or with the same title
    // once the volume number is taken off; same kind only; two or more. Shown like 컬렉션 folders
    // (ids "s:…", not saved anywhere).
    // 컬렉션 stay as they are: only books on no 컬렉션 are grouped by series
    // 🔓 이 묶음 풀기: series keys the user took apart (kept, so they stay apart)
    var ungrouped by remember { mutableStateOf(shelfPrefs.getStringSet("ungrouped_series", emptySet()).orEmpty().toSet()) }
    fun setUngrouped(v: Set<String>) { ungrouped = v; shelfPrefs.edit().putStringSet("ungrouped_series", v).apply() }
    val seriesGroups: List<Pair<CollectionEntity, List<BookEntity>>> = remember(base, groupMode, inAnyCol, ungrouped) {
        if (groupMode != "series") emptyList()
        else base.filter { it.id !in inAnyCol }.groupBy { seriesKeyOf(it) }.mapNotNull { (k, bs) ->
            if (k == null || bs.size < 2 || k in ungrouped) null
            else CollectionEntity("s:$k", seriesNameOf(bs), 0L, 0) to bs
        }
    }
    val folder = collections.firstOrNull { it.id == openFolder } ?: seriesGroups.firstOrNull { it.first.id == openFolder }?.first
    val inSeries = remember(seriesGroups) { seriesGroups.flatMap { it.second.map { b -> b.id } }.toSet() }

    // 선택 모드
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    LaunchedEffect(selecting) { onSelecting(selecting) }
    BackHandler(enabled = !selecting && folder != null) { openFolder = null }
    fun toggle(ids: Collection<String>) {
        selected = if (ids.all { it in selected }) selected - ids.toSet() else selected + ids
    }

    // 서재에서 보기 (ShelfJump.to): show that book — nothing filtering it out, its 종류 tab, inside
    // its 컬렉션 or 묶음 — then scroll to it. Each change recomposes and comes back here.
    val jumpId = ShelfJump.pendingBook
    LaunchedEffect(jumpId, bookIds, activeCol, tabKey, showTabs, groupMode, seriesGroups, links) {
        val id = jumpId ?: return@LaunchedEffect
        val b = books.firstOrNull { it.id == id } ?: return@LaunchedEffect      // not in the list yet
        if (selected.isNotEmpty()) selected = emptySet()
        if (searchOpen) searchOpen = false
        if (filterName != ShelfFilter.ALL.name) filterName = ShelfFilter.ALL.name
        val cols = links.filter { it.bookId == id }.map { it.collectionId }.filter { c -> collections.any { it.id == c } }
        if (activeCol != null && activeCol !in cols) { setCol(null); return@LaunchedEffect }
        val want = ShelfKind.of(b)
        if (showTabs && kind != want) { setTab(want); return@LaunchedEffect }
        openFolder = when {
            activeCol != null -> null                                   // already that 컬렉션's page
            cols.isNotEmpty() -> cols.first()
            else -> seriesGroups.firstOrNull { g -> g.second.any { it.id == id } }?.first?.id
        }
        ShelfJump.scrollTo = id
        ShelfJump.pendingBook = null
    }
    val scrollId = ShelfJump.scrollTo

    val kindBase = remember(base, kind) { if (kind == null) base else base.filter { ShelfKind.of(it) == kind } }
    val pool = remember(kindBase, filter, sort, sortDesc, kindOrder) { sortShelf(kindBase.filter { filter.matches(it) }, sort, sortDesc, kindOrder) }
    val shown = remember(pool, q, namesOf) {
        if (q.isEmpty()) pool else pool.filter { b -> hitText(b) || namesOf[b.id].orEmpty().any { ShelfSearch.matches(it, q) } }
    }
    val useGroups = grouped && activeCol == null && folder == null
    val entries: List<ShelfEntry> = remember(shown, pool, q, useGroups, tiles, folder, byCol, collections, closedCols, sort, seriesGroups, groupMode) {
        when {
            folder != null -> (byCol[folder.id] ?: seriesGroups.firstOrNull { it.first.id == folder.id }?.second?.map { it.id }?.toSet()).orEmpty()
                .let { ids -> shown.filter { it.id in ids }.let { l -> if (folder.id.startsWith("s:")) l.sortedWith { a, b -> ShelfSearch.naturalCompare(a.title, b.title) } else l }.map { ShelfEntry.Book(it) } }
            !useGroups -> shown.map { ShelfEntry.Book(it) }
            else -> {
                // with a search, a folder is shown when its name matches; the other hits come out as books
                // 🔗 시리즈별: the 컬렉션 folders as with 📚, plus series groups of the books on no 컬렉션
                val groups: List<Pair<CollectionEntity, Set<String>>> =
                    collections.mapNotNull { c -> byCol[c.id]?.let { c to it } } +
                        (if (groupMode == "series") seriesGroups.map { (c, bs) -> c to bs.map { it.id }.toSet() } else emptyList())
                val folders = groups.mapNotNull { (c, ids) ->
                    val inside = if (q.isEmpty()) shown.filter { it.id in ids }
                    else if (ShelfSearch.matches(c.name, q)) pool.filter { it.id in ids } else emptyList()
                    inside.takeIf { it.isNotEmpty() }?.let { c to it }
                }.let { list ->
                    if (sort == ShelfSort.TITLE || sort == ShelfSort.AUTHOR) list.sortedWith { a, b -> ShelfSearch.naturalCompare(a.first.name, b.first.name) }
                    else list.sortedByDescending { (_, bs) -> bs.maxOf { it.lastReadAt ?: it.addedAt } }
                }
                val grpIds = if (groupMode == "series") inAnyCol + inSeries else inAnyCol
                val loose = if (q.isEmpty()) shown.filter { it.id !in grpIds } else shown.filter { hitText(it) }
                if (tiles) folders.map { (c, bs) -> ShelfEntry.Folder(c, bs) } + loose.map { ShelfEntry.Book(it) }
                else folders.flatMap { (c, bs) ->
                    val open = c.id !in closedCols
                    listOf<ShelfEntry>(ShelfEntry.Head(c, bs, open)) +
                        (if (open) bs.map { ShelfEntry.Book(it, group = c.name, key = c.id + "_" + it.id) } else emptyList())
                } + loose.map { ShelfEntry.Book(it) }
            }
        }
    }
    val visibleIds = remember(entries) { entries.flatMap { it.bookIds() }.distinct() }
    val chosen = remember(selected, books) { books.filter { it.id in selected } }
    LaunchedEffect(bookIds) { if (selected.any { it !in bookIds }) selected = selected.filter { it in bookIds }.toSet() }

    /** 🔗 an automatic series group → a real 컬렉션 with the same books. */
    fun makeCollection(name: String, ids: List<String>) {
        ShelfActions.scope.launch {
            val c = ShelfCollections.create(colDb, name)
            ShelfActions.addToCollection(app, c.id, ids)
        }
        android.widget.Toast.makeText(ctx, "📚 컬렉션 「$name」을 만들었습니다", android.widget.Toast.LENGTH_SHORT).show()
    }

    var bulkDelete by remember { mutableStateOf(false) }
    var bulkRemove by remember { mutableStateOf(false) }
    var bulkCol by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // header line: how many books, and today's reading time (all books)
        val times by remember { colDb.readingTime().observeAll() }.collectAsState(initial = emptyList())
        val todayKey = java.time.LocalDate.now().toString()
        val todayMin = remember(times, todayKey) { (times.filter { it.day == todayKey }.sumOf { it.seconds } / 60).toInt() }
        val brandSub = if (!loaded) null else "서재 ${books.size}권 · 오늘 ${minutesText(todayMin)}"
        if (selecting) SelectionBar(
            count = selected.size,
            allSelected = visibleIds.isNotEmpty() && selected.containsAll(visibleIds),
            onClose = { selected = emptySet() },
            onAll = { selected = if (selected.containsAll(visibleIds)) selected - visibleIds.toSet() else selected + visibleIds },
        ) else TopBar("Reader", brand = true, brandSub = brandSub) {
            // two buttons only, so "OG Reader" fits on narrow bar-type phones too
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(androidx.compose.material.icons.Icons.Default.Menu, contentDescription = "메뉴")
                }
                OgMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    // 간결성 (81): icon tiles like the reading screen's ☰ — 가져오기 한 줄, 도구 한 줄
                    fun T(e: String, l: String, f: () -> Unit) = PanelTile(e, l) { menu = false; f() }
                    Column(Modifier.width(320.dp).padding(horizontal = 4.dp)) {
                        Text(
                            "가져오기", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                        )
                        PanelGrid(listOf(
                            T("☁️", "서버", onCatalog), T("📄", "파일", onImportFile),
                            T("📁", "폴더", onImportFolder), T("🎧", "오디오북", onImportAudio),
                        ), columns = 5)
                        HorizontalDivider(Modifier.padding(vertical = 2.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        PanelGrid(listOf(
                            T("🖍️", "하이라이트", onHighlights), T("📝", "독후감", onReviews), T("📊", "통계", onStats),
                            T("📦", "보관함", onStorage), T("⬇️", "받기", onDownloads),
                        ), columns = 5)
                    }
                }
            }
            EmojiButton("⚙️", "설정", onSettings)
        }
        UpdateBanner()
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())

        // 종류 탭
        if (showTabs) {
            // real tabs: the whole width shared, the chosen one bold with a wide underline, a line below
            val tabs = listOf<ShelfKind?>(null) + presentKinds
            androidx.compose.material3.TabRow(
                selectedTabIndex = tabs.indexOf(kind).coerceAtLeast(0),
                containerColor = barColor(),
                contentColor = MaterialTheme.colorScheme.primary,
                divider = { HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant) },
            ) {
                tabs.forEach { k ->
                    val on = k == kind
                    val n = if (k == null) base.size else kindCounts[k] ?: 0
                    androidx.compose.material3.Tab(
                        selected = on,
                        onClick = { setTab(k); openFolder = null },
                        modifier = Modifier.height(46.dp).semantics { contentDescription = (k?.label ?: "전체") + " ${n}권" },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            (k?.emoji ?: "전체") + " $n",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(MiniChipGap),
        ) {
            MiniChip("🔍", onClick = { searchOpen = !searchOpen; if (!searchOpen) { query = "" } }, selected = searchOpen, description = "서재에서 찾기")
            // tiles or list (automatic by width until chosen), 컬렉션별, size of tiles
            var viewOpen by remember { mutableStateOf(false) }
            Box {
                MiniChip(
                    (if (tiles) "🔲 타일" else "📃 목록") + when (groupMode) { "collection" -> " · 📚"; "series" -> " · 🔗"; else -> "" }, plain = true,
                    onClick = { viewOpen = true },
                    description = "보기 방식",
                )
                OgMenu(expanded = viewOpen, onDismissRequest = { viewOpen = false }) {
                    fun setLayout(grid: Boolean) { gridChoice = grid; shelfPrefs.edit().putString(pk("layout"), if (grid) "grid" else "list").apply() }
                    DropdownMenuItem(text = { EmojiText("🔲 타일") }, leadingIcon = { CheckMark(tiles) }, onClick = { viewOpen = false; setLayout(true) })
                    DropdownMenuItem(text = { EmojiText("📃 목록") }, leadingIcon = { CheckMark(!tiles) }, onClick = { viewOpen = false; setLayout(false) })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { EmojiText("📚 컬렉션별로 묶기") }, leadingIcon = { CheckMark(groupMode == "collection") },
                        onClick = { viewOpen = false; openFolder = null; setGroup(if (groupMode == "collection") "none" else "collection") },
                    )
                    DropdownMenuItem(
                        text = { EmojiText("🔗 컬렉션 + 시리즈별로 묶기 (자동)") }, leadingIcon = { CheckMark(groupMode == "series") },
                        onClick = { viewOpen = false; openFolder = null; setGroup(if (groupMode == "series") "none" else "series") },
                    )
                    if (groupMode == "series" && ungrouped.isNotEmpty()) DropdownMenuItem(
                        text = { EmojiText("🔗 풀어 둔 묶음 ${ungrouped.size}개 다시 묶기") },
                        onClick = { viewOpen = false; setUngrouped(emptySet()) },
                    )
                    if (grouped && tiles) {
                        HorizontalDivider()
                        Text("폴더 모양", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        DropdownMenuItem(text = { Text("▦ 작은 격자") }, leadingIcon = { CheckMark(!stackFolders) }, onClick = {
                            viewOpen = false; stackFolders = false; shelfPrefs.edit().putString("folder_style", "grid").apply()
                        })
                        DropdownMenuItem(text = { Text("🗂️ 겹친 표지") }, leadingIcon = { CheckMark(stackFolders) }, onClick = {
                            viewOpen = false; stackFolders = true; shelfPrefs.edit().putString("folder_style", "stack").apply()
                        })
                    }
                }
            }
            if (tiles) {
                // one chip like its neighbours; the size slider floats under it
                var sizeOpen by remember { mutableStateOf(false) }
                Box {
                    MiniChip("📐 크기", onClick = { sizeOpen = true }, description = "타일 크기", plain = true)
                    OgMenu(expanded = sizeOpen, onDismissRequest = { sizeOpen = false }) {
                        Column(Modifier.width(260.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text("타일 크기", style = MaterialTheme.typography.labelLarge)
                            androidx.compose.material3.Slider(
                                value = TILE_STEPS.indexOf(tileDp).toFloat(),
                                onValueChange = { setTile(TILE_STEPS[it.roundToInt().coerceIn(0, TILE_STEPS.size - 1)]) },
                                valueRange = 0f..(TILE_STEPS.size - 1).toFloat(),
                                steps = TILE_STEPS.size - 2,
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
            SortChip(
                options = ShelfSort.entries, selected = sort, desc = sortDesc,
                label = { it.label }, kind = { it.kind }, defaultDesc = { it.defaultDesc },
                short = { when (it) { ShelfSort.RECENT -> "읽은 날"; ShelfSort.ADDED -> "추가일"; else -> it.label } },
                extra = { close ->
                    DropdownMenuItem(
                        text = { Text("↕️ 종류 순서 정하기…", color = MaterialTheme.colorScheme.primary) },
                        onClick = { close(); editKindOrder = true },
                    )
                },
                onChange = { o, d ->
                    sort = o; sortDesc = d
                    shelfPrefs.edit().putString(pk("sort"), o.name).putBoolean(pk("sort_desc"), d).apply()
                },
            )
            CollectionChip(
                collections, colCounts, activeCol, books.size,
                onSelect = { setCol(it); openFolder = null }, onNew = { newCol = true }, onEdit = { editCols = true },
            )
            // 전체 / 읽는 중 / 안 읽음 / 다 읽음 — one drop-down
            var filterOpen by remember { mutableStateOf(false) }
            Box {
                MiniChip(
                    "🔖 ${filter.label} ${kindBase.count { filter.matches(it) }}",
                    onClick = { filterOpen = true },
                    selected = filter != ShelfFilter.ALL,
                )
                OgMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                    ShelfFilter.entries.forEach { f ->
                        DropdownMenuItem(
                            text = { Text("${f.label} ${kindBase.count { f.matches(it) }}") },
                            leadingIcon = { CheckMark(f == filter) },
                            onClick = { filterOpen = false; filterName = f.name },
                        )
                    }
                }
            }
        }

        if (searchOpen) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("제목·저자·컬렉션 이름 (초성도 됩니다)") },
                leadingIcon = { EmojiText("🔍") },
                trailingIcon = { IconButton(onClick = { query = ""; searchOpen = false }) { EmojiText("✖️") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(focus),
            )
            if (q.isNotEmpty()) CountLine("찾은 책 ${shown.size}권")
        }

        // inside a folder: where we are, and the way out
        if (folder != null) Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val auto = folder.id.startsWith("s:")
            EmojiButton("⬅️", "묶음에서 나가기") { openFolder = null }
            Text(
                (if (auto) "🔗 " else "📚 ") + "${folder.name} · ${entries.size}권", style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            CollectionMenuButton(
                onRename = { renameCol = folder }, onDelete = { deleteCol = folder },
                onSelectAll = { selected = selected + visibleIds },
                onMakeCollection = if (auto) ({ makeCollection(folder.name, visibleIds) }) else null,
                onUngroup = if (auto) ({ openFolder = null; setUngrouped(ungrouped + folder.id.removePrefix("s:")) }) else null,
            )
        }
        // ▶ 이어 읽기 (TODO 65): the volume read last, or the next when that one is finished
        if (folder != null && !selecting) {
            val resume = remember(entries) {
                val inFolder = entries.filterIsInstance<ShelfEntry.Book>().map { it.b }
                com.oasisreader.app.data.ContinueReading.pick(inFolder, { it.title }) { it }
            }
            if (resume != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                MiniChip("▶️ 이어 읽기", onClick = { onOpen(resume) }, description = "읽던 회차로 바로 가기")
            }
        }

        if (loaded && books.isEmpty()) {
            EmptyShelf(onCatalog, onImportFile)
            return@Column
        }
        if (loaded && activeCol != null && base.isEmpty()) {
            Text(
                "이 컬렉션은 비어 있습니다.\n서재 전체에서 책의 ⋮ 메뉴로 \"📚 컬렉션에 넣기\"를 고르세요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        if (loaded && entries.isEmpty()) Text(
            if (q.isNotEmpty()) "「$q」에 맞는 책이 없습니다." else "보여 줄 책이 없습니다.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )

        val dateFmt = remember { java.text.SimpleDateFormat("yyyy.M", java.util.Locale.KOREA) }
        fun bookLabel(b: BookEntity): String = when (sort) {
            ShelfSort.TITLE -> ShelfSearch.initial(b.title)
            ShelfSort.AUTHOR -> ShelfSearch.initial(b.author)
            ShelfSort.RECENT -> dateFmt.format(java.util.Date(b.lastReadAt ?: b.addedAt))
            ShelfSort.ADDED -> dateFmt.format(java.util.Date(b.addedAt))
            ShelfSort.PROGRESS -> progressText(b)
            ShelfSort.KIND -> ShelfKind.of(b).let { "${it.emoji} ${it.label}" }
        }
        fun labelAt(i: Int): String? = when (val e = entries.getOrNull(i)) {
            null -> null
            is ShelfEntry.Book -> e.group ?: bookLabel(e.b)
            is ShelfEntry.Folder -> "📚 " + e.c.name
            is ShelfEntry.Head -> "📚 " + e.c.name
        }
        fun clickBook(b: BookEntity) { if (selecting) toggle(listOf(b.id)) else onOpen(b) }
        fun selState(ids: List<String>): Boolean? = if (!selecting) null else ids.isNotEmpty() && ids.all { it in selected }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val bottomPad = if (selecting) 16.dp else 24.dp
            if (tiles) {
                val posKey = "g|$tabKey|$openFolder|$useGroups"
                val gridState = remember(tabKey, openFolder, useGroups) {
                    val p = ShelfMemory.pos[posKey]; LazyGridState(p?.first ?: 0, p?.second ?: 0)
                }
                DisposableEffect(gridState) {
                    onDispose { ShelfMemory.pos[posKey] = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                }
                LaunchedEffect(scrollId, entries, gridState) {
                    val id = scrollId ?: return@LaunchedEffect
                    val i = entries.indexOfFirst { it is ShelfEntry.Book && it.b.id == id }
                    if (i >= 0) { gridState.scrollToItem(i); ShelfJump.scrollTo = null }
                    else { kotlinx.coroutines.delay(1_500); ShelfJump.scrollTo = null }   // not on this page after all
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(tileDp.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (tileDp < 100) 10.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (tileDp < 100) 16.dp else 20.dp),
                    modifier = Modifier.fillMaxSize().let { if (selecting) it else it.navigationBarsPadding() },
                ) {
                    items(entries, key = { it.key }) { e ->
                        when (e) {
                            is ShelfEntry.Book -> GridBook(
                                e.b, store, onClick = { clickBook(e.b) }, onLongClick = { toggle(listOf(e.b.id)) },
                                onMenu = { menuFor = e.b }, compact = tileDp < 110, ahead = aheadMap[e.b.id], selected = selState(listOf(e.b.id)), showKind = kind == null,
                            )
                            is ShelfEntry.Folder -> FolderTile(
                                e.c, e.books, store, stack = stackFolders, compact = tileDp < 110, selected = selState(e.bookIds()),
                                onClick = { if (selecting) toggle(e.bookIds()) else { openFolder = e.c.id } },
                                onLongClick = { toggle(e.bookIds()) },
                                onRename = { renameCol = e.c }, onDelete = { deleteCol = e.c },
                                onMakeCollection = if (e.c.id.startsWith("s:")) ({ makeCollection(e.c.name, e.bookIds()) }) else null,
                                onUngroup = if (e.c.id.startsWith("s:")) ({ setUngrouped(ungrouped + e.c.id.removePrefix("s:")) }) else null,
                            )
                            is ShelfEntry.Head -> {}
                        }
                    }
                }
                FastScroller(remember(gridState) { gridState.scrollTarget() }, ::labelAt, bottomPadding = bottomPad)
            } else {
                val posKey = "l|$tabKey|$openFolder|$useGroups"
                val listState = remember(tabKey, openFolder, useGroups) {
                    val p = ShelfMemory.pos[posKey]; LazyListState(p?.first ?: 0, p?.second ?: 0)
                }
                DisposableEffect(listState) {
                    onDispose { ShelfMemory.pos[posKey] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                }
                LaunchedEffect(scrollId, entries, listState) {
                    val id = scrollId ?: return@LaunchedEffect
                    val i = entries.indexOfFirst { it is ShelfEntry.Book && it.b.id == id }
                    if (i >= 0) { listState.scrollToItem(i); ShelfJump.scrollTo = null }
                    else { kotlinx.coroutines.delay(1_500); ShelfJump.scrollTo = null }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().let { if (selecting) it else it.navigationBarsPadding() },
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(entries, key = { it.key }) { e ->
                        when (e) {
                            is ShelfEntry.Book -> {
                                ListBook(
                                    e.b, store, onClick = { clickBook(e.b) }, onLongClick = { toggle(listOf(e.b.id)) },
                                    onMenu = { menuFor = e.b }, ahead = aheadMap[e.b.id], selected = selState(listOf(e.b.id)),
                                    indent = e.group != null,
                                )
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            is ShelfEntry.Head -> CollectionHead(
                                e.c, e.books, e.open, selected = selState(e.bookIds()),
                                onClick = { if (selecting) toggle(e.bookIds()) else toggleClosed(e.c.id) },
                                onLongClick = { toggle(e.bookIds()) },
                                onRename = { renameCol = e.c }, onDelete = { deleteCol = e.c },
                                onMakeCollection = if (e.c.id.startsWith("s:")) ({ makeCollection(e.c.name, e.bookIds()) }) else null,
                                onUngroup = if (e.c.id.startsWith("s:")) ({ setUngrouped(ungrouped + e.c.id.removePrefix("s:")) }) else null,
                            )
                            is ShelfEntry.Folder -> {}
                        }
                    }
                }
                FastScroller(remember(listState) { listState.scrollTarget() }, ::labelAt, bottomPadding = bottomPad)
            }
        }

        // 선택 모드: what to do with the chosen books (the same as 📦 보관함 관리)
        if (selecting) Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(MiniChipGap),
            ) {
                fun done(msg: String? = null) { selected = emptySet(); msg?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() } }
                val nonAudio = chosen.filter { it.format != AudioLibraryLink.FORMAT }
                MiniChip("🗑️ 서재에서 지우기", onClick = { bulkDelete = true })
                if (chosen.any { it.sourceUrl != null || it.format == AudioLibraryLink.FORMAT }) MiniChip("☁️ 폰에서 지우기", onClick = { bulkRemove = true })
                MiniChip("📚 컬렉션에 넣기", onClick = { bulkCol = true })
                if (nonAudio.any { !it.pinned }) MiniChip("📌 보관", onClick = {
                    val list = chosen
                    scope.launch { ShelfActions.setPinned(app, list, true); done("📌 보관했습니다") }
                })
                if (nonAudio.any { it.pinned && it.sourceUrl != null }) MiniChip("🕒 임시로", onClick = {
                    val list = chosen
                    scope.launch { ShelfActions.setPinned(app, list, false); done("🕒 임시로 두었습니다") }
                })
                if (chosen.any { it.finishedAt == null }) MiniChip("✔️ 다 읽음", onClick = {
                    val list = chosen
                    scope.launch { ShelfActions.setFinished(app, list, true); done() }
                })
                if (chosen.any { it.finishedAt != null }) MiniChip("↩️ 안 읽음으로", onClick = {
                    val list = chosen
                    scope.launch { ShelfActions.setFinished(app, list, false); done() }
                })
            }
        }
    }

    infoFor?.let { b -> BookInfoDialog(b, store) { infoFor = null } }
    if (newCol) CollectionNameDialog("새 컬렉션", onDismiss = { newCol = false }) { name ->
        newCol = false
        scope.launch { setCol(ShelfCollections.create(colDb, name).id) }
    }
    if (editCols) CollectionEditDialog(collections, colCounts, links, books) { editCols = false }
    renameCol?.let { c ->
        CollectionNameDialog("컬렉션 이름 바꾸기", c.name, onDismiss = { renameCol = null }) { name ->
            renameCol = null
            scope.launch { colDb.collections().rename(c.id, name) }
        }
    }
    deleteCol?.let { c ->
        CollectionDeleteDialog(c, links, books) {
            deleteCol = null
            if (openFolder == c.id) openFolder = null
        }
    }
    if (editKindOrder) KindOrderDialog(kindOrder, onDismiss = { editKindOrder = false }) { order ->
        kindOrder = order
        shelfPrefs.edit().putString("kind_order", order.joinToString(",") { it.name }).apply()
        if (sort != ShelfSort.KIND) { sort = ShelfSort.KIND; sortDesc = false; shelfPrefs.edit().putString(pk("sort"), sort.name).putBoolean(pk("sort_desc"), false).apply() }
    }
    pickColFor?.let { b -> CollectionPickDialog(b, collections, links) { pickColFor = null } }
    exportFor?.let { b -> HighlightExportDialog(b.id) { exportFor = null } }

    if (bulkDelete) BulkDeleteDialog(chosen, onDismiss = { bulkDelete = false }) { keep ->
        bulkDelete = false
        val list = chosen
        selected = emptySet()
        ShelfActions.scope.launch { ShelfActions.delete(app, list, keep) }
    }
    if (bulkCol) BulkCollectionDialog(chosen.size, collections, onDismiss = { bulkCol = false }) { c ->
        bulkCol = false
        val ids = chosen.map { it.id }
        selected = emptySet()
        ShelfActions.scope.launch { ShelfActions.addToCollection(app, c.id, ids) }
        android.widget.Toast.makeText(ctx, "📚 ${c.name}에 넣었습니다", android.widget.Toast.LENGTH_SHORT).show()
    }
    if (bulkRemove) BulkRemoveFilesDialog(chosen, app, onDismiss = { bulkRemove = false }, onClear = { selected = emptySet() })

    menuFor?.let { b ->
        BookMenuDialog(
            b, links, ctx, askNotif, scope, onOpen, onReview, onSourceFolder, onPin,
            onDismiss = { menuFor = null },
            onInfo = { infoFor = b }, onPickCol = { pickColFor = b }, onRename = { renaming = b },
            onExport = { exportFor = b }, onSelect = { selected = selected + b.id }, onDelete = { confirmDelete = b },
        )
    }

    renaming?.let { b -> RenameBookDialog(b, onDismiss = { renaming = null }) { title -> onRename(b, title) } }

    confirmDelete?.let { b -> DeleteBookDialog(b, ctx, onDismiss = { confirmDelete = null }, onDelete = onDelete) }
}

/**
 * Which series a book belongs to, for 🔗 시리즈별로 묶기: the server series or folder it came from
 * (since 1.2), else its title without the volume number; per kind, so a novel and a comic of the same
 * name stay apart. Null: not part of a series.
 */
internal fun seriesKeyOf(b: BookEntity): String? {
    val kind = ShelfKind.of(b).name
    com.oasisreader.app.data.BookOrigin.decode(b.origin)?.let { o ->
        when (o.type) {
            com.oasisreader.app.data.BookOrigin.META -> o.series?.let { return "$kind|m|${o.server}|${o.library}|$it" }
            com.oasisreader.app.data.BookOrigin.OPDS -> o.urls.lastOrNull()?.let { return "$kind|o|${o.server}|$it" }
            com.oasisreader.app.data.BookOrigin.FOLDER -> o.folder?.takeIf { it.isNotBlank() }?.let { return "$kind|f|${o.tree}|$it" }
        }
    }
    return ShelfSearch.seriesStem(b.title)?.let { "$kind|t|" + ShelfSearch.normalize(it) }
}

/** The group's name: the common title without volume numbers, or the first title. */
private fun seriesNameOf(books: List<BookEntity>): String {
    val stems = books.mapNotNull { ShelfSearch.seriesStem(it.title) }
    return stems.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        ?: books.minWithOrNull { a, b -> ShelfSearch.naturalCompare(a.title, b.title) }?.title.orEmpty()
}

/** Where the shelf was (open folder, scroll per tab/folder) — survives opening a book and coming back. */
object ShelfMemory {
    var openFolder: String? = null
    val pos = HashMap<String, Pair<Int, Int>>()
}
