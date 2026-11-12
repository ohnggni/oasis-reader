package com.oasisreader.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oasisreader.app.data.HighlightExport
import com.oasisreader.app.data.ReviewText
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.HighlightEntity
import com.oasisreader.app.data.db.ReviewEntity
import com.oasisreader.app.data.db.forgetRemovedIfEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The most a shared text or a text file may be to go into a 독후감. */
private const val MAX_TEXT_BYTES = 1024 * 1024

/**
 * 독후감 (TODO 43): text that another app shared to OG Reader waits here until the reader has
 * picked the book it is about ([pending] → 책 고르기 → [handOver] → the editor [take]s it).
 */
object ReviewInbox {
    var pending by mutableStateOf<String?>(null)
    private var forEditor: String? = null
    /** the text came as a file: it can go into the 서재 as a TXT book instead */
    var fileUri by mutableStateOf<Uri?>(null)

    fun handOver() { forEditor = pending; pending = null; fileUri = null }

    fun take(): String? = forEditor.also { forEditor = null }

    /** An ACTION_SEND intent with text (or a text file): keep it for a 독후감. */
    fun offer(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) { fileUri = null; pending = text.trim(); return }
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        val app = context.applicationContext
        ShelfActions.scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { readText(app, uri) }.getOrNull() }
            if (!read.isNullOrBlank()) { fileUri = uri; pending = read.trim() } else Notice.show(app, "공유받은 글을 읽지 못했습니다.")
        }
    }
}

private fun readText(context: Context, uri: Uri): String? =
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(MAX_TEXT_BYTES + 1)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        if (n > MAX_TEXT_BYTES) null else ReviewText.decode(buf.copyOf(n))
    }

private fun dayFormat() = SimpleDateFormat("yyyy.M.d", Locale.KOREA)

/**
 * One book's 독후감: stars and the text. Saved by itself (a few seconds after typing stops, and on
 * leaving); an empty one with no stars is not kept. The book may be gone from the 서재.
 */
@Composable
fun ReviewEditorScreen(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val db = remember { AppDatabase.get(app) }
    var loaded by remember(bookId) { mutableStateOf(false) }
    var title by remember(bookId) { mutableStateOf("") }
    var author by remember(bookId) { mutableStateOf<String?>(null) }
    var finishedAt by remember(bookId) { mutableStateOf<Long?>(null) }
    var text by rememberSaveable(bookId) { mutableStateOf("") }
    var rating by rememberSaveable(bookId) { mutableIntStateOf(0) }
    var createdAt by remember(bookId) { mutableLongStateOf(0L) }
    var savedText by remember(bookId) { mutableStateOf("") }
    var savedRating by remember(bookId) { mutableIntStateOf(0) }
    var incoming by remember(bookId) { mutableStateOf<String?>(null) }   // shared / file text waiting for "이어 붙이기 / 바꾸기"
    var pickHighlights by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val fmt = remember { dayFormat() }

    fun take(newText: String) {
        if (text.isBlank()) text = newText else incoming = newText
    }

    LaunchedEffect(bookId) {
        val b = db.books().get(bookId)
        val gone = if (b == null) db.removedBooks().get(bookId) else null
        title = b?.title ?: gone?.title ?: "지운 책"
        author = b?.author ?: gone?.author
        finishedAt = b?.finishedAt
        val r = db.reviews().get(bookId)
        text = r?.text.orEmpty(); rating = r?.rating ?: 0
        savedText = text; savedRating = rating
        createdAt = r?.createdAt ?: 0L
        loaded = true
        ReviewInbox.take()?.let { take(it) }
    }

    // The app's own scope: the save must finish though this screen is already gone.
    fun save() {
        if (!loaded) return
        val t = text
        val r = rating
        if (t == savedText && r == savedRating) return
        savedText = t; savedRating = r
        val now = System.currentTimeMillis()
        if (createdAt == 0L) createdAt = now
        val created = createdAt
        ShelfActions.scope.launch {
            if (t.isBlank() && r == 0) { db.reviews().delete(bookId); db.forgetRemovedIfEmpty(bookId) }
            else db.reviews().upsert(ReviewEntity(bookId = bookId, text = t, rating = r, createdAt = created, updatedAt = now))
        }
    }
    LaunchedEffect(text, rating) { delay(3_000); save() }
    DisposableEffect(bookId) { onDispose { save() } }
    val leave = { save(); onBack() }
    BackHandler(onBack = leave)

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ShelfActions.scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { readText(app, uri) }.getOrNull() }
            if (read.isNullOrBlank()) Notice.show(app, "글을 읽지 못했습니다. 글 파일(.txt · .md, 1MB까지)만 넣을 수 있습니다.") else take(read.trim())
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            val t = text
            val r = rating
            val done = finishedAt?.let { fmt.format(Date(it)) }
            ShelfActions.scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val hl = db.highlights().allFor(bookId).map { it.text to it.note }
                        app.contentResolver.openOutputStream(uri)?.use { it.write(ReviewText.markdown(title, author, r, done, t, hl).toByteArray(Charsets.UTF_8)) } != null
                    }.getOrDefault(false)
                }
                Notice.show(app, if (ok) "저장했습니다." else "저장하지 못했습니다.")
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding()) {
        TopBar("독후감", onBack = leave) {
            EmojiButton("📤", "공유") {
                runCatching {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, ReviewText.shareText(title, rating, text)) }, "독후감 공유"))
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "독후감 메뉴") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("🖍️ 하이라이트 넣기") }, onClick = { menu = false; pickHighlights = true })
                    DropdownMenuItem(text = { Text("📄 글 파일에서 가져오기") }, onClick = { menu = false; runCatching { openFile.launch(arrayOf("text/*")) } })
                    DropdownMenuItem(text = { Text("📝 마크다운 파일로 저장 (하이라이트 포함)") }, onClick = {
                        menu = false
                        runCatching { saveFile.launch(HighlightExport.fileName(title, "md")) }
                    })
                    DropdownMenuItem(text = { Text("🗑️ 독후감 지우기", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(author?.takeIf { it.isNotBlank() }, finishedAt?.let { "다 읽음 " + fmt.format(Date(it)) }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 1..5) {
                    Text(
                        if (i <= rating) "★" else "☆", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClickLabel = "별 ${i}개") { rating = if (rating == i) 0 else i }
                            .padding(horizontal = 3.dp, vertical = 2.dp),
                    )
                }
                if (rating > 0) Text("  ${rating}점", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            placeholder = { Text("이 책을 읽고 든 생각을 적어 보세요.\n다른 앱에서 쓴 글은 공유 › OG Reader로 넣을 수 있습니다.") },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp).semantics { contentDescription = "독후감 글" },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MiniChipGap)) {
            MiniChip("🖍️ 하이라이트 넣기", onClick = { pickHighlights = true })
            MiniChip("📄 파일", onClick = { runCatching { openFile.launch(arrayOf("text/*")) } })
            Text("${text.length}자 · 자동 저장", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1)
        }
    }

    incoming?.let { add ->
        AlertDialog(
            onDismissRequest = { incoming = null },
            title = { Text("받은 글을 어떻게 넣을까요?", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    Text(add, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { text = ReviewText.append(text, add); incoming = null }) { Text("➕ 지금 글 뒤에 이어 붙이기") }
                    TextButton(onClick = { text = add; incoming = null }) { Text("♻️ 지금 글을 지우고 이 글로 바꾸기") }
                }
            },
            confirmButton = { TextButton(onClick = { incoming = null }) { Text("취소") } },
        )
    }

    if (pickHighlights) HighlightPickDialog(bookId, onDismiss = { pickHighlights = false }) { picked ->
        pickHighlights = false
        if (picked.isNotEmpty()) text = ReviewText.append(text, ReviewText.quotes(picked.map { it.text to it.note }))
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        text = { Text("「$title」의 독후감을 지울까요? 되돌릴 수 없습니다.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                text = ""; rating = 0
                leave()
            }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
    )
}

/** The book's highlights with check boxes: the checked ones go into the 독후감 as quotes. */
@Composable
private fun HighlightPickDialog(bookId: String, onDismiss: () -> Unit, onPick: (List<HighlightEntity>) -> Unit) {
    val db = AppDatabase.get(LocalContext.current.applicationContext)
    var list by remember { mutableStateOf<List<HighlightEntity>?>(null) }
    var on by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(bookId) { list = db.highlights().allFor(bookId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("넣을 하이라이트", style = MaterialTheme.typography.titleSmall) },
        text = {
            val all = list
            when {
                all == null -> Text("불러오는 중…")
                all.isEmpty() -> Text("이 책에는 하이라이트가 없습니다.\n책에서 글을 길게 눌러 고르고 색을 누르면 생깁니다.")
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(all, key = { it.id }) { h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { on = if (h.id in on) on - h.id else on + h.id }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = h.id in on, onCheckedChange = { v -> on = if (v) on + h.id else on - h.id })
                            Column(Modifier.weight(1f)) {
                                Text(h.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                h.note?.takeIf { it.isNotBlank() }?.let { Text("📝 $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = on.isNotEmpty(), onClick = { onPick(list.orEmpty().filter { it.id in on }) }) { Text(if (on.isEmpty()) "넣기" else "${on.size}개 넣기") }
        },
        dismissButton = {
            Row {
                val all = list.orEmpty()
                if (all.size > 1) TextButton(onClick = { on = if (on.size == all.size) emptySet() else all.map { it.id }.toSet() }) { Text(if (on.size == all.size) "모두 해제" else "모두 고르기") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

/** All 독후감, the newest first. Books gone from the 서재 are marked 🗑️; theirs can still be read and edited. */
@Composable
fun ReviewsScreen(onBack: () -> Unit, onOpen: (bookId: String) -> Unit) {
    BackHandler(onBack = onBack)
    val db = AppDatabase.get(LocalContext.current.applicationContext)
    val reviews by remember { db.reviews().observeAll() }.collectAsState(initial = null)
    val books by remember { db.books().observeAll() }.collectAsState(initial = emptyList())
    val removed by remember { db.removedBooks().observeAll() }.collectAsState(initial = emptyList())
    val titles = remember(books) { books.associate { it.id to it.title } }
    val removedTitles = remember(removed) { removed.associate { it.id to it.title } }
    var query by rememberSaveable { mutableStateOf("") }
    val fmt = remember { dayFormat() }
    fun titleOf(id: String) = titles[id] ?: removedTitles[id] ?: "지운 책"
    val shown = remember(reviews, query, titles, removedTitles) {
        val q = query.trim()
        reviews.orEmpty().filter { r -> q.isEmpty() || r.text.contains(q, true) || ShelfSearch.matches(titleOf(r.bookId), q) }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("독후감", onBack = onBack)
        if (reviews != null && reviews!!.isEmpty()) {
            Text(
                "아직 독후감이 없습니다.\n\n" +
                    "• 서재에서 책의 ⋮ › 📝 독후감으로 씁니다.\n" +
                    "• 다른 앱(메모, 메신저 등)에서 쓴 글은 공유 › OG Reader를 누르고 책을 고르면 들어옵니다.\n" +
                    "• 책을 서재에서 지워도 독후감은 남습니다.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("🔍 독후감 · 책 제목 찾기") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        CountLine(reviews?.let { "📝 ${shown.size}편" })
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(shown, key = { it.bookId }) { r ->
                val onShelf = titles.containsKey(r.bookId)
                Column(Modifier.fillMaxWidth().clickable { onOpen(r.bookId) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        (if (onShelf) "📖 " else "🗑️ ") + titleOf(r.bookId),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (onShelf) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        listOfNotNull(ReviewText.stars(r.rating).takeIf { r.rating > 0 }, fmt.format(Date(r.updatedAt)), "${r.text.length}자").joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (r.text.isNotBlank()) Text(r.text.trim(), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

/** Text arrived from another app: which book is it about? (서재 order: the book read last comes first.) */
@Composable
fun ReviewPickBookScreen(books: List<BookEntity>, onPick: (bookId: String) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val incoming = ReviewInbox.pending
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(books, query) { books.filter { ShelfSearch.matches(it.title, query) || ShelfSearch.matches(it.author, query) } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("어느 책의 독후감인가요?", onBack = onCancel)
        if (incoming != null) Text(
            "받은 글: " + incoming.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ReviewInbox.fileUri?.let { file ->
            val ctx = LocalContext.current
            TextButton(onClick = { ReviewInbox.fileUri = null; ShareImport.run(ctx, listOf(file)); onCancel() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("📖 독후감이 아니라 책입니다 — 이 글 파일을 서재에 담기")
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("🔍 책 제목 · 저자 (초성도 됩니다)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (books.isEmpty()) Text("서재에 책이 없습니다. 책을 먼저 서재에 담아 주세요.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(shown, key = { it.id }) { b ->
                Column(Modifier.fillMaxWidth().clickable { onPick(b.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(b.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    b.author?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}
