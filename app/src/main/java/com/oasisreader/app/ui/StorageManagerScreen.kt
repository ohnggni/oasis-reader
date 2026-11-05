package com.oasisreader.app.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.audio.AudioDownloads
import com.oasisreader.app.audio.AudioLibraryLink
import com.oasisreader.app.data.BookQueue
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.Storage
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One 서재 entry as the storage manager sees it. */
private data class Stored(val book: BookEntity, val bytes: Long, val onPhone: Boolean, val fromServer: Boolean) {
    val audio get() = book.format == AudioLibraryLink.FORMAT
}

private enum class StTab(val label: String) { PHONE("📱 폰에 있음"), SERVER("☁️ 서버에만") }
private enum class StGroup(val label: String) { KIND("🏷️ 종류별"), COLLECTION("📚 컬렉션별"), NONE("📃 묶지 않기") }
private enum class StSort(val label: String) { SIZE("💾 용량 큰 순"), OLD("🕘 오래 안 본 순"), NAME("🔤 이름순") }

/**
 * 📦 보관함 관리 (설정 › 저장 공간): every book of the 서재 by where its file is — on the phone,
 * or on the server only — grouped and sorted, and several at once: 폰에서 지우기 (the entry stays,
 * ☁), 받기, 보관/임시로, 컬렉션에 넣기, 서재에서 지우기. Receiving in progress: 받기 관리.
 */
@Composable
fun StorageManagerScreen(onBack: () -> Unit, onDownloads: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val app = context.applicationContext
    val db = remember { AppDatabase.get(app) }
    val store = remember { BookStore.get(app) }
    val scope = rememberCoroutineScope()
    val rows by remember { db.books().observeAll() }.collectAsState(initial = null)
    val collections by remember { db.collections().observeAll() }.collectAsState(initial = emptyList())
    val links by remember { db.collections().observeLinks() }.collectAsState(initial = emptyList())
    var refresh by remember { mutableIntStateOf(0) }
    val av = AudioDownloads.version
    val bv = BookQueue.version
    val items by produceState<List<Stored>?>(null, rows, refresh, av) {
        val list = rows ?: return@produceState
        value = withContext(Dispatchers.IO) {
            list.map { b ->
                if (b.format == AudioLibraryLink.FORMAT) {
                    val bytes = AudioDownloads.keptBytes(app, b.id)
                    Stored(b, bytes, bytes > 0, true)
                } else {
                    val has = store.hasFiles(b.id)
                    Stored(b, if (has) store.filesBytes(b.id) else 0L, has, b.sourceUrl != null)
                }
            }
        }
    }
    val usage by produceState<com.oasisreader.app.data.StorageUsage?>(null, refresh, items) { value = Storage.usage(app) }
    val receiving = remember(bv, av) { BookQueue.all(app).size + AudioDownloads.bookIds(app).count { AudioDownloads.wantedTracks(app, it).isNotEmpty() } }

    var tab by rememberSaveable { mutableStateOf(StTab.PHONE.name) }
    var group by rememberSaveable { mutableStateOf(StGroup.KIND.name) }
    var sort by rememberSaveable { mutableStateOf(StSort.SIZE.name) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var closed by remember { mutableStateOf(setOf<String>()) }
    var confirmRemoveFiles by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickCollection by remember { mutableStateOf(false) }

    val t = StTab.valueOf(tab)
    val shown = remember(items, tab, sort) {
        items.orEmpty().filter { if (t == StTab.PHONE) it.onPhone else !it.onPhone }.sortedWith(
            when (StSort.valueOf(sort)) {
                StSort.SIZE -> compareByDescending<Stored> { it.bytes }.thenBy { it.book.title }
                StSort.OLD -> compareBy<Stored> { it.book.lastReadAt ?: it.book.addedAt }
                StSort.NAME -> compareBy { it.book.title }
            },
        )
    }
    val colName = remember(collections) { collections.associate { it.id to it.name } }
    val groups: List<Pair<String, List<Stored>>> = remember(shown, group, links, colName) {
        when (StGroup.valueOf(group)) {
            StGroup.NONE -> listOf("" to shown)
            StGroup.KIND -> ShelfKind.entries.mapNotNull { k ->
                shown.filter { ShelfKind.of(it.book) == k }.takeIf { it.isNotEmpty() }?.let { "${k.emoji} ${k.label}" to it }
            }
            StGroup.COLLECTION -> {
                val by = links.groupBy { it.collectionId }.mapValues { e -> e.value.map { it.bookId }.toSet() }
                val inAny = links.map { it.bookId }.toSet()
                collections.mapNotNull { c -> shown.filter { it.book.id in by[c.id].orEmpty() }.takeIf { it.isNotEmpty() }?.let { "📚 ${c.name}" to it } } +
                    listOfNotNull(shown.filter { it.book.id !in inAny }.takeIf { it.isNotEmpty() }?.let { "컬렉션 없음" to it })
            }
        }
    }
    val chosen = shown.filter { it.book.id in selected }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("📦 보관함 관리", onBack = onBack)
        usage?.let { u ->
            Text(
                "앱 전체 ${Storage.label(u.totalBytes)} · 폰 남은 공간 ${Storage.label(u.freeBytes)}\n" +
                    "📌 보관 ${Storage.label(u.keptBytes)} · 🎧 오디오북 ${Storage.label(u.audioBytes)} · ☁️ 임시 ${Storage.label(u.cacheBytes)} · 🗃️ 기타 ${Storage.label(u.otherBytes)}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(MiniChipGap)) {
            StTab.entries.forEach { e ->
                val n = items.orEmpty().count { if (e == StTab.PHONE) it.onPhone else !it.onPhone }
                MiniChip("${e.label} $n", selected = tab == e.name, onClick = { tab = e.name; selected = emptySet() })
            }
            MiniChip("⬇️ 받는 중 $receiving", onClick = onDownloads, description = "받기 관리")
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(MiniChipGap)) {
            StGroup.entries.forEach { g -> MiniChip(g.label, selected = group == g.name, onClick = { group = g.name }, plain = group != g.name) }
            StSort.entries.forEach { o -> MiniChip(o.label, selected = sort == o.name, onClick = { sort = o.name }, plain = sort != o.name) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = shown.isNotEmpty() && selected.containsAll(shown.map { it.book.id }),
                onCheckedChange = { all -> selected = if (all) shown.map { it.book.id }.toSet() else emptySet() },
            )
            Text(
                if (chosen.isEmpty()) "${shown.size}권 · ${Storage.label(shown.sumOf { it.bytes })}"
                else "${chosen.size}권 고름 · ${Storage.label(chosen.sumOf { it.bytes })}",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        HorizontalDivider(thickness = 0.5.dp)
        Box(Modifier.weight(1f)) {
            if (items == null) Text("세는 중…", modifier = Modifier.padding(24.dp))
            else if (shown.isEmpty()) Text(
                if (t == StTab.PHONE) "폰에 받아 둔 책이 없습니다." else "서버에만 있는 책이 없습니다. 서재의 책은 모두 폰에 있습니다.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (name, list) ->
                    if (name.isNotEmpty()) item(key = "g_$name") {
                        val open = name !in closed
                        val all = list.all { it.book.id in selected }
                        Row(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { closed = if (open) closed + name else closed - name }.padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = all, onCheckedChange = { on ->
                                val ids = list.map { it.book.id }.toSet()
                                selected = if (on) selected + ids else selected - ids
                            })
                            Text("$name · ${list.size}권 · ${Storage.label(list.sumOf { it.bytes })}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (open) "🔼" else "🔽")
                        }
                    }
                    if (name.isEmpty() || name !in closed) items(list, key = { "b_${name}_${it.book.id}" }) { st ->
                        StoredRow(st, st.book.id in selected) { on -> selected = if (on) selected + st.book.id else selected - st.book.id }
                    }
                }
            }
        }
        // actions on the chosen books
        if (chosen.isNotEmpty()) Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(MiniChipGap),
            ) {
                if (t == StTab.PHONE) {
                    MiniChip("☁️ 폰에서 지우기", onClick = { confirmRemoveFiles = true })
                    val books = chosen.filter { !it.audio }
                    if (books.any { !it.book.pinned }) MiniChip("📌 보관", onClick = {
                        scope.launch { books.forEach { db.books().setPinned(it.book.id, true) }; selected = emptySet(); refresh++ }
                    })
                    if (books.any { it.book.pinned && it.fromServer }) MiniChip("🕒 임시로", onClick = {
                        scope.launch { books.filter { it.fromServer }.forEach { db.books().setPinned(it.book.id, false) }; selected = emptySet(); refresh++ }
                    })
                } else {
                    MiniChip("⬇️ 받기", onClick = {
                        val n = receive(app, chosen)
                        Toast.makeText(context, "${n}권 받기를 시작했습니다", Toast.LENGTH_SHORT).show()
                        selected = emptySet()
                    })
                }
                MiniChip("📚 컬렉션에 넣기", onClick = { pickCollection = true })
                MiniChip("🗑️ 서재에서 지우기", onClick = { confirmDelete = true })
            }
        }
    }

    if (confirmRemoveFiles) {
        val local = chosen.count { !it.fromServer }
        AlertDialog(
            onDismissRequest = { confirmRemoveFiles = false },
            title = { Text("☁️ 폰에서 지우기") },
            text = {
                Text(
                    "${chosen.size - local}권의 파일(${Storage.label(chosen.filter { it.fromServer }.sumOf { it.bytes })})을 지웁니다. 서재·읽은 위치·하이라이트는 남고, 열면 서버에서 다시 받습니다." +
                        if (local > 0) "\n폰에서 직접 넣은 ${local}권은 다시 받을 곳이 없어 그대로 둡니다." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveFiles = false
                    val list = chosen
                    scope.launch {
                        list.filter { it.fromServer }.forEach { s ->
                            if (s.audio) AudioDownloads.remove(app, s.book) else store.removeFiles(s.book.id)
                        }
                        selected = emptySet(); refresh++
                    }
                }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveFiles = false }) { Text("취소") } },
        )
    }
    if (confirmDelete) {
        var keepHl by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("🗑️ 서재에서 지우기") },
            text = {
                Column {
                    Text("${chosen.size}권을 서재에서 지웁니다. 파일도 폰에서 지워집니다.")
                    Row(Modifier.fillMaxWidth().clickable { keepHl = !keepHl }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keepHl, onCheckedChange = { keepHl = it })
                        Text("🖍️ 하이라이트·메모는 남기기", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val list = chosen
                    scope.launch {
                        list.forEach { s ->
                            if (s.audio) AudioDownloads.remove(app, s.book)
                            store.delete(s.book.id, keepHl)
                        }
                        selected = emptySet(); refresh++
                    }
                }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
    if (pickCollection) {
        var creating by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pickCollection = false },
            title = { Text("📚 ${chosen.size}권을 컬렉션에 넣기") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    collections.forEach { c ->
                        Text(
                            c.name, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable {
                                pickCollection = false
                                val ids = chosen.map { it.book.id }
                                scope.launch { ids.forEach { ShelfCollections.set(db, c.id, it, true) }; selected = emptySet() }
                                Toast.makeText(context, "📚 ${c.name}에 넣었습니다", Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 10.dp),
                        )
                    }
                    TextButton(onClick = { creating = true }) { Text("➕ 새 컬렉션") }
                }
            },
            confirmButton = { TextButton(onClick = { pickCollection = false }) { Text("닫기") } },
        )
        if (creating) CollectionNameDialog("새 컬렉션", onDismiss = { creating = false }) { name ->
            creating = false; pickCollection = false
            val ids = chosen.map { it.book.id }
            scope.launch {
                val c = ShelfCollections.create(db, name)
                ids.forEach { ShelfCollections.set(db, c.id, it, true) }
                selected = emptySet()
            }
        }
    }
}

/** Receive the chosen books again: books into the book queue, audiobooks into theirs. */
private fun receive(app: Context, list: List<Stored>): Int {
    var n = 0
    val servers = ServerSettings(app)
    list.forEach { s ->
        val b = s.book
        if (s.audio) { AudioDownloads.enqueue(app, b); n++; return@forEach }
        val href = b.sourceUrl ?: return@forEach
        val server = servers.forUrl(href) ?: return@forEach
        // the same key the catalog used (OPDS entry id / meta key), so the 서재 entry is reused
        BookQueue.enqueue(app, BookQueue.Job(b.opdsId ?: href, server.id, href, b.title, meta = true))
        n++
    }
    return n
}

@Composable
private fun StoredRow(st: Stored, checked: Boolean, onCheck: (Boolean) -> Unit) {
    val context = LocalContext.current
    val store = remember { BookStore.get(context.applicationContext) }
    val fmt = remember { SimpleDateFormat("yyyy.M.d", Locale.KOREA) }
    Row(
        Modifier.fillMaxWidth().clickable { onCheck(!checked) }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheck)
        BookCover(file = if (st.book.hasCover) store.coverFile(st.book.id) else null, title = st.book.title, modifier = Modifier.width(32.dp).height(46.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(ShelfKind.of(st.book).emoji + " " + st.book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (st.onPhone) Storage.label(st.bytes) else null,
                    when {
                        st.audio && st.onPhone && !st.book.pinned -> "일부 받음"
                        st.audio -> null
                        !st.onPhone -> "☁️ 서버에만"
                        st.book.pinned -> "📌 보관"
                        else -> "☁️ 임시"
                    },
                    if (!st.fromServer) "직접 넣은 책" else null,
                    (st.book.lastReadAt)?.let { "읽은 날 " + fmt.format(Date(it)) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
