package com.oasisreader.app.ui

import com.oasisreader.app.data.ShelfKind
import com.oasisreader.app.data.ShelfSearch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 선택 모드 top bar: N권 선택됨 · ✖️ · ☑️ 전체 (what is on screen now). */
@Composable
internal fun SelectionBar(count: Int, allSelected: Boolean, onClose: () -> Unit, onAll: () -> Unit) {
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
internal fun CollectionMenuButton(onRename: () -> Unit, onDelete: () -> Unit, onSelectAll: () -> Unit, size: Dp = 36.dp, onMakeCollection: (() -> Unit)? = null, onUngroup: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(size)) {
            Icon(androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = "컬렉션 메뉴", tint = androidx.compose.material3.LocalContentColor.current, modifier = Modifier.size(if (size < 36.dp) 18.dp else 24.dp))
        }
        OgMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { EmojiText("☑️ 모두 고르기") }, onClick = { open = false; onSelectAll() })
            if (onMakeCollection != null) {
                // an automatic series group: nothing to rename or delete; it can become a 컬렉션
                DropdownMenuItem(text = { EmojiText("📚 컬렉션으로 만들기") }, onClick = { open = false; onMakeCollection() })
                if (onUngroup != null) DropdownMenuItem(text = { Text("🔓 이 묶음 풀기") }, onClick = { open = false; onUngroup() })
            } else {
                DropdownMenuItem(text = { Text("✏️ 이름 바꾸기") }, onClick = { open = false; onRename() })
                DropdownMenuItem(text = { EmojiText("🗑️ 컬렉션 지우기…", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onDelete() })
            }
        }
    }
}

/** A tick in the corner of a cover in 선택 모드. */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.SelectBadge(on: Boolean) {
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
internal fun FolderTile(
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
            if (selected == null) MenuDisc(compact) {
                CollectionMenuButton(onRename, onDelete, onSelectAll = onLongClick, size = if (compact) 24.dp else 28.dp, onMakeCollection = onMakeCollection, onUngroup = onUngroup)
            }
            selected?.let { SelectBadge(it) }
        }
        if (reading && read < books.size) LinearProgressIndicator(
            progress = { read.toFloat() / books.size },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(ShelfSearch.labeled(if (c.id.startsWith("s:")) "🔗" else "📚", c.name), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${books.size}권" + if (read > 0) " · ${read}권 읽음" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
}

/** A 컬렉션 head in the list (컬렉션별): folds its books in and out. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CollectionHead(
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
internal fun MoreButton(onClick: () -> Unit, size: Dp = 36.dp) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(
            androidx.compose.material.icons.Icons.Filled.MoreVert,
            contentDescription = "책 메뉴",
            tint = androidx.compose.material3.LocalContentColor.current,
            modifier = Modifier.size(if (size < 36.dp) 18.dp else 24.dp),
        )
    }
}

@Composable
internal fun EmptyShelf(onCatalog: () -> Unit, onImportFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmojiText("📚", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("서재가 비어 있습니다", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCatalog) { EmojiText("☁️ 서버에서 가져오기") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImportFile) { EmojiText("📄 파일에서 가져오기") }
    }
}

/** Another device read further (progress sync): shown after the library's own progress. */
internal fun aheadText(pct: Float?): String = pct?.let { " · ⇄ ${(it * 100).roundToInt()}%" }.orEmpty()

internal fun progressText(b: BookEntity): String = when {
    b.finishedAt != null -> if (b.format == AudioLibraryLink.FORMAT) "다 들음" else "다 읽음"
    b.lastReadAt == null -> if (b.format == AudioLibraryLink.FORMAT) "새 오디오북" else "새 책"
    else -> "${(b.progress * 100).roundToInt()}%"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GridBook(
    b: BookEntity, store: BookStore, onClick: () -> Unit, onLongClick: () -> Unit, onMenu: () -> Unit,
    compact: Boolean = false, ahead: Float? = null, selected: Boolean? = null, showKind: Boolean = true,
) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box {
            BookCover(
                file = if (b.hasCover) store.coverFile(b.id) else null,
                title = b.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
            )
            CoverMarks(b, store, small = compact)
            // ⋮ sits on the cover (bottom-left) so the title below gets the tile's full width
            if (selected == null) MenuDisc(compact) { MoreButton(onMenu, if (compact) 24.dp else 28.dp) }
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
                Text(
                    if (showKind) ShelfSearch.labeled(ShelfKind.of(b).emoji, b.title) else b.title,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(b.author, progressText(b) + aheadText(ahead)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Marks laid on a cover: a see-through dark disc with a white mark, like buttons on a photo (E-INK: solid black on white). */
internal val CoverScrim = Color.Black.copy(alpha = 0.40f)

/** The disc a tile's ⋮ sits in, on the cover's bottom-left. */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.MenuDisc(small: Boolean, content: @Composable () -> Unit) {
    val size = if (small) 24.dp else 28.dp
    val eink = com.oasisreader.app.ui.theme.ThemeMode.eink
    Box(
        Modifier.align(Alignment.BottomStart).padding(4.dp).size(size)
            .background(if (eink) Color.White else CoverScrim, CircleShape)
            .let { if (eink) it.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape) else it },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (eink) Color.Black else Color.White) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ListBook(
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
internal val TILE_STEPS = listOf(72, 86, 100, 118, 140, 166)
internal const val TILE_DEFAULT = 100

/** 32분 / 1시간 / 1시간 5분 */
internal fun minutesText(min: Int): String = when {
    min < 60 -> "${min}분"
    min % 60 == 0 -> "${min / 60}시간"
    else -> "${min / 60}시간 ${min % 60}분"
}

/** ⬇ on a green disc = kept on the phone; ☁ on a dark disc = on the server only, or only for now. White rim. */
@Composable
internal fun MarkDisc(kept: Boolean, small: Boolean) {
    val size = if (small) 24.dp else 28.dp
    if (OgIcons.style != OgIcons.EMOJI) {
        // one-colour style: a see-through dark disc, ⬇ = on the phone, ☁ = server only (E-INK: kept filled black, server-only hollow)
        val eink = com.oasisreader.app.ui.theme.ThemeMode.eink
        val ink = if (eink) (if (kept) Color.White else Color.Black) else Color.White
        Box(
            Modifier.size(size)
                .background(if (eink) (if (kept) Color.Black else Color.White) else CoverScrim, CircleShape)
                .let { if (eink) it.border(1.dp, Color.Black, CircleShape) else it }
                .semantics { contentDescription = if (kept) "폰에 받음" else "서버에만 있음 또는 임시 보관" },
            contentAlignment = Alignment.Center,
        ) {
            OgIcons.icon(if (kept) "download" else "cloud")?.let {
                Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.64f))
            }
        }
        return
    }
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
internal fun androidx.compose.foundation.layout.BoxScope.CoverMarks(b: BookEntity, store: BookStore, small: Boolean) {
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
internal fun KindOrderDialog(initial: List<ShelfKind>, onDismiss: () -> Unit, onSave: (List<ShelfKind>) -> Unit) {
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
