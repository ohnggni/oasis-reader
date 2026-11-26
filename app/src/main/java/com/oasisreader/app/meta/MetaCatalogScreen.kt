package com.oasisreader.app.meta

import com.oasisreader.app.data.BookStore
import android.content.Context
import com.oasisreader.app.data.MetaLibrary
import com.oasisreader.app.data.MetaSeries
import com.oasisreader.app.data.MetaBook
import com.oasisreader.app.data.MetaApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.Downloads
import com.oasisreader.app.data.Server
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.ui.EmojiButton
import com.oasisreader.app.ui.TopBar
import kotlinx.coroutines.launch

/**
 * The bookoasis catalog as it should be: libraries → series (newest first by real file date)
 * → volumes with clean titles. Data from oasis-meta, files from bookoasis.
 */
/** 📂 있던 폴더 열기: the series the meta catalog of that server should open with, once. */
object MetaCatalogNav {
    val pending = HashMap<String, MetaSeries>()
}

@Composable
fun MetaCatalogScreen(
    serverId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpdsFolders: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val server = remember(serverId) { ServerSettings(app).get(serverId) ?: Server("", "OPDS", "", "", "") }
    val api = remember(server) { MetaApi(server) }
    val opds = remember(server) { OpdsClient(server) }
    val prefs = remember { app.getSharedPreferences("meta_catalog", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(app) }

    var libraries by remember { mutableStateOf<List<MetaLibrary>?>(null) }
    val libNames: Map<Int, String> = libraries.orEmpty().associate { it.id to it.name }
    var libError by remember { mutableStateOf<String?>(null) }
    // chosen categories (none = 전체); older versions kept a single one under library_<server>
    val libIdsState = remember {
        mutableStateOf(
            prefs.getStringSet("libraries_$serverId", null)?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: setOfNotNull(prefs.getInt("library_$serverId", -1).takeIf { it >= 0 }),
        )
    }
    var libIds by libIdsState
    val libParam = libIds.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")
    // file formats (none = 전체), combined with the categories (both must match)
    val fmtKeysState = remember {
        mutableStateOf(prefs.getStringSet("formats_$serverId", null)?.filter { k -> CatalogFormats.ALL.any { it.key == k } }?.toSet() ?: emptySet())
    }
    var fmtKeys by fmtKeysState
    val fmtParam = CatalogFormats.param(fmtKeys)
    val presetsState = remember { mutableStateOf(CatalogPresets.load(app, serverId)) }
    val savingPresetState = remember { mutableStateOf(false) }
    // older versions kept RECENT / OLDEST (date, newest or oldest first)
    val storedSort = prefs.getString("sort", null)
    val sortState = remember {
        mutableStateOf(
            when (storedSort) { "RECENT", "OLDEST" -> SeriesSort.DATE; else -> runCatching { SeriesSort.valueOf(storedSort ?: "") }.getOrDefault(SeriesSort.DATE) },
        )
    }
    var sort by sortState
    val sortDescState = remember {
        mutableStateOf(if (prefs.contains("sort_desc")) prefs.getBoolean("sort_desc", true) else storedSort != "OLDEST" && sort.defaultDesc)
    }
    var sortDesc by sortDescState
    var query by remember { mutableStateOf("") }
    val activeQueryState = remember { mutableStateOf("") }
    var activeQuery by activeQueryState
    val searchBooksState = remember { mutableStateOf(false) }   // search volumes instead of series
    val searchBooks by searchBooksState
    // series view: tiles (size adjustable) or a list, remembered per phone
    val seriesListState = remember { mutableStateOf(prefs.getBoolean("series_list", false)) }
    val seriesList by seriesListState
    val seriesTileState = remember { mutableIntStateOf(prefs.getInt("series_tile", SERIES_TILE_DEFAULT).let { v -> SERIES_TILE_STEPS.minBy { kotlin.math.abs(it - v) } }) }
    val seriesTile by seriesTileState
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var openSeries by remember { mutableStateOf<MetaSeries?>(MetaCatalogNav.pending.remove(serverId)) }   // 📂 있던 폴더 열기 opens a series
    val openBookState = remember { mutableStateOf<MetaBook?>(null) }   // detail sheet over the list
    var openBook by openBookState
    var reload by remember { mutableIntStateOf(0) }

    val seriesItems = remember { mutableStateListOf<MetaSeries>() }
    val bookItems = remember { mutableStateListOf<MetaBook>() }
    val pageState = remember { mutableIntStateOf(1) }
    val page by pageState
    val hasMoreState = remember { mutableStateOf(false) }
    val hasMore by hasMoreState
    val totalState = remember { mutableStateOf<Int?>(null) }        // all that match (from oasis-meta)
    val total by totalState
    val totalBooksState = remember { mutableStateOf<Int?>(null) }
    val totalBooks by totalBooksState
    val loadingState = remember { mutableStateOf(false) }
    val loading by loadingState
    val errorState = remember { mutableStateOf<String?>(null) }
    val error by errorState

    LaunchedEffect(reload) {
        libError = null
        libraries = try { api.libraries() } catch (e: Exception) { libError = e.message; null }
    }

    val loader = MetaPageLoader(
        api, libParam, fmtParam, seriesItems, bookItems,
        libIdsState, fmtKeysState, sortState, sortDescState, activeQueryState, searchBooksState,
        pageState, hasMoreState, totalState, totalBooksState, loadingState, errorState,
    )

    LaunchedEffect(libParam, fmtParam, sort, sortDesc, activeQuery, searchBooks, reload) { loader.loadPage(1) }

    val owned by remember { db.books().observeSourceUrls() }.collectAsState(initial = emptyList())
    val ownedSet = remember(owned) { owned.toSet() }
    val pinnedList by remember { db.books().observePinnedSourceUrls() }.collectAsState(initial = emptyList())
    val pinnedSet = remember(pinnedList) { pinnedList.toSet() }
    val progress by Downloads.progress.collectAsState()
    val store = remember { com.oasisreader.app.data.BookStore.get(app) }

    val actions = MetaShelfActions(context, app, server, api, scope, db, store, ownedSet, pinnedSet, onOpenBook)
    // (series name, books, what part: null = the whole series, else "처음부터 「…」까지")
    val askSeriesState = remember { mutableStateOf<Triple<String, List<MetaBook>, String?>?>(null) }
    var askSeries by askSeriesState
    MetaAskSeriesDialog(askSeriesState, actions)

    // ── series detail ─────────────────────────────────────────────
    openSeries?.let { s -> Box(Modifier.fillMaxSize()) {
        SeriesDetail(
            series = s, format = fmtParam, api = api, opds = opds, ownedSet = ownedSet, pinnedSet = pinnedSet, progress = progress,
            onBack = { openSeries = null },
            onBook = { openBook = it },
            onAction = actions::openOrDownload,
            onDownloadAll = { list -> askSeries = Triple(s.seriesName, list, null) },
            onDownloadRange = { list, range -> askSeries = Triple(s.seriesName, list, range) },
        )
        MetaBookOverlay(openBookState, api, opds, ownedSet, pinnedSet, progress, libNames, actions)
    }
        return
    }

    Box(Modifier.fillMaxSize()) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(server.name, onBack = onBack) {
            com.oasisreader.app.ui.HomeButton()
            EmojiButton("⚙️", "서버 설정", onOpenSettings)
        }
        com.oasisreader.app.ui.CatalogViewSwitch(organized = true, onOrganized = {}, onFolders = onOpdsFolders)
        com.oasisreader.app.ui.CompactSearchField(
            value = query,
            onValueChange = { query = it; if (it.isBlank()) activeQuery = "" },
            placeholder = "시리즈·제목·저자 검색",
            onSearch = {
                activeQuery = query.trim()
                // results take the screen: put the keyboard away
                keyboard?.hide(); focus.clearFocus()
            },
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        )
        MetaFilterRow(app, serverId, prefs, libraries, libIdsState, fmtKeysState, presetsState, savingPresetState, sortState, sortDescState, activeQuery, searchBooks)
        MetaCountRow(
            prefs, loading, seriesItems.isEmpty(), bookItems.isEmpty(), seriesItems.size, bookItems.size, hasMore, total, totalBooks,
            activeQuery, searchBooksState, seriesListState, seriesTileState,
        )

        MetaResults(
            api, opds, error ?: libError, libIds, libNames, activeQuery, searchBooks, seriesItems, bookItems,
            seriesList, seriesTile, loading, hasMore, page, ownedSet, progress,
            onRetry = { reload++ }, onOpdsFolders = onOpdsFolders, onOpenSettings = onOpenSettings,
            onSeries = { openSeries = it }, onBook = { openBook = it }, onAction = actions::openOrDownload,
            onMore = { scope.launch { loader.loadPage(page + 1) } },
        )
    }
    MetaBookOverlay(openBookState, api, opds, ownedSet, pinnedSet, progress, libNames, actions)
    }
}
