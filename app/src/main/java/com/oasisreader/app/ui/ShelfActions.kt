package com.oasisreader.app.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oasisreader.app.audio.AudioDownloads
import com.oasisreader.app.audio.AudioLibraryLink
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookCollectionEntity
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.CollectionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What 서재 (선택 모드), 📦 보관함 관리 and 컬렉션 지우기 do to several books at once.
 * One place, so the three screens behave the same.
 */
object ShelfActions {
    /** Outlives the dialog that started the work (a dialog's own scope ends when it closes). */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)

    private fun isAudio(b: BookEntity) = b.format == AudioLibraryLink.FORMAT

    /** Has the book's file (or some audiobook tracks) on the phone? (disk; call off the main thread) */
    fun onPhone(app: Context, b: BookEntity): Boolean =
        if (isAudio(b)) AudioDownloads.keptBytes(app, b.id) > 0 else BookStore.get(app).hasFiles(b.id)

    /** 폰에서 지우기 could free it: on the phone and it can be received again. */
    fun canRemoveFiles(app: Context, b: BookEntity): Boolean =
        (isAudio(b) || b.sourceUrl != null) && onPhone(app, b)

    /** 서재에서 지우기 (files too). */
    suspend fun delete(app: Context, books: List<BookEntity>, keepHighlights: Boolean) {
        val store = BookStore.get(app)
        books.forEach { b ->
            if (isAudio(b)) AudioDownloads.remove(app, b)
            if (com.oasisreader.app.audio.OwnAudio.isOwn(b)) com.oasisreader.app.audio.OwnAudio.forget(app, b.id)
            store.delete(b.id, keepHighlights)
        }
    }

    /** ☁️ 폰에서 지우기: only books that came from a server; the 서재 entry stays. Returns how many. */
    suspend fun removeFiles(app: Context, books: List<BookEntity>): Int {
        val store = BookStore.get(app)
        var n = 0
        books.forEach { b ->
            val can = withContext(Dispatchers.IO) { canRemoveFiles(app, b) }
            if (!can) return@forEach
            if (isAudio(b)) AudioDownloads.remove(app, b) else store.removeFiles(b.id)
            n++
        }
        return n
    }

    /** 📌 보관 / 🕒 임시로 (books from a server only; audiobooks keep their own state). */
    suspend fun setPinned(app: Context, books: List<BookEntity>, pinned: Boolean) {
        val db = AppDatabase.get(app)
        books.filter { !isAudio(it) && (pinned || it.sourceUrl != null) }.forEach { db.books().setPinned(it.id, pinned) }
    }

    /** ✔️ 다 읽음 / 안 읽음. */
    suspend fun setFinished(app: Context, books: List<BookEntity>, finished: Boolean) {
        val db = AppDatabase.get(app)
        val now = System.currentTimeMillis()
        books.forEach { db.books().setFinished(it.id, if (finished) now else null) }
    }

    suspend fun addToCollection(app: Context, collectionId: String, ids: Collection<String>) {
        val db = AppDatabase.get(app)
        ids.forEach { ShelfCollections.set(db, collectionId, it, true) }
    }

    suspend fun highlightCount(app: Context, ids: Collection<String>): Int {
        val h = AppDatabase.get(app).highlights()
        return ids.sumOf { h.countFor(it) }
    }
}

/** 🗑️ 서재에서 지우기 for several books, with "🖍️ 하이라이트·메모는 남기기" (on by default). */
@Composable
fun BulkDeleteDialog(books: List<BookEntity>, onDismiss: () -> Unit, onConfirm: (keepHighlights: Boolean) -> Unit) {
    val app = LocalContext.current.applicationContext
    val ids = remember(books) { books.map { it.id } }
    val hl by produceState(0, ids) { value = ShelfActions.highlightCount(app, ids) }
    var keepHl by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { EmojiText("🗑️ 서재에서 지우기") },
        text = {
            Column {
                Text(
                    (if (books.size == 1) "「${books[0].title}」을(를)" else "${books.size}권을") +
                        " 서재에서 지웁니다. 파일도 폰에서 지워집니다. 독서 시간 통계는 남습니다.",
                )
                if (hl > 0) Row(Modifier.fillMaxWidth().clickable { keepHl = !keepHl }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keepHl, onCheckedChange = { keepHl = it })
                    EmojiText("🖍️ 하이라이트·메모 ${hl}개는 남기기", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(hl > 0 && keepHl) }) { Text("지우기", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 📚 N권을 컬렉션에 넣기: pick one (or make a new one); [onPicked] gets the collection id. */
@Composable
fun BulkCollectionDialog(count: Int, collections: List<CollectionEntity>, onDismiss: () -> Unit, onPicked: (CollectionEntity) -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { EmojiText("📚 ${count}권을 컬렉션에 넣기") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (collections.isEmpty()) Text("아직 컬렉션이 없습니다. 아래에서 만드세요.", style = MaterialTheme.typography.bodyMedium)
                collections.forEach { c ->
                    Text(
                        c.name, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPicked(c) }.padding(vertical = 10.dp),
                    )
                }
                TextButton(onClick = { creating = true }) { EmojiText("➕ 새 컬렉션") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
    if (creating) CollectionNameDialog("새 컬렉션", onDismiss = { creating = false }) { name ->
        creating = false
        scope.launch { onPicked(ShelfCollections.create(AppDatabase.get(app), name)) }
    }
}

/**
 * 컬렉션 지우기: ◉ 컬렉션만 (books stay) / ○ 안의 책 N권도 서재에서 지우기 (+ 하이라이트 남기기).
 * Books also in another 컬렉션 go too (they leave the 서재), with a line saying how many.
 */
@Composable
fun CollectionDeleteDialog(
    collection: CollectionEntity,
    links: List<BookCollectionEntity>,
    books: List<BookEntity>,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val inside = remember(collection.id, links, books) {
        val ids = links.filter { it.collectionId == collection.id }.map { it.bookId }.toSet()
        books.filter { it.id in ids }
    }
    val elsewhere = remember(inside, links) {
        val ids = inside.map { it.id }.toSet()
        links.filter { it.bookId in ids && it.collectionId != collection.id }.map { it.bookId }.toSet().size
    }
    var withBooks by remember { mutableStateOf(false) }
    var keepHl by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${collection.name}」 컬렉션 지우기") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable { withBooks = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !withBooks, onClick = { withBooks = false })
                    Text("컬렉션만 지우기 (책은 서재에 남음)", style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = inside.isNotEmpty()) { withBooks = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = withBooks, onClick = { withBooks = true }, enabled = inside.isNotEmpty())
                    Text("안의 책 ${inside.size}권도 서재에서 지우기", style = MaterialTheme.typography.bodyMedium)
                }
                if (withBooks) {
                    Row(Modifier.fillMaxWidth().clickable { keepHl = !keepHl }.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keepHl, onCheckedChange = { keepHl = it })
                        EmojiText("🖍️ 하이라이트·메모는 남기기", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (elsewhere > 0) Text(
                        "이 중 ${elsewhere}권은 다른 컬렉션에도 있습니다. 서재에서 지우면 거기서도 빠집니다.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                val list = inside
                val db = AppDatabase.get(app)
                ShelfActions.scope.launch {
                    if (withBooks) ShelfActions.delete(app, list, keepHl)
                    ShelfCollections.delete(db, collection.id)
                }
            }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
