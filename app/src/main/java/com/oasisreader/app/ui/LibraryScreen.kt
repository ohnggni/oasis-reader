package com.oasisreader.app.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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

enum class ShelfSort(val label: String, val kind: SortKind, val defaultDesc: Boolean) {
    RECENT("읽은 날짜", SortKind.DATE, true),
    ADDED("추가한 날짜", SortKind.DATE, true),
    TITLE("제목", SortKind.TEXT, false),
    AUTHOR("저자", SortKind.TEXT, false),
    PROGRESS("진행률", SortKind.AMOUNT, true);
}

private val shelfCollator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)

/** Ascending order for each criterion; [desc] turns it around. */
private fun sortShelf(list: List<BookEntity>, sort: ShelfSort, desc: Boolean): List<BookEntity> {
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
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onHighlights: () -> Unit,
    onDelete: (BookEntity) -> Unit,
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
    val shown = remember(books, filter, sort, sortDesc) { sortShelf(books.filter { filter.matches(it) }, sort, sortDesc) }

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
                        Triple("🖍️", "하이라이트", onHighlights),
                        Triple("📊", "독서 통계", onStats),
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
                onChange = { o, d ->
                    sort = o; sortDesc = d
                    shelfPrefs.edit().putString("sort", o.name).putBoolean("sort_desc", d).apply()
                },
            )
            // 전체 / 읽는 중 / 안 읽음 / 다 읽음 — one drop-down
            var filterOpen by remember { mutableStateOf(false) }
            Box {
                MiniChip(
                    "🔖 ${filter.label} ${books.count { filter.matches(it) }}",
                    onClick = { filterOpen = true },
                    selected = filter != ShelfFilter.ALL,
                )
                androidx.compose.material3.DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                    ShelfFilter.entries.forEach { f ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("${f.label} ${books.count { f.matches(it) }}") },
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
                    TextButton(onClick = { menuFor = null; renaming = b }) { Text("✏️ 제목 바꾸기") }
                    val isAudio = b.format == com.oasisreader.app.audio.AudioLibraryLink.FORMAT
                    if (isAudio) {
                        // 오프라인: copy the tracks to the phone, or go back to streaming
                        val dl = com.oasisreader.app.audio.AudioDownloads.progress[b.id]
                        when {
                            dl != null -> TextButton(onClick = { com.oasisreader.app.audio.AudioDownloads.cancel(b.id) }) {
                                Text("⬇️ 받는 중 ${(dl * 100).roundToInt()}% · 누르면 취소")
                            }
                            b.pinned && b.fileBytes > 0 -> TextButton(onClick = {
                                menuFor = null
                                scope.launch { com.oasisreader.app.audio.AudioDownloads.remove(ctx, b) }
                            }) { Text("☁️ 폰에서 지우고 스트리밍으로 듣기") }
                            else -> TextButton(onClick = {
                                menuFor = null
                                com.oasisreader.app.audio.AudioDownloads.start(ctx, b) { err ->
                                    android.widget.Toast.makeText(ctx, err?.let { "받지 못했습니다: $it" } ?: "「${b.title}」을(를) 폰에 받았습니다. 인터넷 없이 들을 수 있습니다.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }) { Text("⬇️ 폰에 받기 (오프라인으로 듣기)") }
                        }
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
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("서재에서 지우시겠습니까?") },
            text = { Text("「${b.title}」 파일과 하이라이트가 이 폰에서 지워집니다. 독서 시간 통계는 남습니다.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete(b) }) {
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

private fun progressText(b: BookEntity): String = (if (b.pinned) "" else "☁️ ") + (when (b.format) { BookStore.FORMAT_COMIC -> "🖼 "; BookStore.FORMAT_PDF -> "📄 "; com.oasisreader.app.audio.AudioLibraryLink.FORMAT -> "🎧 "; else -> "" }) + when {
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
            if (b.format == com.oasisreader.app.audio.AudioLibraryLink.FORMAT) AudioBadge(b, compact, Modifier.align(Alignment.BottomStart))
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
                Text(b.title, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(b.author, progressText(b) + aheadText(ahead) + downloadText(b)).joinToString(" · "),
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
            if (b.format == com.oasisreader.app.audio.AudioLibraryLink.FORMAT) AudioBadge(b, true, Modifier.align(Alignment.BottomStart))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                Text(progressText(b) + aheadText(ahead) + downloadText(b), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** A kept audiobook being copied to the phone: " · ⬇ 35%". */
@Composable
private fun downloadText(b: BookEntity): String =
    com.oasisreader.app.audio.AudioDownloads.progress[b.id]?.let { " · ⬇ ${(it * 100).roundToInt()}%" }.orEmpty()

/** On an audiobook's cover: 🎧 and, when it is on the phone, ⬇ (works offline). */
@Composable
private fun AudioBadge(b: BookEntity, compact: Boolean, modifier: Modifier = Modifier) {
    val kept = b.pinned && b.fileBytes > 0
    Row(
        modifier.padding(4.dp)
            .background(Color.Black.copy(alpha = 0.62f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (compact) (if (kept) "🎧⬇" else "🎧") else (if (kept) "🎧 오디오북 ⬇" else "🎧 오디오북"),
            color = Color.White, fontSize = if (compact) 10.sp else 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}
