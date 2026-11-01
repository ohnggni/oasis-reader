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

enum class ShelfFilter(val label: String) {
    ALL("전체"), READING("읽는 중"), UNREAD("안 읽음"), DONE("다 읽음");

    fun matches(b: BookEntity) = when (this) {
        ALL -> true
        READING -> b.finishedAt == null && b.lastReadAt != null
        UNREAD -> b.finishedAt == null && b.lastReadAt == null
        DONE -> b.finishedAt != null
    }
}

enum class ShelfSort(val label: String) {
    RECENT("최근 읽은 순"), ADDED("추가한 순"), TITLE("제목"), AUTHOR("저자"), PROGRESS("진행률");
}

private val shelfCollator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)

private fun sortShelf(list: List<BookEntity>, sort: ShelfSort): List<BookEntity> = when (sort) {
    ShelfSort.RECENT -> list.sortedByDescending { it.lastReadAt ?: it.addedAt }
    ShelfSort.ADDED -> list.sortedByDescending { it.addedAt }
    ShelfSort.TITLE -> list.sortedWith { a, b -> shelfCollator.compare(a.title, b.title) }
    ShelfSort.AUTHOR -> list.sortedWith { a, b ->
        val c = shelfCollator.compare(a.author ?: "\uFFFF", b.author ?: "\uFFFF")
        if (c != 0) c else shelfCollator.compare(a.title, b.title)
    }
    ShelfSort.PROGRESS -> list.sortedWith(
        compareBy<BookEntity> { it.finishedAt != null }.thenByDescending { it.progress }.thenBy { it.title }
    )
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
    var menuFor by remember { mutableStateOf<BookEntity?>(null) }
    var infoFor by remember { mutableStateOf<BookEntity?>(null) }
    var exportFor by remember { mutableStateOf<BookEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<BookEntity?>(null) }
    var renaming by remember { mutableStateOf<BookEntity?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
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
    var sortOpen by remember { mutableStateOf(false) }
    val shown = remember(books, filter, sort) { sortShelf(books.filter { filter.matches(it) }, sort) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Reader", brand = true) {
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // tiles or list: automatic by width until chosen here
            val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
            val tiles = gridChoice ?: wide
            androidx.compose.material3.AssistChip(
                onClick = {
                    gridChoice = !tiles
                    shelfPrefs.edit().putString("layout", if (!tiles) "grid" else "list").apply()
                },
                label = { Text(if (tiles) "▦ 타일" else "☰ 목록") },
            )
            if (tiles) {
                // one chip like its neighbours; the size slider floats under it
                var sizeOpen by remember { mutableStateOf(false) }
                Box {
                    androidx.compose.material3.AssistChip(
                        onClick = { sizeOpen = true },
                        label = { Text("⤢ 크기") },
                    )
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
            Box {
                androidx.compose.material3.AssistChip(
                    onClick = { sortOpen = true },
                    label = { Text("↕ ${sort.label}") },
                )
                androidx.compose.material3.DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    ShelfSort.entries.forEach { o ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text((if (o == sort) "✓ " else "    ") + o.label) },
                            onClick = { sortOpen = false; sort = o; shelfPrefs.edit().putString("sort", o.name).apply() },
                        )
                    }
                }
            }
            ShelfFilter.entries.forEach { f ->
                val n = books.count { f.matches(it) }
                FilterChip(
                    selected = filter == f,
                    onClick = { filterName = f.name },
                    label = { Text("${f.label} $n") },
                )
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
                        GridBook(b, store, onClick = { onOpen(b) }, onMenu = { menuFor = b }, compact = tileDp < 110)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.id }) { b ->
                        ListBook(b, store, onClick = { onOpen(b) }, onMenu = { menuFor = b })
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
                        listOfNotNull(b.author, "%.1f MB".format(b.fileBytes / 1048576.0), b.sourceUrl?.let { "서버에서 받음" })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { menuFor = null; infoFor = b }) { Text("ℹ️ 책 정보") }
                    TextButton(onClick = { menuFor = null; renaming = b }) { Text("✏️ 제목 바꾸기") }
                    TextButton(onClick = { menuFor = null; exportFor = b }) { Text("📝 하이라이트 내보내기") }
                    if (b.sourceUrl != null) TextButton(onClick = { menuFor = null; onPin(b, !b.pinned) }) {
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

private fun progressText(b: BookEntity): String = (if (b.pinned) "" else "☁️ ") + (when (b.format) { BookStore.FORMAT_COMIC -> "🖼 "; BookStore.FORMAT_PDF -> "📄 "; else -> "" }) + when {
    b.finishedAt != null -> "다 읽음"
    b.lastReadAt == null -> "새 책"
    else -> "${(b.progress * 100).roundToInt()}%"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBook(b: BookEntity, store: BookStore, onClick: () -> Unit, onMenu: () -> Unit, compact: Boolean = false) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onMenu)) {
        BookCover(
            file = if (b.hasCover) store.coverFile(b.id) else null,
            title = b.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
        )
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
                    listOfNotNull(b.author, progressText(b)).joinToString(" · "),
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
private fun ListBook(b: BookEntity, store: BookStore, onClick: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMenu)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(
            file = if (b.hasCover) store.coverFile(b.id) else null,
            title = b.title,
            modifier = Modifier.width(52.dp).height(76.dp),
        )
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
                Text(progressText(b), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MoreButton(onMenu)
    }
}


/** Library tile widths (dp), smallest first; 100 gives three across on a phone. */
private val TILE_STEPS = listOf(72, 86, 100, 118, 140, 166)
private const val TILE_DEFAULT = 100
