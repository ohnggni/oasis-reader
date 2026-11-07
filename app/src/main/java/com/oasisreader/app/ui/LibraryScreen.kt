package com.oasisreader.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.audio.AudioLibraryLink
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.CollectionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class ShelfFilter(val label: String) {
    ALL("전체"), READING("읽는 중"), UNREAD("안 읽음"), DONE("다 읽음");

    fun matches(b: BookEntity) = when (this) {
        ALL -> true
        READING -> b.finishedAt == null && b.lastReadAt != null
        UNREAD -> b.finishedAt == null && b.lastReadAt == null
        DONE -> b.finishedAt != null
    }
}

/** What a 서재 book is: shown first in its text line, one tab each, sorted by 종류. */
enum class ShelfKind(val emoji: String, val label: String) {
    BOOK("📖", "책"), COMIC("🖼️", "만화"), PDF("📄", "PDF"), AUDIO("🎧", "오디오북");

    companion object {
        fun of(b: BookEntity) = when (b.format) {
            BookStore.FORMAT_COMIC -> COMIC
            BookStore.FORMAT_PDF -> PDF
            AudioLibraryLink.FORMAT -> AUDIO
            else -> BOOK
        }

        /** The user's order for 종류 sorting and the tabs (prefs "kind_order", names joined by commas). */
        fun order(saved: String?): List<ShelfKind> {
            val named = saved?.split(',')?.mapNotNull { n -> entries.firstOrNull { it.name == n } }.orEmpty()
            return (named + entries).distinct()
        }
    }
}

enum class ShelfSort(val label: String, val kind: SortKind, val defaultDesc: Boolean) {
    RECENT("읽은 날짜", SortKind.DATE, true),
    ADDED("추가한 날짜", SortKind.DATE, true),
    TITLE("제목", SortKind.TEXT, false),
    AUTHOR("저자", SortKind.TEXT, false),
    PROGRESS("진행률", SortKind.AMOUNT, true),
    KIND("종류", SortKind.ORDER, false);
}

private val shelfCollator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)

/** Ascending order for each criterion; [desc] turns it around. Titles in natural order (2권 before 10권). */
private fun sortShelf(list: List<BookEntity>, sort: ShelfSort, desc: Boolean, kinds: List<ShelfKind> = ShelfKind.entries): List<BookEntity> {
    val byTitle = Comparator<BookEntity> { a, b -> ShelfSearch.naturalCompare(a.title, b.title) }
    val asc: Comparator<BookEntity> = when (sort) {
        ShelfSort.RECENT -> compareBy<BookEntity> { it.lastReadAt ?: it.addedAt }
        ShelfSort.ADDED -> compareBy { it.addedAt }
        ShelfSort.TITLE -> byTitle
        ShelfSort.AUTHOR -> Comparator { a, b ->
            val c = shelfCollator.compare(a.author ?: "￿", b.author ?: "￿")
            if (c != 0) c else ShelfSearch.naturalCompare(a.title, b.title)
        }
        ShelfSort.PROGRESS -> compareBy<BookEntity> { if (it.finishedAt != null) 2f else it.progress }.then(byTitle)
        // the user's kind order; inside a kind, the most recently read first
        ShelfSort.KIND -> compareBy<BookEntity> { kinds.indexOf(ShelfKind.of(it)) }
    }
    if (sort == ShelfSort.KIND) {
        val recent = compareByDescending<BookEntity> { it.lastReadAt ?: it.addedAt }
        return list.sortedWith((if (desc) asc.reversed() else asc).then(recent))
    }
    return list.sortedWith(if (desc) asc.reversed() else asc)
}

/** One thing in the 서재 grid/list: a book, a 컬렉션 folder tile, or a 컬렉션 head (list, 컬렉션별). */
private sealed interface ShelfEntry {
    val key: String

    data class Book(val b: BookEntity, val group: String? = null, override val key: String = b.id) : ShelfEntry
    data class Folder(val c: CollectionEntity, val books: List<BookEntity>) : ShelfEntry { override val key get() = "f_" + c.id }
    data class Head(val c: CollectionEntity, val books: List<BookEntity>, val open: Boolean) : ShelfEntry { override val key get() = "h_" + c.id }
}

private fun ShelfEntry.bookIds(): List<String> = when (this) {
    is ShelfEntry.Book -> listOf(b.id)
    is ShelfEntry.Folder -> books.map { it.id }
    is ShelfEntry.Head -> books.map { it.id }
}

/** Kept on the phone (서재에 보관, works offline)? Reads the disk. */
private fun isKept(b: BookEntity, store: BookStore): Boolean =
    b.pinned && if (b.format == AudioLibraryLink.FORMAT) b.fileBytes > 0
    else store.originalOf(b.id) != null || store.contentDir(b.id).isDirectory

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
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onHighlights: () -> Unit,
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
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(
                        Triple("☁️", "서버에서 책 찾기", onCatalog),
                        Triple("📁", "파일에서 가져오기", onImportFile),
                        Triple("🗂️", "폴더에서 가져오기", onImportFolder),
                        Triple("🖍️", "하이라이트", onHighlights),
                        Triple("📊", "독서 통계", onStats),
                        Triple("📦", "보관함 관리", onStorage),
                        Triple("⬇️", "받기 관리", onDownloads),
                    ).forEach { (emoji, label, action) ->
                        DropdownMenuItem(
                            leadingIcon = { Text(emoji, style = MaterialTheme.typography.titleMedium) },
                            text = { Text(label) },
                            onClick = { menu = false; action() },
                        )
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
                DropdownMenu(expanded = viewOpen, onDismissRequest = { viewOpen = false }) {
                    fun setLayout(grid: Boolean) { gridChoice = grid; shelfPrefs.edit().putString(pk("layout"), if (grid) "grid" else "list").apply() }
                    DropdownMenuItem(text = { Text("🔲 타일") }, leadingIcon = { CheckMark(tiles) }, onClick = { viewOpen = false; setLayout(true) })
                    DropdownMenuItem(text = { Text("📃 목록") }, leadingIcon = { CheckMark(!tiles) }, onClick = { viewOpen = false; setLayout(false) })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("📚 컬렉션별로 묶기") }, leadingIcon = { CheckMark(groupMode == "collection") },
                        onClick = { viewOpen = false; openFolder = null; setGroup(if (groupMode == "collection") "none" else "collection") },
                    )
                    DropdownMenuItem(
                        text = { Text("🔗 컬렉션 + 시리즈별로 묶기 (자동)") }, leadingIcon = { CheckMark(groupMode == "series") },
                        onClick = { viewOpen = false; openFolder = null; setGroup(if (groupMode == "series") "none" else "series") },
                    )
                    if (groupMode == "series" && ungrouped.isNotEmpty()) DropdownMenuItem(
                        text = { Text("🔗 풀어 둔 묶음 ${ungrouped.size}개 다시 묶기") },
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
                    DropdownMenu(expanded = sizeOpen, onDismissRequest = { sizeOpen = false }) {
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
                DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
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
                leadingIcon = { Text("🔍") },
                trailingIcon = { IconButton(onClick = { query = ""; searchOpen = false }) { Text("✖️") } },
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
                                onMenu = { menuFor = e.b }, compact = tileDp < 110, ahead = aheadMap[e.b.id], selected = selState(listOf(e.b.id)),
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
    if (bulkRemove) {
        val list = chosen
        val can by produceState<List<BookEntity>?>(null, list) {
            value = withContext(Dispatchers.IO) { list.filter { ShelfActions.canRemoveFiles(app, it) } }
        }
        AlertDialog(
            onDismissRequest = { bulkRemove = false },
            title = { Text("☁️ 폰에서 지우기") },
            text = {
                val n = can?.size
                Text(
                    if (n == null) "세는 중…"
                    else "${n}권의 파일을 폰에서 지웁니다. 서재·읽은 위치·하이라이트는 남고, 열면 서버에서 다시 받습니다." +
                        if (list.size > n) "\n직접 넣은 책이나 이미 폰에 없는 책 ${list.size - n}권은 그대로 둡니다." else "",
                )
            },
            confirmButton = {
                TextButton(enabled = !can.isNullOrEmpty(), onClick = {
                    bulkRemove = false
                    val go = can.orEmpty()
                    selected = emptySet()
                    ShelfActions.scope.launch { ShelfActions.removeFiles(app, go) }
                }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { bulkRemove = false }) { Text("취소") } },
        )
    }

    menuFor?.let { b ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(b.title) },
            text = {
                Column {
                    Text(
                        (if (b.format == AudioLibraryLink.FORMAT)
                            listOfNotNull(b.author, if (b.pinned && b.fileBytes > 0) "🎧 폰에 받음 · %.1f MB".format(b.fileBytes / 1048576.0) else "🎧 스트리밍 (폰에 없음)")
                        else listOfNotNull(b.author, "%.1f MB".format(b.fileBytes / 1048576.0), b.sourceUrl?.let { "서버에서 받음" }))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { menuFor = null; infoFor = b }) { Text("ℹ️ 책 정보") }
                    TextButton(onClick = { menuFor = null; pickColFor = b }) {
                        val n = links.count { it.bookId == b.id }
                        Text("📚 컬렉션에 넣기" + if (n > 0) " (${n}곳에 있음)" else "")
                    }
                    onSourceFolder?.takeIf { com.oasisreader.app.reader.canOpenOrigin(b) }?.let { open -> TextButton(onClick = { menuFor = null; open(b) }) { Text("📂 있던 폴더 열기") } }
                    TextButton(onClick = { menuFor = null; renaming = b }) { Text("✏️ 제목 바꾸기") }
                    val isAudio = b.format == AudioLibraryLink.FORMAT
                    if (isAudio) {
                        // 오프라인: copy the tracks to the phone, or go back to streaming
                        val ad = com.oasisreader.app.audio.AudioDownloads
                        val dl = ad.progress[b.id]
                        val err = ad.errors[b.id]
                        fun startDl() {
                            menuFor = null
                            askNotif()
                            ad.enqueue(ctx, b)
                            com.oasisreader.app.ui.Notice.show(ctx, "「${b.title}」 받기를 시작했습니다. 앱을 닫아도 계속 받고, 알림에서 진행을 볼 수 있습니다.")
                        }
                        err?.let { Text("⚠️ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
                        val paused = remember(b.id, ad.version) { ad.isPaused(ctx, b.id) }
                        when {
                            dl != null || paused -> Row {
                                if (paused) TextButton(onClick = { ad.resume(ctx, b.id) }) { Text("▶️ 이어 받기") }
                                else TextButton(onClick = { ad.pause(ctx, b.id) }) { Text("⏸️ 일시정지 ${((dl ?: 0f) * 100).roundToInt()}%") }
                                TextButton(onClick = { ad.cancel(ctx, b.id) }) { Text("✖️ 받기 취소") }
                            }
                            b.pinned && b.fileBytes > 0 -> {}
                            b.fileBytes > 0 -> TextButton(onClick = { startDl() }) { Text("⬇️ 나머지 트랙도 받기") }
                            else -> TextButton(onClick = { startDl() }) { Text("⬇️ 폰에 받기 (오프라인으로 듣기)") }
                        }
                        if (b.fileBytes > 0) TextButton(onClick = {
                            menuFor = null
                            scope.launch { ad.remove(ctx, b) }
                        }) { Text("☁️ 폰에서 지우고 스트리밍으로 듣기") }
                    } else TextButton(onClick = { menuFor = null; exportFor = b }) { Text("📝 하이라이트 내보내기") }
                    if (b.sourceUrl != null && !isAudio) TextButton(onClick = { menuFor = null; onPin(b, !b.pinned) }) {
                        Text(if (b.pinned) "☁️ 임시로 두기 (공간 부족하면 파일만 지워짐)" else "📌 서재에 보관 (지금은 임시)")
                    }
                    TextButton(onClick = { menuFor = null; selected = selected + b.id }) { Text("☑️ 여러 권 고르기") }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuFor = null; confirmDelete = b }) {
                    Text("🗑️ 서재에서 지우기", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { menuFor = null }) { Text("닫기") } },
        )
    }

    renaming?.let { b ->
        var text by remember(b.id) { mutableStateOf(b.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("제목 바꾸기") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { renaming = null; onRename(b, text.trim()) }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("취소") } },
        )
    }

    confirmDelete?.let { b ->
        // highlights can outlive the book (하이라이트 화면 › 🗑️ 지운 책), kept unless unticked
        val hlCount by produceState(0, b.id) {
            value = com.oasisreader.app.data.db.AppDatabase.get(ctx).highlights().countFor(b.id)
        }
        var keepHl by remember(b.id) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("서재에서 지우시겠습니까?") },
            text = {
                Column {
                    Text(
                        "「${b.title}」 파일이 이 폰에서 지워집니다. 독서 시간 통계는 남습니다." +
                            if (hlCount > 0 && !keepHl) " 하이라이트 ${hlCount}개도 지워집니다." else "",
                    )
                    if (hlCount > 0) Row(
                        Modifier.fillMaxWidth().clickable { keepHl = !keepHl }.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = keepHl, onCheckedChange = { keepHl = it })
                        Text("🖍️ 하이라이트·메모 ${hlCount}개는 남기기", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete(b, hlCount > 0 && keepHl) }) {
                    Text("지우기", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } },
        )
    }
}

/** 선택 모드 top bar: N권 선택됨 · ✖️ · ☑️ 전체 (what is on screen now). */
@Composable
private fun SelectionBar(count: Int, allSelected: Boolean, onClose: () -> Unit, onAll: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(
            Modifier.fillMaxWidth().shelfTopInset().height(60.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EmojiButton("✖️", "선택 끝내기", onClose)
            Text("${count}권 선택됨", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAll) { Text(if (allSelected) "☑️ 전체 해제" else "☑️ 전체") }
        }
    }
}

/** ⋮ on a 컬렉션 folder or head: 이름 바꾸기 · 컬렉션 지우기 (and 모두 고르기). */
@Composable
private fun CollectionMenuButton(onRename: () -> Unit, onDelete: () -> Unit, onSelectAll: () -> Unit, size: Dp = 36.dp, onMakeCollection: (() -> Unit)? = null, onUngroup: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(size)) {
            Icon(androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = "컬렉션 메뉴", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("☑️ 모두 고르기") }, onClick = { open = false; onSelectAll() })
            if (onMakeCollection != null) {
                // an automatic series group: nothing to rename or delete; it can become a 컬렉션
                DropdownMenuItem(text = { Text("📚 컬렉션으로 만들기") }, onClick = { open = false; onMakeCollection() })
                if (onUngroup != null) DropdownMenuItem(text = { Text("🔓 이 묶음 풀기") }, onClick = { open = false; onUngroup() })
            } else {
                DropdownMenuItem(text = { Text("✏️ 이름 바꾸기") }, onClick = { open = false; onRename() })
                DropdownMenuItem(text = { Text("🗑️ 컬렉션 지우기…", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onDelete() })
            }
        }
    }
}

/** A tick in the corner of a cover in 선택 모드. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SelectBadge(on: Boolean) {
    if (on) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)))
    Box(
        Modifier.align(Alignment.TopStart).padding(5.dp).size(24.dp)
            .background(if (on) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f), CircleShape)
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

/**
 * A 컬렉션 as a folder tile: 작은 격자 (2×2 covers on a folder card, like a phone's app folder)
 * or 겹친 표지 (the first volume large, two more peeking behind). Below: name, 권수, 읽은 권수.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    c: CollectionEntity, books: List<BookEntity>, store: BookStore, stack: Boolean, compact: Boolean, selected: Boolean?,
    onClick: () -> Unit, onLongClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
    onMakeCollection: (() -> Unit)? = null,
    onUngroup: (() -> Unit)? = null,
) {
    val covers = remember(books) { books.sortedWith { a, b -> ShelfSearch.naturalCompare(a.title, b.title) }.take(4) }
    val read = books.count { it.finishedAt != null }
    val allKept by produceState<Boolean?>(null, books) { value = withContext(Dispatchers.IO) { books.all { isKept(it, store) } } }
    val reading = books.any { it.lastReadAt != null && it.finishedAt == null }
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        ) {
            if (!stack) {
                Column(Modifier.fillMaxSize().padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (r in 0..1) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (col in 0..1) Box(Modifier.weight(1f).fillMaxHeight()) {
                            covers.getOrNull(r * 2 + col)?.let { b ->
                                BookCover(file = if (b.hasCover) store.coverFile(b.id) else null, title = b.title, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
                            }
                        }
                    }
                }
            } else {
                // back covers first, each a little further right and up
                for (i in (minOf(covers.size, 3) - 1) downTo 0) {
                    val b = covers[i]
                    Box(Modifier.matchParentSize().padding(start = (6 + 7 * i).dp, top = (18 - 6 * i).dp, end = (20 - 7 * i).dp, bottom = (6 + 6 * i).dp)) {
                        BookCover(file = if (b.hasCover) store.coverFile(b.id) else null, title = b.title, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
                        if (i > 0) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.18f * i)))
                    }
                }
            }
            if (allKept != null) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { MarkDisc(allKept == true, compact) }
            selected?.let { SelectBadge(it) }
        }
        if (reading && read < books.size) LinearProgressIndicator(
            progress = { read.toFloat() / books.size },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(ShelfSearch.labeled(if (c.id.startsWith("s:")) "🔗" else "📚", c.name), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${books.size}권" + if (read > 0) " · ${read}권 읽음" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            CollectionMenuButton(onRename, onDelete, onSelectAll = onLongClick, size = if (compact) 26.dp else 36.dp, onMakeCollection = onMakeCollection, onUngroup = onUngroup)
        }
    }
}

/** A 컬렉션 head in the list (컬렉션별): folds its books in and out. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionHead(
    c: CollectionEntity, books: List<BookEntity>, open: Boolean, selected: Boolean?,
    onClick: () -> Unit, onLongClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
    onMakeCollection: (() -> Unit)? = null,
    onUngroup: (() -> Unit)? = null,
) {
    val read = books.count { it.finishedAt != null }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) Text(if (selected) "☑️ " else "⬜ ", fontSize = 16.sp)
        Column(Modifier.weight(1f)) {
            Text((if (c.id.startsWith("s:")) "🔗 " else "📚 ") + c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${books.size}권" + if (read > 0) " · ${read}권 읽음" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (read in 1 until books.size) {
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(progress = { read.toFloat() / books.size }, modifier = Modifier.width(70.dp).height(3.dp))
                }
            }
        }
        Text(if (open) "🔼" else "🔽")
        CollectionMenuButton(onRename, onDelete, onSelectAll = onLongClick, onMakeCollection = onMakeCollection, onUngroup = onUngroup)
    }
}

@Composable
private fun MoreButton(onClick: () -> Unit, size: Dp = 36.dp) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(
            androidx.compose.material.icons.Icons.Filled.MoreVert,
            contentDescription = "책 메뉴",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyShelf(onCatalog: () -> Unit, onImportFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("📚", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("서재가 비어 있습니다", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCatalog) { Text("☁️ 서버에서 책 찾기") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImportFile) { Text("📁 내 폰 · 구글 드라이브에서 가져오기") }
    }
}

/** Another device read further (progress sync): shown after the library's own progress. */
private fun aheadText(pct: Float?): String = pct?.let { " · ⇄ ${(it * 100).roundToInt()}%" }.orEmpty()

private fun progressText(b: BookEntity): String = when {
    b.finishedAt != null -> if (b.format == AudioLibraryLink.FORMAT) "다 들음" else "다 읽음"
    b.lastReadAt == null -> if (b.format == AudioLibraryLink.FORMAT) "새 오디오북" else "새 책"
    else -> "${(b.progress * 100).roundToInt()}%"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBook(
    b: BookEntity, store: BookStore, onClick: () -> Unit, onLongClick: () -> Unit, onMenu: () -> Unit,
    compact: Boolean = false, ahead: Float? = null, selected: Boolean? = null,
) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box {
            BookCover(
                file = if (b.hasCover) store.coverFile(b.id) else null,
                title = b.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
            )
            CoverMarks(b, store, small = compact)
            selected?.let { SelectBadge(it) }
        }
        if (b.lastReadAt != null && b.finishedAt == null) {
            LinearProgressIndicator(
                progress = { b.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(ShelfSearch.labeled(ShelfKind.of(b).emoji, b.title), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(b.author, progressText(b) + aheadText(ahead)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            MoreButton(onMenu, if (compact) 26.dp else 36.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListBook(
    b: BookEntity, store: BookStore, onClick: () -> Unit, onLongClick: () -> Unit, onMenu: () -> Unit,
    ahead: Float? = null, selected: Boolean? = null, indent: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = if (indent) 26.dp else 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            BookCover(
                file = if (b.hasCover) store.coverFile(b.id) else null,
                title = b.title,
                modifier = Modifier.width(52.dp).height(76.dp),
            )
            CoverMarks(b, store, small = true)
            selected?.let { SelectBadge(it) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ShelfSearch.labeled(ShelfKind.of(b).emoji, b.title), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            b.author?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (b.lastReadAt != null && b.finishedAt == null) {
                    LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.width(90.dp).height(3.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(progressText(b) + aheadText(ahead), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MoreButton(onMenu)
    }
}


/** Library tile widths (dp), smallest first; 100 gives three across on a phone. */
private val TILE_STEPS = listOf(72, 86, 100, 118, 140, 166)
private const val TILE_DEFAULT = 100

/** 32분 / 1시간 / 1시간 5분 */
internal fun minutesText(min: Int): String = when {
    min < 60 -> "${min}분"
    min % 60 == 0 -> "${min / 60}시간"
    else -> "${min / 60}시간 ${min % 60}분"
}

/** ⬇ on a green disc = kept on the phone; ☁ on a dark disc = on the server only, or only for now. White rim. */
@Composable
private fun MarkDisc(kept: Boolean, small: Boolean) {
    val size = if (small) 24.dp else 28.dp
    Box(
        Modifier.size(size)
            .background(if (kept) Color(0xFF1E8E4E) else Color(0xFF37474F).copy(alpha = 0.88f), CircleShape)
            .border(1.5.dp, Color.White, CircleShape)
            .semantics { contentDescription = if (kept) "폰에 받음" else "서버에만 있음 또는 임시 보관" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            androidx.compose.ui.res.painterResource(if (kept) com.oasisreader.app.R.drawable.ic_mark_phone else com.oasisreader.app.R.drawable.ic_mark_cloud),
            contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f),
        )
    }
}

/**
 * On a cover's bottom-right: ⬇ on a green disc = kept on the phone (서재에 보관, works offline),
 * ☁ on a dark disc = on the server only, or only kept for now in the cache (바로 읽기 / 임시).
 * Both have a white rim, so they read on any cover colour; the kind is in the text line.
 * While an audiobook comes, a ring around the mark shows how far.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CoverMarks(b: BookEntity, store: BookStore, small: Boolean) {
    val audio = b.format == AudioLibraryLink.FORMAT
    val dl = if (audio) com.oasisreader.app.audio.AudioDownloads.progress[b.id] else null
    val kept = remember(b.id, b.pinned, b.lastReadAt, b.fileBytes) { isKept(b, store) }
    val size = if (small) 24.dp else 28.dp
    Box(Modifier.align(Alignment.BottomEnd).padding(4.dp), contentAlignment = Alignment.Center) {
        MarkDisc(kept, small)
        if (dl != null) androidx.compose.material3.CircularProgressIndicator(
            progress = { dl }, strokeWidth = 2.5.dp, color = Color(0xFF7CD992), trackColor = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(size + 6.dp),
        )
    }
}

/** 종류 순서: the order the 종류 sort and the tabs use (▲▼ to move). */
@Composable
private fun KindOrderDialog(initial: List<ShelfKind>, onDismiss: () -> Unit, onSave: (List<ShelfKind>) -> Unit) {
    var order by remember { mutableStateOf(initial) }
    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in order.indices) return
        order = order.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("↕️ 종류 순서") },
        text = {
            Column {
                Text("종류 탭과 \"종류\" 정렬이 이 순서를 따릅니다. 같은 종류 안에서는 최근에 읽은 책부터.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                order.forEachIndexed { i, k ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${k.emoji} ${k.label}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { move(i, -1) }, enabled = i > 0) { Text("🔼") }
                        TextButton(onClick = { move(i, 1) }, enabled = i < order.size - 1) { Text("🔽") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(order); onDismiss() }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * Which series a book belongs to, for 🔗 시리즈별로 묶기: the server series or folder it came from
 * (since 1.2), else its title without the volume number; per kind, so a novel and a comic of the same
 * name stay apart. Null: not part of a series.
 */
private fun seriesKeyOf(b: BookEntity): String? {
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
