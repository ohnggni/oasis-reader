package com.oasisreader.app.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.BookOrigin
import com.oasisreader.app.data.Downloads
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.ui.DownloadControl
import com.oasisreader.app.ui.EmojiButton
import com.oasisreader.app.ui.MiniChip
import com.oasisreader.app.ui.Notice
import com.oasisreader.app.ui.ShelfCollections
import com.oasisreader.app.ui.ShelfSearch
import com.oasisreader.app.ui.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 📂 있던 폴더 열기 for a file server: the folder to start in, once. */
object RemoteNav {
    val pending = HashMap<String, String>()
}

/** One connection for the screen, opened again when it broke (a NAS drops idle sessions). */
private class Conn(private val app: android.content.Context, private val server: com.oasisreader.app.data.Server) {
    private val lock = Mutex()
    private var fs: RemoteFs? = null

    suspend fun <T> use(f: (RemoteFs) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            val first = fs ?: Remote.open(app, server).also { fs = it }
            try {
                f(first)
            } catch (e: Exception) {
                // once more on a fresh connection; a second failure is the real answer
                runCatching { first.close() }
                fs = null
                val again = Remote.open(app, server).also { fs = it }
                f(again)
            }
        }
    }

    suspend fun root(): String = use { it.root }

    fun close() { val f = fs; fs = null; if (f != null) Thread { runCatching { f.close() } }.start() }
}

/**
 * A file server's folders (WebDAV · SFTP · SMB · FTP): folders and books (epub · cbz · zip · pdf ·
 * txt). Tap a book = 바로 읽기, ⬇️ = 서재에 담기; long press to pick several (folders too: every
 * book inside, sub folders if asked), optionally into 컬렉션 named after their folders.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(serverId: String, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenBook: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val server = remember(serverId) { ServerSettings(app).get(serverId) }
    if (server == null) { LaunchedEffect(Unit) { onBack() }; return }
    val db = remember { AppDatabase.get(app) }
    val scope = rememberCoroutineScope()
    val conn = remember(serverId) { Conn(app, server) }
    DisposableEffect(conn) { onDispose { conn.close() } }

    var root by remember { mutableStateOf<String?>(null) }
    var dir by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val selected = remember { mutableStateListOf<RemoteFile>() }
    var ask by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var gathering by remember { mutableStateOf(false) }
    val progress by Downloads.progress.collectAsState()
    val owned by remember { db.books().observeOpdsIds() }.collectAsState(initial = emptyList())
    val ownedSet = remember(owned) { owned.toHashSet() }

    LaunchedEffect(serverId, reload) {
        if (root != null) return@LaunchedEffect
        error = null
        try {
            val r = conn.root()
            root = r
            dir = RemoteNav.pending.remove(serverId)?.takeIf { it.startsWith(r) } ?: r
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    LaunchedEffect(dir, reload) {
        val d = dir ?: return@LaunchedEffect
        items = null; error = null
        try {
            items = conn.use { it.list(d) }
                .filter { it.isDir || it.isBook }
                .filterNot { it.name.startsWith(".") }
                .sortedWith(compareBy<RemoteFile> { !it.isDir }.thenComparator { a, b -> ShelfSearch.naturalCompare(a.name, b.name) })
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    val atRoot = dir == null || dir == root
    fun back() {
        when {
            selected.isNotEmpty() -> selected.clear()
            atRoot -> onBack()
            else -> dir = Remote.parent(dir!!)
        }
    }
    BackHandler(onBack = ::back)

    fun receive(f: RemoteFile) {
        Downloads.startRemote(app, server, f.path, origin = BookOrigin.remote(serverId, Remote.parent(f.path)))
    }
    fun openNow(f: RemoteFile) {
        val url = Remote.url(serverId, f.path)
        if (url in ownedSet) {
            scope.launch { db.books().findBySource(url, url)?.let { onOpenBook(it.id) } }
            return
        }
        Downloads.startRemote(app, server, f.path, pinned = false, onDone = { b -> onOpenBook(b.id) },
            origin = BookOrigin.remote(serverId, Remote.parent(f.path)))
    }

    /** Puts the books on the 서재 (folders: their books, [deep] = sub folders too). */
    fun keep(picked: List<RemoteFile>, deep: Boolean, byFolder: Boolean) {
        gathering = true
        scope.launch {
            try {
                val books = ArrayList<RemoteFile>()
                picked.filter { !it.isDir }.forEach { books += it }
                picked.filter { it.isDir }.forEach { d -> books += conn.use { Remote.walk(it, d.path, deep) } }
                val cols = HashMap<String, String>()
                var n = 0
                var have = 0
                books.distinctBy { it.path }.forEach { f ->
                    val folder = Remote.parent(f.path)
                    val col = if (byFolder) {
                        val name = Remote.nameOf(folder).ifBlank { server.name }
                        cols.getOrPut(name) { ShelfCollections.findOrCreate(db, name).id }
                    } else null
                    val url = Remote.url(serverId, f.path)
                    val already = db.books().findBySource(url, url)
                    if (already != null) {
                        have++
                        if (!already.pinned) db.books().setPinned(already.id, true)
                        col?.let { ShelfCollections.set(db, it, already.id, true) }
                    } else {
                        n++
                        Downloads.startRemote(app, server, f.path, collectionId = col, origin = BookOrigin.remote(serverId, folder))
                    }
                }
                Notice.show(
                    context,
                    (if (n > 0) "${n}권을 받습니다" else "받을 책이 없습니다") +
                        (if (have > 0) " · 이미 있는 ${have}권은 건너뜀" else "") +
                        (if (cols.isNotEmpty()) " · 📚 " + cols.keys.take(3).joinToString(", ") + (if (cols.size > 3) " 외 ${cols.size - 3}개" else "") + "에 묶습니다" else ""),
                )
                selected.clear()
            } catch (e: Exception) {
                Notice.show(context, "목록을 읽지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                gathering = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val title = when {
            selected.isNotEmpty() -> "${selected.size}개 선택됨"
            atRoot -> server.name
            else -> "📂 " + Remote.nameOf(dir!!)
        }
        TopBar(title, onBack = ::back) {
            if (selected.isNotEmpty()) {
                EmojiButton("☑️", "모두 고르기") {
                    val all = items.orEmpty()
                    if (selected.size == all.size) selected.clear() else { selected.clear(); selected.addAll(all) }
                }
            } else {
                EmojiButton("🔄", "새로 고침") { reload++ }
                EmojiButton("⚙️", "연결 설정", onOpenSettings)
            }
        }
        // where am I: server › folder › folder (tap a part to go back there)
        val r = root
        val d = dir
        if (r != null && d != null && d != r) {
            val parts = d.removePrefix(r).split('/').filter { it.isNotEmpty() }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(server.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.combinedClickable(onClick = { dir = r }))
                var at = r
                parts.forEachIndexed { i, p ->
                    at = Remote.child(at, p)
                    val target = at
                    Text(" › ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        p, style = MaterialTheme.typography.labelLarge,
                        color = if (i == parts.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.combinedClickable(onClick = { dir = target }),
                    )
                }
            }
        }
        val list = items
        val books = list.orEmpty().count { it.isBook }
        if (selected.isEmpty() && list != null && list.any { it.isDir || it.isBook }) Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                listOfNotNull(
                    list.count { it.isDir }.takeIf { it > 0 }?.let { "📁 ${it}개" },
                    books.takeIf { it > 0 }?.let { "📖 ${it}권" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (gathering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else MiniChip("⬇️ 이 폴더 모두 담기", onClick = {
                val here = d ?: return@MiniChip
                ask = listOf(RemoteFile(Remote.nameOf(here).ifBlank { server.name }, here, true))
            })
        }
        Box(Modifier.weight(1f)) {
            when {
                error != null -> Column(Modifier.padding(24.dp)) {
                    Text("⚠️ ${error}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(12.dp))
                    Row {
                        TextButton(onClick = { reload++ }) { Text("다시 시도") }
                        TextButton(onClick = onOpenSettings) { Text("연결 설정") }
                    }
                }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(
                    "이 폴더에는 하위 폴더나 책(epub · cbz · zip · pdf · txt)이 없습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                    items(list, key = { it.path }) { f ->
                        val url = Remote.url(serverId, f.path)
                        val isSel = f in selected
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        when {
                                            selected.isNotEmpty() -> if (isSel) selected.remove(f) else selected.add(f)
                                            f.isDir -> dir = f.path
                                            else -> openNow(f)
                                        }
                                    },
                                    onLongClick = { if (isSel) selected.remove(f) else selected.add(f) },
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selected.isNotEmpty()) Checkbox(checked = isSel, onCheckedChange = { if (it) selected.add(f) else selected.remove(f) })
                            Text(if (f.isDir) "📁" else com.oasisreader.app.meta.formatEmoji(f.name.substringAfterLast('.', "")),
                                style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!f.isDir) {
                                    val sub = listOfNotNull(
                                        f.size.takeIf { it > 0 }?.let { sizeText(it) },
                                        f.mtime.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("yyyy.M.d", java.util.Locale.KOREA).format(java.util.Date(it)) },
                                    ).joinToString(" · ")
                                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (!f.isDir && selected.isEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                if (url in ownedSet && progress[url] == null) Text("✅", style = MaterialTheme.typography.titleMedium)
                                else DownloadControl(url, progress[url], { receive(f) })
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        // selection: what to do with the picked ones
        if (selected.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    listOfNotNull(
                        selected.count { it.isDir }.takeIf { it > 0 }?.let { "📁 ${it}개" },
                        selected.count { !it.isDir }.takeIf { it > 0 }?.let { "📖 ${it}권" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (gathering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else MiniChip("⬇️ 서재에 담기", onClick = { ask = selected.toList() })
            }
        }
    }

    ask?.let { picked ->
        val hasDir = picked.any { it.isDir }
        var deep by remember(picked) { mutableStateOf(false) }
        var byFolder by remember(picked) { mutableStateOf(hasDir) }
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("⬇️ 서재에 담기") },
            text = {
                Column {
                    Text(
                        if (hasDir) "고른 폴더 안의 책을 받습니다. 서재에 이미 있는 책은 건너뜁니다."
                        else "고른 책 ${picked.size}권을 받습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (hasDir) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.combinedClickable(onClick = { deep = !deep })) {
                        Checkbox(checked = deep, onCheckedChange = { deep = it })
                        Text("하위 폴더까지", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.combinedClickable(onClick = { byFolder = !byFolder })) {
                        Checkbox(checked = byFolder, onCheckedChange = { byFolder = it })
                        Text("폴더 이름으로 📚 컬렉션에 묶기", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { ask = null; keep(picked, deep, byFolder) }) { Text("받기") } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("취소") } },
        )
    }
}

private fun sizeText(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    else -> "${(b / 1024).coerceAtLeast(1)} KB"
}
