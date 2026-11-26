package com.oasisreader.app.remote

import com.oasisreader.app.ui.WifiAsk
import com.oasisreader.app.ui.EmojiText
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import com.oasisreader.app.ui.Text
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
import com.oasisreader.app.data.ShelfCollections
import com.oasisreader.app.data.ShelfSearch
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
    // 뒤로가기 (TODO 69): always the folder above, FTP/SFTP even above the address folder (up to "/").
    // [ceiling] = a folder whose parent cannot be listed; [climbedFrom] = where the last step up started.
    var ceiling by remember { mutableStateOf<String?>(null) }
    var climbedFrom by remember { mutableStateOf<String?>(null) }
    var exitAt by remember { mutableStateOf(0L) }
    val climbable = server.proto == Remote.FTP || server.proto == Remote.FTPS || server.proto == Remote.SFTP
    val selected = remember { mutableStateListOf<RemoteFile>() }
    var ask by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var gathering by remember { mutableStateOf(false) }
    // 🎧 소장 오디오북 (TODO 30): sound files here, and the folder(s) to take as audiobooks
    var audioHere by remember { mutableStateOf(0) }
    var audioAsk by remember { mutableStateOf<List<String>?>(null) }
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
            val all = conn.use { it.list(d) }
            audioHere = all.count { !it.isDir && Remote.isAudioName(it.name) }
            items = all
                .filter { it.isDir || it.isBook }
                .filterNot { it.name.startsWith(".") }
                .sortedWith(compareBy<RemoteFile> { !it.isDir }.thenComparator { a, b -> ShelfSearch.naturalCompare(a.name, b.name) })
            climbedFrom = null
        } catch (e: Exception) {
            val from = climbedFrom
            if (from != null && d == Remote.parent(from) && d != from) {
                // the folder above is closed to this user: stay where we were and call it the top
                climbedFrom = null; ceiling = from; dir = from
                Notice.show(context, "더 위로는 갈 수 없습니다")
            } else {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    val atRoot = dir == null || dir == root
    fun isTop(d: String): Boolean = d == "/" || d == ceiling || (!climbable && d == root)
    fun up() {
        val d = dir ?: return
        climbedFrom = d
        dir = Remote.parent(d)
    }
    fun back() {
        val d = dir
        when {
            selected.isNotEmpty() -> selected.clear()
            d == null -> onBack()
            !isTop(d) -> up()
            // at the very top: a second press within 2 seconds leaves (so a habit press doesn't throw us out)
            System.currentTimeMillis() - exitAt < 2000 -> onBack()
            else -> { exitAt = System.currentTimeMillis(); Notice.show(context, "한 번 더 누르면 서버 목록으로 나갑니다") }
        }
    }
    BackHandler(onBack = ::back)

    fun receive(f: RemoteFile) {
        WifiAsk.run(app) { mobile -> Downloads.startRemote(app, server, f.path, origin = BookOrigin.remote(serverId, Remote.parent(f.path)), mobile = mobile) }
    }
    fun openNow(f: RemoteFile) {
        val url = Remote.url(serverId, f.path)
        if (url in ownedSet) {
            scope.launch { db.books().findBySource(url, url)?.let { onOpenBook(it.id) } }
            return
        }
        WifiAsk.run(app) { mobile ->
            Downloads.startRemote(app, server, f.path, pinned = false, onDone = { b -> onOpenBook(b.id) },
                origin = BookOrigin.remote(serverId, Remote.parent(f.path)), mobile = mobile)
        }
    }

    /**
     * Puts the books on the 서재 (folders: their books, [deep] = sub folders too). [registerOnly]
     * (등록만, TODO 78): entries only, each received when opened — but the first book of each folder
     * is received now (바로 읽기 임시 보관) so the folder's 📚 컬렉션 has a cover.
     */
    fun keep(picked: List<RemoteFile>, deep: Boolean, byFolder: Boolean, registerOnly: Boolean = false) {
        gathering = true
        scope.launch {
            try {
                val books = ArrayList<RemoteFile>()
                picked.filter { !it.isDir }.forEach { books += it }
                picked.filter { it.isDir }.forEach { d -> books += conn.use { Remote.walk(it, d.path, deep) } }
                val cols = HashMap<String, String>()
                var n = 0
                var have = 0
                val unique = books.distinctBy { it.path }
                // 등록만: the first book of each folder (natural order) is the one received for the cover
                val coverOf = if (!registerOnly) emptySet() else unique.groupBy { Remote.parent(it.path) }.values
                    .mapNotNull { fs -> fs.minWithOrNull { a, b -> ShelfSearch.naturalCompare(a.name, b.name) }?.path }.toSet()
                val store = com.oasisreader.app.data.BookStore.get(app)
                val fetch = ArrayList<(Boolean) -> Unit>()    // what is to be received, asked about at once (103)
                unique.forEach { f ->
                    val folder = Remote.parent(f.path)
                    val col = if (byFolder) {
                        val name = Remote.nameOf(folder).ifBlank { server.name }
                        cols.getOrPut(name) { ShelfCollections.findOrCreate(db, name).id }
                    } else null
                    val url = Remote.url(serverId, f.path)
                    val already = db.books().findBySource(url, url)
                    if (already != null && !registerOnly && !store.hasFiles(already.id)) {
                        // registered earlier (등록만) or dropped: 전부 받기 now receives it (import puts the file back)
                        n++
                        fetch += { mobile -> Downloads.startRemote(app, server, f.path, collectionId = col, origin = already.origin ?: BookOrigin.remote(serverId, folder), mobile = mobile) }
                    } else if (already != null) {
                        have++
                        if (!registerOnly && !already.pinned) db.books().setPinned(already.id, true)
                        col?.let { ShelfCollections.set(db, it, already.id, true) }
                    } else {
                        n++
                        val origin = BookOrigin.remote(serverId, folder)
                        when {
                            !registerOnly -> fetch += { mobile -> Downloads.startRemote(app, server, f.path, collectionId = col, origin = origin, mobile = mobile) }
                            f.path in coverOf -> fetch += { mobile -> Downloads.startRemote(app, server, f.path, pinned = false, collectionId = col, origin = origin, mobile = mobile) }
                            else -> {
                                val b = store.registerRemote(url, Remote.nameOf(f.path).substringBeforeLast('.'), origin)
                                col?.let { ShelfCollections.set(db, it, b.id, true) }
                            }
                        }
                    }
                }
                val result = { Notice.show(
                    context,
                    (if (n == 0) (if (registerOnly) "등록할 책이 없습니다" else "받을 책이 없습니다")
                        else if (registerOnly) "${n}권을 서재에 등록했습니다 (열 때 받음)" else "${n}권을 받습니다") +
                        (if (have > 0) " · 이미 있는 ${have}권은 건너뜀" else "") +
                        (if (cols.isNotEmpty()) " · 📚 " + cols.keys.take(3).joinToString(", ") + (if (cols.size > 3) " 외 ${cols.size - 3}개" else "") + "에 묶습니다" else ""),
                ) }
                // 등록만 is done already: its message does not wait for the covers
                if (registerOnly || fetch.isEmpty()) result()
                if (fetch.isNotEmpty()) WifiAsk.run(app) { mobile ->
                    fetch.forEach { it(mobile) }
                    if (!registerOnly) result()
                }
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
            else -> "📂 " + Remote.nameOf(dir!!).ifBlank { server.name }
        }
        TopBar(title, onBack = ::back) {
            com.oasisreader.app.ui.HomeButton()
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
        val r0 = root
        val d = dir
        // above the address folder (FTP/SFTP climbed up) the trail starts at "/"
        val r = if (r0 != null && d != null && d != r0 && !(r0 == "/" || d.startsWith(r0.trimEnd('/') + "/"))) "/" else r0
        if (r != null && d != null && d != r) {
            val parts = d.removePrefix(r).split('/').filter { it.isNotEmpty() }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(server.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.combinedClickable(onClick = { dir = r }))
                var at: String = r
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
        if (selected.isEmpty() && list != null && (list.any { it.isDir || it.isBook } || audioHere > 0)) Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                listOfNotNull(
                    list.count { it.isDir }.takeIf { it > 0 }?.let { "📁 ${it}개" },
                    books.takeIf { it > 0 }?.let { "📖 ${it}권" },
                    audioHere.takeIf { it > 0 }?.let { "🎧 트랙 ${it}개" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // 🎧: this folder as an audiobook (it holds sound files) or the audiobooks under it
            if (audioHere > 0 || list.any { it.isDir }) MiniChip("🎧 오디오북", onClick = { audioAsk = listOfNotNull(d) })
            if (books == 0 && list.none { it.isDir }) {}
            else if (gathering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else MiniChip("⬇️ 이 폴더 모두 담기", onClick = {
                val here = d ?: return@MiniChip
                ask = listOf(RemoteFile(Remote.nameOf(here).ifBlank { server.name }, here, true))
            })
        }
        Box(Modifier.weight(1f)) {
            when {
                error != null -> Column(Modifier.padding(24.dp)) {
                    EmojiText("⚠️ ${error}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(12.dp))
                    Row {
                        TextButton(onClick = { reload++ }) { Text("다시 시도") }
                        TextButton(onClick = onOpenSettings) { Text("연결 설정") }
                    }
                }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(
                    if (audioHere > 0) "오디오북 폴더(트랙 ${audioHere}개) — 위 🎧로 담기"
                    else "책이 없는 폴더입니다 (epub · cbz · zip · pdf · txt)",
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
                                if (url in ownedSet && progress[url] == null) EmojiText("✅", style = MaterialTheme.typography.titleMedium)
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
                else {
                    if (selected.any { it.isDir }) MiniChip("🎧 오디오북", onClick = { audioAsk = selected.filter { it.isDir }.map { it.path } })
                    MiniChip("⬇️ 서재에 담기", onClick = { ask = selected.toList() })
                }
            }
        }
    }

    audioAsk?.let { dirs ->
        com.oasisreader.app.audio.OwnAudioChoiceDialog(
            remote = true,
            what = if (dirs.size == 1) "「${Remote.nameOf(dirs[0]).ifBlank { server.name }}」" else "고른 폴더 ${dirs.size}개",
            onPick = { keep ->
                audioAsk = null; selected.clear()
                fun add(mobile: Boolean) = dirs.forEach { com.oasisreader.app.audio.OwnAudioImport.fromRemote(context, server, it, keep, mobile) }
                if (keep) WifiAsk.run(app) { mobile -> add(mobile) } else add(false)
            },
            onDismiss = { audioAsk = null },
        )
    }

    ask?.let { picked ->
        val hasDir = picked.any { it.isDir }
        var deep by remember(picked) { mutableStateOf(false) }
        var byFolder by remember(picked) { mutableStateOf(hasDir) }
        var registerOnly by remember(picked) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { ask = null },
            // 간결성 (81): the title says what and how many; 이미 있는 책은 말없이 건너뜀
            title = { EmojiText("⬇️ 서재에 담기 · ${picked.size}개") },
            text = {
                Column {
                    // 받는 방식 (TODO 78): everything now, or entries only and each book when opened
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.combinedClickable(onClick = { registerOnly = false })) {
                        RadioButton(selected = !registerOnly, onClick = { registerOnly = false })
                        Text("전부 받기", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.combinedClickable(onClick = { registerOnly = true })) {
                        RadioButton(selected = registerOnly, onClick = { registerOnly = true })
                        Column {
                            Text("등록만 (열 때 받기)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "웹툰 · 만화처럼 회차가 많을 때",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    if (hasDir) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.combinedClickable(onClick = { deep = !deep })) {
                        Checkbox(checked = deep, onCheckedChange = { deep = it })
                        Text("하위 폴더까지", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.combinedClickable(onClick = { byFolder = !byFolder })) {
                        Checkbox(checked = byFolder, onCheckedChange = { byFolder = it })
                        EmojiText("📚 폴더별 컬렉션으로", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { ask = null; keep(picked, deep, byFolder, registerOnly) }) { Text(if (registerOnly) "등록" else "받기") } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("취소") } },
        )
    }
}

private fun sizeText(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    else -> "${(b / 1024).coerceAtLeast(1)} KB"
}
