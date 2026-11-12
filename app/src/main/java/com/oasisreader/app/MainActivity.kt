package com.oasisreader.app

import com.oasisreader.app.audio.rowId
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.Downloads
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.comic.ComicScreen
import com.oasisreader.app.reader.ReaderScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.produceState
import com.oasisreader.app.stats.StatsCalculator
import com.oasisreader.app.stats.StatsScreen
import com.oasisreader.app.stats.StatsUi
import com.oasisreader.app.ui.HighlightsScreen
import com.oasisreader.app.ui.LibraryScreen
import com.oasisreader.app.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import com.oasisreader.app.ui.OpdsScreen
import com.oasisreader.app.ui.PageStatusBarIcons
import com.oasisreader.app.ui.ServerScreen
import com.oasisreader.app.ui.ServerListScreen
import com.oasisreader.app.meta.MetaCatalogScreen
import com.oasisreader.app.data.ServerSettings
import androidx.compose.runtime.key
import com.oasisreader.app.ui.TopBar
import com.oasisreader.app.ui.theme.OasisTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Page-turner remotes / keyboards / volume keys reach the open book before any view does. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        com.oasisreader.app.ui.PageKeys.dispatch(this, event) || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.oasisreader.app.ui.theme.ThemeMode.load(this)
        com.oasisreader.app.data.FontManager(this)   // loads the user's own fonts
        com.oasisreader.app.ui.DiskCovers.dir = java.io.File(cacheDir, "covers")
        com.oasisreader.app.meta.MetaApi.cacheDir = java.io.File(cacheDir, "meta")
        com.oasisreader.app.audio.AudioDownloads.migrateLegacy(this)   // plain kept tracks → encrypted (once)
        com.oasisreader.app.audio.AudioProgressSync.schedule(this)      // listening places that did not go out yet
        com.oasisreader.app.data.BookQueue.resumeWaiting(this)          // books still waiting to be received
        // 독후감: text shared from another app (not again when the activity is rebuilt with the same intent)
        if (savedInstanceState == null) com.oasisreader.app.ui.ReviewInbox.offer(this, intent)
        addOnNewIntentListener { i -> com.oasisreader.app.ui.ReviewInbox.offer(this, i) }
        setContent {
            OasisTheme {
                // Surface supplies onBackground as the default text colour (dark mode)
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    App()
                    // 오디오북: the phone's place or the server's, when that cannot be told
                    com.oasisreader.app.audio.PlaceChoiceDialog()
                    // long messages (Toasts cut them to two lines)
                    com.oasisreader.app.ui.NoticeHost()
                }
            }
        }
    }
}

/** Screens are encoded as strings so they survive process death (rememberSaveable). */
private object Route {
    const val LIBRARY = "library"
    const val CATALOG = "catalog"        // pick a server (or open the only one)
    const val CATALOG_OF = "catalog:"    // catalog:<serverId>  (organized view when the server has a meta API)
    const val OPDS_OF = "opds:"          // opds:<serverId>     (raw OPDS folders)
    const val SERVERS = "servers"        // list in settings
    const val SERVER = "server:"         // server:<id> to edit, "server:" to add
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val READING_DEFAULTS = "reading_defaults"
    const val HIGHLIGHTS = "highlights"
    const val READER = "reader:"
    const val DOC = "doc:"               // doc:<asset name without .md>
    const val AUDIO = "audio"            // bookoasis audiobooks
    const val AUDIO_PLAYER = "audio_player"
    const val DOWNLOADS = "downloads"    // ⬇️ 받기 관리
    const val STORAGE = "storage"        // 📦 보관함 관리
    const val REVIEWS = "reviews"        // 📝 독후감 (all)
    const val REVIEW = "review:"         // review:<bookId>
    const val REVIEW_PICK = "review_pick" // text shared from another app: pick its book
}

@Composable
private fun App() {
    val context = LocalContext.current
    val app = context.applicationContext
    val store = remember { BookStore.get(app) }
    val db = remember { AppDatabase.get(app) }
    val scope = rememberCoroutineScope()

    var route by rememberSaveable { mutableStateOf(Route.LIBRARY) }
    var serverReturn by rememberSaveable { mutableStateOf(Route.LIBRARY) }
    var audioReturn by rememberSaveable { mutableStateOf(Route.LIBRARY) }
    var downloadsReturn by rememberSaveable { mutableStateOf(Route.LIBRARY) }
    var librarySelecting by remember { mutableStateOf(false) }   // 서재 선택 모드: its action bar takes the bottom
    var storageReturn by rememberSaveable { mutableStateOf(Route.SETTINGS) }    // 📦 보관함 관리 opens from 설정 or the 서재 ☰   // where the full player goes back to
    var importing by remember { mutableStateOf(false) }

    val books by remember { db.books().observeAll() }.collectAsState(initial = null)
    var reviewReturn by rememberSaveable { mutableStateOf(Route.LIBRARY) }      // where the 독후감 editor goes back to
    // text shared to the app: ask which book it is about
    val sharedText = com.oasisreader.app.ui.ReviewInbox.pending
    LaunchedEffect(sharedText) { if (sharedText != null) route = Route.REVIEW_PICK }
    // 🏠 서재로 (server screens) and 서재에서 보기 (the "담았습니다" card): the 서재, from any screen
    val jump = com.oasisreader.app.ui.ShelfJump.seq
    LaunchedEffect(jump) {
        if (jump != com.oasisreader.app.ui.ShelfJump.handled) { com.oasisreader.app.ui.ShelfJump.handled = jump; route = Route.LIBRARY }
    }

    LaunchedEffect(Unit) {
        Downloads.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) {
        // "[만화] 1"-style titles from earlier downloads: rename them from oasis-meta (once per start)
        runCatching { store.trimCache(com.oasisreader.app.data.CachePrefs(app).limitBytes) }
        // books added before progress sync existed: give them their KOReader document id
        runCatching { com.oasisreader.app.data.KoSync.backfillDocs(app) }
        val n = runCatching { com.oasisreader.app.meta.TitleFixer.run(app) }.getOrDefault(0)
        if (n > 0) Toast.makeText(context, "제목 ${n}개를 정리했습니다", Toast.LENGTH_SHORT).show()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            var ok = 0
            var last: String? = null
            for (u in uris) {
                try {
                    last = store.importUri(u).id; ok++
                } catch (e: Exception) {
                    com.oasisreader.app.ui.Notice.show(context, "가져오기 실패: ${e.message}")
                }
            }
            importing = false
            last?.let { com.oasisreader.app.ui.Notice.added("${ok}권을 서재에 담았습니다", it) }
        }
    }

    // 폴더에서 가져오기: pick a folder → look through it → confirm → import in the background
    var folderScanning by remember { mutableStateOf(false) }
    var folderPlan by remember { mutableStateOf<com.oasisreader.app.data.FolderImport.Plan?>(null) }
    // 🎧 소장 오디오북 (TODO 30): a phone folder → in place / copy → the audiobooks in it on the 서재
    var audioTree by remember { mutableStateOf<android.net.Uri?>(null) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> audioTree = uri }
    audioTree?.let { tree ->
        com.oasisreader.app.audio.OwnAudioChoiceDialog(
            remote = false, what = "고른 폴더",
            onPick = { copy -> audioTree = null; com.oasisreader.app.audio.OwnAudioImport.fromFolder(context, tree, copy) },
            onDismiss = { audioTree = null },
        )
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        folderScanning = true
        scope.launch {
            runCatching { com.oasisreader.app.data.FolderImport.plan(context, uri) }
                .onSuccess { folderPlan = it }
                .onFailure { com.oasisreader.app.ui.Notice.show(context, "폴더를 읽지 못했습니다: ${it.message}") }
            folderScanning = false
        }
    }
    if (folderScanning) com.oasisreader.app.ui.FolderScanningDialog()
    folderPlan?.let { plan ->
        com.oasisreader.app.ui.FolderImportDialog(plan, onDismiss = { folderPlan = null }) { group ->
            folderPlan = null
            com.oasisreader.app.data.FolderImport.start(context, plan, group)
            Toast.makeText(context, "가져오기를 시작했습니다. 알림에서 진행을 볼 수 있습니다.", Toast.LENGTH_LONG).show()
        }
    }

    fun openServer(from: String, id: String?) { serverReturn = from; route = Route.SERVER + (id ?: "") }
    /** Open a 서재 book: an audiobook goes to the player (its place from the server first), else the reader. */
    fun openShelfBook(b: BookEntity) {
            if (b.format == com.oasisreader.app.audio.AudioLibraryLink.FORMAT) {
                // an audiobook: its tracks and place from the server, then the player
                // playing it right now: straight to the player; paused or not loaded: ask the
                // server for its place first (it may have been listened to elsewhere)
                val already = com.oasisreader.app.audio.AudioPlayback.playing &&
                    com.oasisreader.app.audio.AudioPlayback.book?.rowId() == b.id
                audioReturn = Route.LIBRARY
                if (already) route = Route.AUDIO_PLAYER
                else scope.launch {
                    Toast.makeText(context, "오디오북을 여는 중…", Toast.LENGTH_SHORT).show()
                    runCatching { com.oasisreader.app.audio.AudioLibraryLink.play(context, b) }
                        .onSuccess { route = Route.AUDIO_PLAYER }
                        .onFailure { com.oasisreader.app.ui.Notice.show(context, "열 수 없습니다: ${it.message}") }
                }
            } else route = Route.READER + b.id
    }
    /** 📂 있던 폴더 열기: the OPDS folder or meta series a book was received from. */
    fun openOrigin(b: BookEntity) {
        val o = com.oasisreader.app.data.BookOrigin.decode(b.origin) ?: return
        val sid = o.server ?: return
        when (o.type) {
            com.oasisreader.app.data.BookOrigin.OPDS -> {
                com.oasisreader.app.ui.OpdsFavorites.pending[sid] = com.oasisreader.app.ui.OpdsFavorites.Place(o.urls, o.labels)
                route = Route.OPDS_OF + sid
            }
            com.oasisreader.app.data.BookOrigin.META -> {
                val series = o.series ?: return
                val lib = o.library ?: return
                com.oasisreader.app.meta.MetaCatalogNav.pending[sid] = com.oasisreader.app.meta.MetaSeries(lib, series)
                route = Route.CATALOG_OF + sid
            }
            com.oasisreader.app.data.BookOrigin.REMOTE -> {
                com.oasisreader.app.remote.RemoteNav.pending[sid] = o.folder ?: return
                route = Route.CATALOG_OF + sid
            }
        }
    }

    PageStatusBarIcons()
    when {
        route == Route.CATALOG -> {
            val servers = remember(route) { ServerSettings(app).all() }
            when (servers.size) {
                0 -> LaunchedEffect(Unit) { openServer(Route.LIBRARY, null) }
                1 -> LaunchedEffect(Unit) { route = Route.CATALOG_OF + servers[0].id }
                else -> ServerListScreen(
                    onBack = { route = Route.LIBRARY },
                    onEdit = { id -> openServer(Route.CATALOG, id) },
                    onOpen = { id -> route = Route.CATALOG_OF + id },
                )
            }
        }
        route.startsWith(Route.CATALOG_OF) || route.startsWith(Route.OPDS_OF) -> {
            val raw = route.startsWith(Route.OPDS_OF)
            val sid = route.removePrefix(if (raw) Route.OPDS_OF else Route.CATALOG_OF)
            val many = remember(sid) { ServerSettings(app).all().size > 1 }
            val hasMeta = remember(sid, route) { ServerSettings(app).get(sid)?.metaUrl?.isNotBlank() == true }
            val isAudio = remember(sid, route) { ServerSettings(app).get(sid)?.isAudiobook == true }
            val isRemote = remember(sid, route) { ServerSettings(app).get(sid)?.isRemote == true }
            if (isRemote) key(sid) {
                // a file server (WebDAV · SFTP · SMB · FTP): its folders
                com.oasisreader.app.remote.RemoteScreen(
                    serverId = sid,
                    onBack = { route = if (many) Route.CATALOG else Route.LIBRARY },
                    onOpenSettings = { openServer(route, sid) },
                    onOpenBook = { id -> route = Route.READER + id },
                )
            } else if (isAudio) key(sid) {
                // a bookoasis audiobook library: list → tracks → player
                com.oasisreader.app.audio.AudiobookScreen(
                    serverId = sid,
                    onBack = { route = if (many) Route.CATALOG else Route.LIBRARY },
                    onPlayer = { audioReturn = route; route = Route.AUDIO_PLAYER },
                    onOpenSettings = { openServer(route, sid) },
                )
            } else if (!raw && hasMeta) key(sid) {
                MetaCatalogScreen(
                    serverId = sid,
                    onBack = { route = if (many) Route.CATALOG else Route.LIBRARY },
                    onOpenSettings = { openServer(route, sid) },
                    onOpdsFolders = { route = Route.OPDS_OF + sid },
                    onOpenBook = { id -> route = Route.READER + id },
                )
            } else key(sid) {
                OpdsScreen(
                    serverId = sid,
                    onBack = { route = if (raw && hasMeta) Route.CATALOG_OF + sid else if (many) Route.CATALOG else Route.LIBRARY },
                    onOpenSettings = { openServer(route, sid) },
                    onOpenBook = { id -> route = Route.READER + id },
                    onOrganized = if (hasMeta) ({ route = Route.CATALOG_OF + sid }) else null,
                )
            }
        }
        route == Route.SERVERS -> ServerListScreen(
            onBack = { route = Route.SETTINGS },
            onEdit = { id -> openServer(Route.SERVERS, id) },
        )
        route.startsWith(Route.SERVER) -> {
            val sid = route.removePrefix(Route.SERVER).ifBlank { null }
            key(route) { ServerScreen(serverId = sid, onDone = { route = serverReturn }) }
        }
        route == Route.AUDIO_PLAYER -> com.oasisreader.app.audio.AudioPlayerScreen(onBack = { route = audioReturn })
        route == Route.SETTINGS -> SettingsScreen(
            onBack = { route = Route.LIBRARY },
            onServer = { route = Route.SERVERS },
            onDoc = { id -> route = Route.DOC + id },
            onReadingDefaults = { route = Route.READING_DEFAULTS },
            onDownloads = { downloadsReturn = Route.SETTINGS; route = Route.DOWNLOADS },
            onStorage = { storageReturn = Route.SETTINGS; route = Route.STORAGE },
        )
        route == Route.STORAGE -> com.oasisreader.app.ui.StorageManagerScreen(
            onBack = { route = storageReturn },
            onDownloads = { downloadsReturn = Route.STORAGE; route = Route.DOWNLOADS },
        )
        route == Route.READING_DEFAULTS -> com.oasisreader.app.reader.ReadingDefaultsScreen(onBack = { route = Route.SETTINGS })
        route.startsWith(Route.DOC) -> {
            val id = route.removePrefix(Route.DOC)
            com.oasisreader.app.ui.DocScreen(
                title = com.oasisreader.app.ui.docTitle(id), asset = "$id.md",
                onBack = { route = Route.SETTINGS },
                shareable = id == com.oasisreader.app.ui.DOC_META,
            )
        }
        route == Route.DOWNLOADS -> com.oasisreader.app.audio.DownloadsScreen(
            onBack = { route = downloadsReturn },
            onStorage = { storageReturn = Route.DOWNLOADS; route = Route.STORAGE },
            onOpen = { id -> books?.firstOrNull { it.id == id }?.let { openShelfBook(it) } },
        )
        route == Route.REVIEWS -> com.oasisreader.app.ui.ReviewsScreen(
            onBack = { route = Route.LIBRARY },
            onOpen = { id -> reviewReturn = Route.REVIEWS; route = Route.REVIEW + id },
        )
        route == Route.REVIEW_PICK -> com.oasisreader.app.ui.ReviewPickBookScreen(
            books = books.orEmpty(),
            onPick = { id -> com.oasisreader.app.ui.ReviewInbox.handOver(); reviewReturn = Route.LIBRARY; route = Route.REVIEW + id },
            onCancel = { com.oasisreader.app.ui.ReviewInbox.pending = null; route = Route.LIBRARY },
        )
        route.startsWith(Route.REVIEW) -> key(route) {
            com.oasisreader.app.ui.ReviewEditorScreen(bookId = route.removePrefix(Route.REVIEW), onBack = { route = reviewReturn })
        }
        route == Route.HIGHLIGHTS -> HighlightsScreen(
            onBack = { route = Route.LIBRARY },
            onOpen = { h -> route = Route.READER + h.bookId + ":" + h.spineIndex + ":" + h.id },
            // 🎧 오디오북 책갈피: open the book in the player at that place
            onPlayMark = { m ->
                scope.launch {
                    val ap = com.oasisreader.app.audio.AudioPlayback
                    val loaded = ap.book?.rowId() == m.bookId
                    val ok = loaded || (db.books().get(m.bookId)?.let { b -> runCatching { com.oasisreader.app.audio.AudioLibraryLink.play(context, b) }.isSuccess } ?: false)
                    if (ok) { ap.goTo(m.track, m.startSec); audioReturn = Route.HIGHLIGHTS; route = Route.AUDIO_PLAYER }
                    else com.oasisreader.app.ui.Notice.show(context, "이 오디오북을 열지 못했습니다.")
                }
            },
        )
        route == Route.STATS -> {
            val ui by remember {
                combine(db.books().observeAll(), db.readingTime().observeAll(), db.highlights().observeAll()) { b, t, h ->
                    StatsCalculator.compute(b, t, h)
                }.flowOn(Dispatchers.Default)
            }.collectAsState(initial = StatsUi())
            StatsScreen(ui = ui, onBack = { route = Route.LIBRARY }, onOpenBook = { id ->
                scope.launch {
                    val b = db.books().get(id)
                    if (b?.format == com.oasisreader.app.audio.AudioLibraryLink.FORMAT) {
                        // an audiobook in the stats: play it (the reader cannot open it)
                        audioReturn = Route.STATS
                        runCatching { com.oasisreader.app.audio.AudioLibraryLink.play(context, b) }
                            .onSuccess { route = Route.AUDIO_PLAYER }
                            .onFailure { com.oasisreader.app.ui.Notice.show(context, "열 수 없습니다: ${it.message}") }
                    } else route = Route.READER + id
                }
            })
        }
        route.startsWith(Route.READER) -> {
            // reader:<bookId>[:<spineIndex>:<highlightId>]
            val parts = route.removePrefix(Route.READER).split(':')
            val focus = if (parts.size >= 3) parts[1].toIntOrNull()?.let { it to parts[2] } else null
            key(parts[0]) {
                val format by produceState<String?>(null, parts[0]) { value = db.books().get(parts[0])?.format ?: BookStore.FORMAT_EPUB }
                val openBook: (String) -> Unit = { id -> route = Route.READER + id }
                when (format) {
                    null -> Box(Modifier.fillMaxSize())
                    BookStore.FORMAT_COMIC, BookStore.FORMAT_PDF -> ComicScreen(
                        bookId = parts[0], onBack = { route = Route.LIBRARY }, onOpenBook = openBook, onOpenFolder = ::openOrigin,
                    )
                    else -> ReaderScreen(
                        bookId = parts[0], focusHighlight = focus, onBack = { route = Route.LIBRARY }, onOpenBook = openBook, onOpenFolder = ::openOrigin,
                    )
                }
            }
        }
        else -> Box(Modifier.fillMaxSize()) { LibraryScreen(
            books = books.orEmpty(),
            loaded = books != null,
            importing = importing,
            store = store,
            onOpen = { b: BookEntity -> openShelfBook(b) },
            onCatalog = { route = Route.CATALOG },
            onImportFile = {
                picker.launch(arrayOf(
                    "application/epub+zip", "application/zip", "application/x-zip-compressed",
                    "application/vnd.comicbook+zip", "application/x-cbz", "text/plain", "application/pdf", "application/octet-stream",
                ))
            },
            onImportFolder = { runCatching { folderPicker.launch(null) } },
            onImportAudio = { runCatching { audioPicker.launch(null) } },
            onSettings = { route = Route.SETTINGS },
            onStats = { route = Route.STATS },
            onHighlights = { route = Route.HIGHLIGHTS },
            onReviews = { route = Route.REVIEWS },
            onReview = { b -> reviewReturn = Route.LIBRARY; route = Route.REVIEW + b.id },
            onDownloads = { downloadsReturn = Route.LIBRARY; route = Route.DOWNLOADS },
            onStorage = { storageReturn = Route.LIBRARY; route = Route.STORAGE },
            onDelete = { b, keepHl -> scope.launch {
                if (com.oasisreader.app.audio.OwnAudio.isOwn(b)) {
                    com.oasisreader.app.audio.AudioDownloads.remove(context, b); com.oasisreader.app.audio.OwnAudio.forget(context, b.id)
                }
                store.delete(b.id, keepHl)
            } },
            onRename = { b, t -> scope.launch { db.books().rename(b.id, t) } },
            onPin = { b, pin -> scope.launch { db.books().setPinned(b.id, pin) } },
            onSourceFolder = ::openOrigin,
            onSelecting = { librarySelecting = it },
        )
            // an audiobook is loaded: a small player at the bottom of the library
            if (!librarySelecting) com.oasisreader.app.audio.AudioMiniBar(
                onOpen = { audioReturn = Route.LIBRARY; route = Route.AUDIO_PLAYER },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun Placeholder(title: String, message: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(title, onBack = onBack)
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBack) { Text("돌아가기") }
        }
    }
}
