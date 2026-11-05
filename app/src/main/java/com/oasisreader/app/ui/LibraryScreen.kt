package com.oasisreader.app.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.db.BookEntity
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

enum class ShelfFilter(val label: String) {
    ALL("전체"), READING("읽는 중"), UNREAD("안 읽음"), DONE("다 읽음");

    fun matches(b: BookEntity) = when (this) {
        ALL -> true
        READING -> b.finishedAt == null && b.lastReadAt != null
        UNREAD -> b.finishedAt == null && b.lastReadAt == null
        DONE -> b.finishedAt != null
    }
}

/** What a 서재 book is: shown first in its text line, filtered by the 🏷️ chip, sorted by 종류. */
enum class ShelfKind(val emoji: String, val label: String) {
    BOOK("📖", "책"), COMIC("🖼️", "만화"), PDF("📄", "PDF"), AUDIO("🎧", "오디오북");

    companion object {
        fun of(b: BookEntity) = when (b.format) {
            BookStore.FORMAT_COMIC -> COMIC
            BookStore.FORMAT_PDF -> PDF
            com.oasisreader.app.audio.AudioLibraryLink.FORMAT -> AUDIO
            else -> BOOK
        }

        /** The user's order for 종류 sorting (prefs "kind_order", names joined by commas). */
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

/** Ascending order for each criterion; [desc] turns it around. */
private fun sortShelf(list: List<BookEntity>, sort: ShelfSort, desc: Boolean, kinds: List<ShelfKind> = ShelfKind.entries): List<BookEntity> {
    val byTitle = Comparator<BookEntity> { a, b -> shelfCollator.compare(a.title, b.title) }
    val asc: Comparator<BookEntity> = when (sort) {
        ShelfSort.RECENT -> compareBy<BookEntity> { it.lastReadAt ?: it.addedAt }
        ShelfSort.ADDED -> compareBy { it.addedAt }
        ShelfSort.TITLE -> byTitle
        ShelfSort.AUTHOR -> Comparator { a, b ->
            val c = shelfCollator.compare(a.author ?: "\uFFFF", b.author ?: "\uFFFF")
            if (c != 0) c else shelfCollator.compare(a.title, b.title)
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

/**
 * The library. Cover grid on the wide inner screen, list on the cover screen
 * (decided by the window width, so it follows folding/unfolding).
 */
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
    onDelete: (BookEntity, Boolean) -> Unit,
    onRename: (BookEntity, String) -> Unit,
    onPin: (BookEntity, Boolean) -> Unit = { _, _ -> },
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val shelfPrefs = remember { ctx.getSharedPreferences("library", android.content.Context.MODE_PRIVATE) }
    // tile size: smallest tile width in dp (3 across on a phone at the default)
    var tileDp by remember { mutableStateOf(shelfPrefs.getInt("tile_dp", TILE_DEFAULT).let { v -> TILE_STEPS.minBy { kotlin.math.abs(it - v) } }) }
    fun setTile(v: Int) { tileDp = v; shelfPrefs.edit().putInt("tile_dp", v).apply() }
    var gridChoice by remember {
        mutableStateOf(when (shelfPrefs.getString("layout", null)) { "grid" -> true; "list" -> false; else -> null })
    }
    var sort by remember {
        mutableStateOf(runCatching { ShelfSort.valueOf(shelfPrefs.getString("sort", null) ?: "") }.getOrDefault(ShelfSort.RECENT))
    }
    var sortDesc by remember { mutableStateOf(if (shelfPrefs.contains("sort_desc")) shelfPrefs.getBoolean("sort_desc", true) else sort.defaultDesc) }
    // 컬렉션: the user's own shelves (none chosen = the whole 서재)
    val colDb = remember { com.oasisreader.app.data.db.AppDatabase.get(ctx) }
    val collections by remember { colDb.collections().observeAll() }.collectAsState(initial = emptyList())
    val links by remember { colDb.collections().observeLinks() }.collectAsState(initial = emptyList())
    var colId by remember { mutableStateOf(shelfPrefs.getString("collection", null)) }
    fun setCol(id: String?) { colId = id; shelfPrefs.edit().putString("collection", id).apply() }
    val bookIds = remember(books) { books.map { it.id }.toSet() }
    val colCounts = remember(links, bookIds) { links.filter { it.bookId in bookIds }.groupingBy { it.collectionId }.eachCount() }
    val activeCol = colId?.takeIf { id -> collections.any { it.id == id } }
    val base = remember(books, links, activeCol) {
        if (activeCol == null) books else links.filter { it.collectionId == activeCol }.map { it.bookId }.toSet().let { on -> books.filter { it.id in on } }
    }
    var newCol by remember { mutableStateOf(false) }
    var editCols by remember { mutableStateOf(false) }
    var pickColFor by remember { mutableStateOf<BookEntity?>(null) }
    // 종류: one kind or all (🏷️ chip), and the order for 종류 sorting
    var kindName by remember { mutableStateOf(shelfPrefs.getString("kind", null)) }
    val kind = kindName?.let { n -> ShelfKind.entries.firstOrNull { it.name == n } }
    fun setKind(k: ShelfKind?) { kindName = k?.name; shelfPrefs.edit().putString("kind", k?.name).apply() }
    var kindOrder by remember { mutableStateOf(ShelfKind.order(shelfPrefs.getString("kind_order", null))) }
    var editKindOrder by remember { mutableStateOf(false) }
    val kindBase = remember(base, kind) { if (kind == null) base else base.filter { ShelfKind.of(it) == kind } }
    val shown = remember(kindBase, filter, sort, sortDesc, kindOrder) { sortShelf(kindBase.filter { filter.matches(it) }, sort, sortDesc, kindOrder) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // header line: how many books, and today's reading time (all books)
        val timeDb = remember { com.oasisreader.app.data.db.AppDatabase.get(ctx) }
        val times by remember { timeDb.readingTime().observeAll() }.collectAsState(initial = emptyList())
        val todayKey = java.time.LocalDate.now().toString()
        val todayMin = remember(times, todayKey) { (times.filter { it.day == todayKey }.sumOf { it.seconds } / 60).toInt() }
        val brandSub = if (!loaded) null else "서재 ${books.size}권 · 오늘 ${minutesText(todayMin)}"
        TopBar("Reader", brand = true, brandSub = brandSub) {
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

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(MiniChipGap),
        ) {
            // tiles or list: automatic by width until chosen here
            val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
            val tiles = gridChoice ?: wide
            MiniChip(
                if (tiles) "🔲 타일" else "📃 목록", plain = true,
                onClick = {
                    gridChoice = !tiles
                    shelfPrefs.edit().putString("layout", if (!tiles) "grid" else "list").apply()
                },
                description = if (tiles) "타일로 보는 중 (누르면 목록)" else "목록으로 보는 중 (누르면 타일)",
            )
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
                    shelfPrefs.edit().putString("sort", o.name).putBoolean("sort_desc", d).apply()
                },
            )
            CollectionChip(
                collections, colCounts, activeCol, books.size,
                onSelect = { setCol(it) }, onNew = { newCol = true }, onEdit = { editCols = true },
            )
            // 종류: 📖 책 · 🖼️ 만화 · 📄 PDF · 🎧 오디오북
            var kindOpen by remember { mutableStateOf(false) }
            Box {
                MiniChip(
                    if (kind == null) "🏷️ 종류" else "${kind.emoji} ${kind.label}",
                    onClick = { kindOpen = true }, selected = kind != null, description = "종류",
                )
                androidx.compose.material3.DropdownMenu(expanded = kindOpen, onDismissRequest = { kindOpen = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("전체 ${base.size}") }, leadingIcon = { CheckMark(kind == null) },
                        onClick = { kindOpen = false; setKind(null) },
                    )
                    kindOrder.forEach { k ->
                        val n = base.count { ShelfKind.of(it) == k }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("${k.emoji} ${k.label} $n") }, leadingIcon = { CheckMark(kind == k) },
                            enabled = n > 0 || kind == k,
                            onClick = { kindOpen = false; setKind(k) },
                        )
                    }
                }
            }
            // 전체 / 읽는 중 / 안 읽음 / 다 읽음 — one drop-down
            var filterOpen by remember { mutableStateOf(false) }
            Box {
                MiniChip(
                    "🔖 ${filter.label} ${kindBase.count { filter.matches(it) }}",
                    onClick = { filterOpen = true },
                    selected = filter != ShelfFilter.ALL,
                )
                androidx.compose.material3.DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                    ShelfFilter.entries.forEach { f ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("${f.label} ${kindBase.count { f.matches(it) }}") },
                            leadingIcon = { CheckMark(f == filter) },
                            onClick = { filterOpen = false; filterName = f.name },
                        )
                    }
                }
            }
        }

        if (loaded && books.isEmpty()) {
            EmptyShelf(onCatalog, onImportFile)
            return@Column
        }
        if (loaded && activeCol != null && base.isEmpty()) {
            Text(
                "이 컬렉션은 비어 있습니다.\n서재 전체에서 책을 길게 눌러 \"📚 컬렉션에 넣기\"를 고르세요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (gridChoice ?: (maxWidth >= 600.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(tileDp.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (tileDp < 100) 10.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (tileDp < 100) 16.dp else 20.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                ) {
                    items(shown, key = { it.id }) { b ->
                        GridBook(b, store, onClick = { onOpen(b) }, onMenu = { menuFor = b }, compact = tileDp < 110, ahead = aheadMap[b.id])
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.id }) { b ->
                        ListBook(b, store, onClick = { onOpen(b) }, onMenu = { menuFor = b }, ahead = aheadMap[b.id])
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    infoFor?.let { b -> BookInfoDialog(b, store) { infoFor = null } }
    if (newCol) CollectionNameDialog("새 컬렉션", onDismiss = { newCol = false }) { name ->
        newCol = false
        scope.launch { setCol(ShelfCollections.create(colDb, name).id) }
    }
    if (editCols) CollectionEditDialog(collections, colCounts) { editCols = false }
    if (editKindOrder) KindOrderDialog(kindOrder, onDismiss = { editKindOrder = false }) { order ->
        kindOrder = order
        shelfPrefs.edit().putString("kind_order", order.joinToString(",") { it.name }).apply()
        if (sort != ShelfSort.KIND) { sort = ShelfSort.KIND; sortDesc = false; shelfPrefs.edit().putString("sort", sort.name).putBoolean("sort_desc", false).apply() }
    }
    pickColFor?.let { b -> CollectionPickDialog(b, collections, links) { pickColFor = null } }
    exportFor?.let { b -> HighlightExportDialog(b.id) { exportFor = null } }

    menuFor?.let { b ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(b.title) },
            text = {
                Column {
                    Text(
                        (if (b.format == com.oasisreader.app.audio.AudioLibraryLink.FORMAT)
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
                    TextButton(onClick = { menuFor = null; renaming = b }) { Text("✏️ 제목 바꾸기") }
                    val isAudio = b.format == com.oasisreader.app.audio.AudioLibraryLink.FORMAT
                    if (isAudio) {
                        // 오프라인: copy the tracks to the phone, or go back to streaming
                        val ad = com.oasisreader.app.audio.AudioDownloads
                        val dl = ad.progress[b.id]
                        val err = ad.errors[b.id]
                        fun startDl() {
                            menuFor = null
                            askNotif()
                            ad.enqueue(ctx, b)
                            android.widget.Toast.makeText(ctx, "「${b.title}」 받기를 시작했습니다. 앱을 닫아도 계속 받고, 알림에서 진행을 볼 수 있습니다.", android.widget.Toast.LENGTH_LONG).show()
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
                androidx.compose.material3.OutlinedTextField(
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
        val hlCount by androidx.compose.runtime.produceState(0, b.id) {
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

@Composable
private fun MoreButton(onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 36.dp) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        androidx.compose.material3.Icon(
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
    b.finishedAt != null -> if (b.format == com.oasisreader.app.audio.AudioLibraryLink.FORMAT) "다 들음" else "다 읽음"
    b.lastReadAt == null -> if (b.format == com.oasisreader.app.audio.AudioLibraryLink.FORMAT) "새 오디오북" else "새 책"
    else -> "${(b.progress * 100).roundToInt()}%"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBook(b: BookEntity, store: BookStore, onClick: () -> Unit, onMenu: () -> Unit, compact: Boolean = false, ahead: Float? = null) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onMenu)) {
        Box {
            BookCover(
                file = if (b.hasCover) store.coverFile(b.id) else null,
                title = b.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
            )
            CoverMarks(b, store, small = compact)
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
                Text(ShelfKind.of(b).emoji + " " + b.title, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
private fun ListBook(b: BookEntity, store: BookStore, onClick: () -> Unit, onMenu: () -> Unit, ahead: Float? = null) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMenu)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            BookCover(
                file = if (b.hasCover) store.coverFile(b.id) else null,
                title = b.title,
                modifier = Modifier.width(52.dp).height(76.dp),
            )
            CoverMarks(b, store, small = true)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ShelfKind.of(b).emoji + " " + b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/**
 * On a cover's bottom-right: ⬇ on a green disc = kept on the phone (서재에 보관, works offline),
 * ☁ on a dark disc = on the server only, or only kept for now in the cache (바로 읽기 / 임시).
 * Both have a white rim, so they read on any cover colour; the kind is in the text line.
 * While an audiobook comes, a ring around the mark shows how far.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CoverMarks(b: BookEntity, store: BookStore, small: Boolean) {
    val audio = b.format == com.oasisreader.app.audio.AudioLibraryLink.FORMAT
    val dl = if (audio) com.oasisreader.app.audio.AudioDownloads.progress[b.id] else null
    val kept = b.pinned && if (audio) b.fileBytes > 0
        else remember(b.id, b.pinned, b.lastReadAt, b.fileBytes) { store.originalOf(b.id) != null || store.contentDir(b.id).isDirectory }
    val size = if (small) 24.dp else 28.dp
    Box(Modifier.align(Alignment.BottomEnd).padding(4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size)
                .background(if (kept) Color(0xFF1E8E4E) else Color(0xFF37474F).copy(alpha = 0.88f), androidx.compose.foundation.shape.CircleShape)
                .border(1.5.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
                .semantics { contentDescription = if (kept) "폰에 받음" else "서버에만 있음 또는 임시 보관" },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(if (kept) com.oasisreader.app.R.drawable.ic_mark_phone else com.oasisreader.app.R.drawable.ic_mark_cloud),
                contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f),
            )
        }
        if (dl != null) androidx.compose.material3.CircularProgressIndicator(
            progress = { dl }, strokeWidth = 2.5.dp, color = Color(0xFF7CD992), trackColor = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(size + 6.dp),
        )
    }
}

/** 종류 순서: the order the 종류 sort uses (▲▼ to move). */
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
                Text("정렬을 \"종류\"로 하면 이 순서로 묶어 보여 줍니다. 같은 종류 안에서는 최근에 읽은 책부터.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
