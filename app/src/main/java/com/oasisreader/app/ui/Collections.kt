package com.oasisreader.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookCollectionEntity
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.CollectionEntity
import kotlinx.coroutines.launch

/** 컬렉션 helpers on the database (ids like the books': time + random, base 36). */
object ShelfCollections {
    fun newId(): String = "c" + java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e6).toLong(), 36)

    suspend fun create(db: AppDatabase, name: String): CollectionEntity {
        val c = CollectionEntity(newId(), name.trim(), System.currentTimeMillis(), db.collections().maxSort() + 1)
        db.collections().insert(c)
        return c
    }

    /** The 컬렉션 called [name] (same name, ignoring case and spaces at the ends), made when missing. */
    suspend fun findOrCreate(db: AppDatabase, name: String): CollectionEntity {
        val n = name.trim()
        return db.collections().all().firstOrNull { it.name.trim().equals(n, ignoreCase = true) }
            ?: create(db, n)
    }

    suspend fun delete(db: AppDatabase, id: String) {
        db.collections().unlinkCollection(id)
        db.collections().deleteCollection(id)
    }

    suspend fun set(db: AppDatabase, collectionId: String, bookId: String, on: Boolean) {
        if (on) db.collections().link(BookCollectionEntity(collectionId, bookId, System.currentTimeMillis()))
        else db.collections().unlink(collectionId, bookId)
    }
}

/**
 * The 📚 chip of the 서재 filter row: 전체 or one 컬렉션 (with how many books each holds),
 * then "새 컬렉션" and "컬렉션 편집".
 */
@Composable
fun CollectionChip(
    collections: List<CollectionEntity>,
    counts: Map<String, Int>,
    selected: String?,
    total: Int,
    onSelect: (String?) -> Unit,
    onNew: () -> Unit,
    onEdit: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = collections.firstOrNull { it.id == selected }
    Box {
        MiniChip("📚 " + (current?.name ?: "컬렉션"), selected = current != null, onClick = { open = true }, description = "컬렉션")
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("서재 전체 $total") }, leadingIcon = { CheckMark(current == null) }, onClick = { open = false; onSelect(null) })
            collections.forEach { c ->
                DropdownMenuItem(
                    text = { Text("${c.name} ${counts[c.id] ?: 0}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { CheckMark(c.id == selected) },
                    onClick = { open = false; onSelect(c.id) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("➕ 새 컬렉션…", color = MaterialTheme.colorScheme.primary) }, onClick = { open = false; onNew() })
            if (collections.isNotEmpty()) DropdownMenuItem(text = { Text("✏️ 컬렉션 편집…") }, onClick = { open = false; onEdit() })
        }
    }
}

/** A name for a new (or renamed) 컬렉션. */
@Composable
fun CollectionNameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                placeholder = { Text("예: 출퇴근길, 아이들과 함께") },
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = name.isNotBlank()) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** Which 컬렉션 a book is on (check boxes), and a new one right here. */
@Composable
fun CollectionPickDialog(book: BookEntity, collections: List<CollectionEntity>, links: List<BookCollectionEntity>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val on = links.filter { it.bookId == book.id }.map { it.collectionId }.toSet()
    var creating by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📚 컬렉션에 넣기") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(book.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (collections.isEmpty()) Text("아직 컬렉션이 없습니다. 아래에서 만드세요.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                collections.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { scope.launch { ShelfCollections.set(db, c.id, book.id, c.id !in on) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = c.id in on, onCheckedChange = { v -> scope.launch { ShelfCollections.set(db, c.id, book.id, v) } })
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                TextButton(onClick = { creating = true }) { Text("➕ 새 컬렉션 만들어 넣기") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("완료") } },
    )
    if (creating) CollectionNameDialog("새 컬렉션", onDismiss = { creating = false }) { name ->
        creating = false
        scope.launch { val c = ShelfCollections.create(db, name); ShelfCollections.set(db, c.id, book.id, true) }
    }
}

/** Rename or delete 컬렉션 (deleting asks whether the books inside leave the 서재 too). */
@Composable
fun CollectionEditDialog(
    collections: List<CollectionEntity>,
    counts: Map<String, Int>,
    links: List<BookCollectionEntity> = emptyList(),
    books: List<BookEntity> = emptyList(),
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<CollectionEntity?>(null) }
    var deleting by remember { mutableStateOf<CollectionEntity?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("✏️ 컬렉션 편집") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                collections.forEach { c ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.name} · ${counts[c.id] ?: 0}권", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { renaming = c }) { Text("이름") }
                        TextButton(onClick = { deleting = c }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("완료") } },
    )
    renaming?.let { c ->
        CollectionNameDialog("컬렉션 이름 바꾸기", c.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            scope.launch { db.collections().rename(c.id, name) }
        }
    }
    deleting?.let { c -> CollectionDeleteDialog(c, links, books) { deleting = null } }
}
