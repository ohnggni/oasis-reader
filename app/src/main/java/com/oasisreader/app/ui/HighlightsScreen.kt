package com.oasisreader.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.HighlightEntity
import com.oasisreader.app.data.db.forgetRemovedIfEmpty
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which highlights the screen shows. */
private enum class HlFilter(val label: String) { ALL("전체"), ON_SHELF("📚 서재에 있는 책"), REMOVED("🗑️ 지운 책"), AUDIO("🎧 오디오북 책갈피") }

/**
 * All highlights, by book (newest first). A book still on the 서재: tap a highlight to open it
 * there. A removed book (its highlights were kept): marked 🗑️, cannot be opened, but its
 * highlights can still be read, copied, shared, exported and deleted.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HighlightsScreen(onBack: () -> Unit, onOpen: (HighlightEntity) -> Unit, onPlayMark: (com.oasisreader.app.data.db.AudioMarkEntity) -> Unit = {}) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val db = AppDatabase.get(context.applicationContext)
    val scope = rememberCoroutineScope()
    val highlights by remember { db.highlights().observeAll() }.collectAsState(initial = null)
    val books by remember { db.books().observeAll() }.collectAsState(initial = emptyList())
    val removed by remember { db.removedBooks().observeAll() }.collectAsState(initial = emptyList())
    val marks by remember { db.audioMarks().observeAll() }.collectAsState(initial = emptyList())
    val titles = remember(books) { books.associate { it.id to it.title } }
    val removedTitles = remember(removed) { removed.associate { it.id to it.title } }
    val fmt = remember { SimpleDateFormat("yyyy.M.d", Locale.KOREA) }
    var filter by rememberSaveable { mutableStateOf(HlFilter.ALL.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var exportFor by remember { mutableStateOf<String?>(null) }
    var deleteBook by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<HighlightEntity?>(null) }
    fun titleOf(id: String) = titles[id] ?: removedTitles[id] ?: "지운 책"

    // group by book, books ordered by their newest highlight; inside a book, reading order
    val groups = remember(highlights, filter, query, titles, removedTitles) {
        val q = query.trim()
        highlights.orEmpty()
            .filter { h ->
                when (HlFilter.valueOf(filter)) {
                    HlFilter.ALL -> true
                    HlFilter.ON_SHELF -> titles.containsKey(h.bookId)
                    HlFilter.REMOVED -> !titles.containsKey(h.bookId)
                    HlFilter.AUDIO -> false
                }
            }
            .filter { h -> q.isEmpty() || h.text.contains(q, true) || (h.note?.contains(q, true) == true) || titleOf(h.bookId).contains(q, true) }
            .groupBy { it.bookId }
            .entries.sortedByDescending { e -> e.value.maxOf { it.createdAt } }
            .map { it.key to it.value.sortedWith(compareBy<HighlightEntity> { h -> h.spineIndex }.thenBy { h -> h.createdAt }) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("하이라이트", onBack = onBack) { HighlightJsonExportMenu(enabled = !highlights.isNullOrEmpty()) }
        if (highlights != null && highlights!!.isEmpty() && marks.isEmpty()) {
            Text(
                "아직 하이라이트가 없습니다.\n책에서 글을 길게 눌러 고르고 색을 누르면 여기에 모입니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("🔍 하이라이트·메모·책 제목 찾기") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(MiniChipGap),
        ) {
            HlFilter.entries.forEach { f -> MiniChip(f.label, selected = filter == f.name, onClick = { filter = f.name }) }
        }
        if (filter == HlFilter.AUDIO.name) {
            com.oasisreader.app.audio.AudioMarksPanel(marks, query, ::titleOf, { id -> titles.containsKey(id) }, onPlayMark)
            return@Column
        }
        CountLine(groups.sumOf { it.second.size }.takeIf { highlights != null }?.let { "🖍️ ${it}개 · 책 ${groups.size}권" })
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            groups.forEach { (bookId, list) ->
                val onShelf = titles.containsKey(bookId)
                item(key = "h_$bookId") {
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (onShelf) "📖 " else "🗑️ ") + titleOf(bookId),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (onShelf) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${list.size}개" + if (onShelf) "" else " · 지운 책 (책으로 이동은 안 됩니다. 다시 받으면 이어집니다)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "이 책 하이라이트 메뉴") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("📝 이 책 하이라이트 내보내기") }, onClick = { menu = false; exportFor = bookId })
                                DropdownMenuItem(text = { Text("📋 모두 복사") }, onClick = {
                                    menu = false
                                    copy(context, titleOf(bookId) + "\n\n" + list.joinToString("\n\n") { h -> "“${h.text}”" + (h.note?.let { "\n— $it" } ?: "") })
                                })
                                DropdownMenuItem(
                                    text = { Text("🗑️ 이 책 하이라이트 모두 지우기", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; deleteBook = bookId },
                                )
                            }
                        }
                    }
                }
                items(list, key = { it.id }) { h ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { if (onShelf) onOpen(h) else picked = h },
                                onLongClick = { picked = h },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Box(
                            Modifier.width(4.dp).height(48.dp).clip(RoundedCornerShape(2.dp))
                                .background(parseColor(h.color))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            h.note?.takeIf { it.isNotBlank() }?.let {
                                Text("📝 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                fmt.format(Date(h.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    exportFor?.let { id -> HighlightExportDialog(id) { exportFor = null } }

    deleteBook?.let { id ->
        val n = highlights.orEmpty().count { it.bookId == id }
        AlertDialog(
            onDismissRequest = { deleteBook = null },
            text = { Text("「${titleOf(id)}」의 하이라이트 ${n}개를 모두 지울까요?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteBook = null
                    scope.launch {
                        db.highlights().deleteForBook(id)
                        // a removed book with nothing left to keep: forget it too
                        db.forgetRemovedIfEmpty(id)
                    }
                }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteBook = null }) { Text("취소") } },
        )
    }

    picked?.let { h -> HighlightActions(h, titleOf(h.bookId), canOpen = titles.containsKey(h.bookId), onOpen = { picked = null; onOpen(h) }, onDismiss = { picked = null }) }
}

/** One highlight: open (when the book is there), copy, share, the note, delete. */
@Composable
private fun HighlightActions(h: HighlightEntity, title: String, canOpen: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val db = AppDatabase.get(context.applicationContext)
    val scope = rememberCoroutineScope()
    var note by remember(h.id) { mutableStateOf(h.note.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val text = "“${h.text}”" + (h.note?.takeIf { it.isNotBlank() }?.let { "\n— $it" } ?: "") + "\n\n「$title」"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                Text(h.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("📝 메모") }, maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(MiniChipGap)) {
                    if (canOpen) MiniChip("📖 책에서 보기", onClick = onOpen)
                    MiniChip("📋 복사", onClick = { copy(context, text) })
                    MiniChip("📤 공유", onClick = {
                        runCatching {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "하이라이트 공유"))
                        }
                    })
                    MiniChip("🗑️ 지우기", onClick = { confirmDelete = true })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = note.trim().ifEmpty { null }
                if (n != h.note) scope.launch { db.highlights().upsert(h.copy(note = n)) }
                onDismiss()
            }) { Text(if (note.trim() != h.note.orEmpty()) "메모 저장" else "닫기") }
        },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        text = { Text("이 하이라이트를 지울까요?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { db.highlights().delete(h.id) }; onDismiss() }) { Text("지우기", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
    )
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("하이라이트", text))
    Toast.makeText(context, "복사했습니다.", Toast.LENGTH_SHORT).show()
}

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFFFFF176))
