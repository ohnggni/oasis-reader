package com.oasisreader.app

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
        setContent {
            OasisTheme {
                // Surface supplies onBackground as the default text colour (dark mode)
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) { App() }
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
    var audioReturn by rememberSaveable { mutableStateOf(Route.LIBRARY) }   // where the full player goes back to
    var importing by remember { mutableStateOf(false) }

    val books by remember { db.books().observeAll() }.collectAsState(initial = null)

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
            for (u in uris) {
                try {
                    store.importUri(u); ok++
                } catch (e: Exception) {
                    Toast.makeText(context, "가져오기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            importing = false
            if (ok > 0) Toast.makeText(context, "${ok}권을 서재에 담았습니다", Toast.LENGTH_SHORT).show()
        }
    }

    fun openServer(from: String, id: String?) { serverReturn = from; route = Route.SERVER + (id ?: "") }

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
            if (isAudio) key(sid) {
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
        route == Route.DOWNLOADS -> com.oasisreader.app.audio.DownloadsScreen(onBack = { route = Route.LIBRARY })
        route == Route.HIGHLIGHTS -> HighlightsScreen(
            onBack = { route = Route.LIBRARY },
            onOpen = { h -> route = Route.READER + h.bookId + ":" + h.spineIndex + ":" + h.id },
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
                            .onFailure { Toast.makeText(context, "열 수 없습니다: ${it.message}", Toast.LENGTH_LONG).show() }
                    } else route = Route.READER + id
                }
            })
        }
        route.startsWith(Route.READER) -> {
            // reader:<bookId>[:<spineIndex>:<highlightId>]
            val parts = route.removePrefix(Route.READER).split(':')
            val focus = if (parts.size >= 3) parts[1].toIntOrNull()?.let { it to parts[2] } else null
            val format by produceState<String?>(null, parts[0]) { value = db.books().get(parts[0])?.format ?: BookStore.FORMAT_EPUB }
            when (format) {
                null -> Box(Modifier.fillMaxSize())
                BookStore.FORMAT_COMIC, BookStore.FORMAT_PDF -> ComicScreen(bookId = parts[0], onBack = { route = Route.LIBRARY })
                else -> ReaderScreen(bookId = parts[0], focusHighlight = focus, onBack = { route = Route.LIBRARY })
            }
        }
        else -> Box(Modifier.fillMaxSize()) { LibraryScreen(
            books = books.orEmpty(),
            loaded = books != null,
            importing = importing,
            store = store,
            onOpen = { b: BookEntity ->
                if (b.format == com.oasisreader.app.audio.AudioLibraryLink.FORMAT) {
                    // an audiobook: its tracks and place from the server, then the player
                    // playing it right now: straight to the player; paused or not loaded: ask the
                    // server for its place first (it may have been listened to elsewhere)
                    val already = com.oasisreader.app.audio.AudioPlayback.playing &&
                        com.oasisreader.app.audio.AudioPlayback.book?.let { it.serverId to it.id } ==
                        com.oasisreader.app.audio.AudioLibraryLink.parse(b)
                    audioReturn = Route.LIBRARY
                    if (already) route = Route.AUDIO_PLAYER
                    else scope.launch {
                        Toast.makeText(context, "오디오북을 여는 중…", Toast.LENGTH_SHORT).show()
                        runCatching { com.oasisreader.app.audio.AudioLibraryLink.play(context, b) }
                            .onSuccess { route = Route.AUDIO_PLAYER }
                            .onFailure { Toast.makeText(context, "열 수 없습니다: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                } else route = Route.READER + b.id
            },
            onCatalog = { route = Route.CATALOG },
            onImportFile = {
                picker.launch(arrayOf(
                    "application/epub+zip", "application/zip", "application/x-zip-compressed",
                    "application/vnd.comicbook+zip", "application/x-cbz", "text/plain", "application/pdf", "application/octet-stream",
                ))
            },
            onSettings = { route = Route.SETTINGS },
            onStats = { route = Route.STATS },
            onHighlights = { route = Route.HIGHLIGHTS },
            onDownloads = { route = Route.DOWNLOADS },
            onDelete = { b -> scope.launch { store.delete(b.id) } },
            onRename = { b, t -> scope.launch { db.books().rename(b.id, t) } },
            onPin = { b, pin -> scope.launch { db.books().setPinned(b.id, pin) } },
        )
            // an audiobook is loaded: a small player at the bottom of the library
            com.oasisreader.app.audio.AudioMiniBar(
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
