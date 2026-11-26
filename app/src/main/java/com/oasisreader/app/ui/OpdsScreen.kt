package com.oasisreader.app.ui

import com.oasisreader.app.data.Server
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.Downloads
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.data.OpdsEntry
import com.oasisreader.app.data.OpdsFeed

/** Browse / search the bookoasis catalog and pull books into the library. */
@Composable
fun OpdsScreen(
    serverId: String, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenBook: (String) -> Unit,
    onOrganized: (() -> Unit)? = null,   // the server has oasis-meta: offer the series view
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val server = remember(serverId) { ServerSettings(app).get(serverId) ?: com.oasisreader.app.data.Server("", "OPDS", "", "", "") }
    val client = remember(server) { OpdsClient(server) }
    val db = remember { AppDatabase.get(app) }
    val scope = rememberCoroutineScope()

    if (!server.isComplete) {
        OpdsNeedsSetup(server.name, onBack, onOpenSettings)
        return
    }

    // navigation stack of catalog pages
    // 📂 있던 폴더 열기 (from a book) starts right in that folder
    val startAt = remember(serverId) { OpdsFavorites.pending.remove(serverId)?.takeIf { it.urls.isNotEmpty() && it.urls.size == it.labels.size } }
    val stack = remember { mutableStateListOf(*(startAt?.urls ?: listOf(server.url)).toTypedArray()) }
    val labels = remember { mutableStateListOf(*(startAt?.labels ?: listOf(server.name)).toTypedArray()) }   // folder name shown for each stack level
    val path = OpdsPath(server.id, stack, labels)
    val stateState = remember { mutableStateOf<FeedState>(FeedState.Loading) }
    var state by stateState
    var attempt by remember { mutableIntStateOf(0) }
    val extra = remember { mutableStateListOf<OpdsEntry>() }   // "more" pages appended
    val nextUrlState = remember { mutableStateOf<String?>(null) }
    var nextUrl by nextUrlState
    val loadingMoreState = remember { mutableStateOf(false) }
    val loadingMore by loadingMoreState
    val failedMoreState = remember { mutableStateOf<String?>(null) }
    val paging = OpdsPaging(context, client, scope, extra, nextUrlState, loadingMoreState, failedMoreState)
    val queryState = remember { mutableStateOf("") }
    val rootSearchState = remember { mutableStateOf<OpdsFeed?>(null) }   // feed that carries the search link
    var rootSearch by rootSearchState
    val detailState = remember { mutableStateOf<OpdsEntry?>(null) }
    val sortPrefs = remember { app.getSharedPreferences("catalog", android.content.Context.MODE_PRIVATE) }
    val sortState = remember {
        mutableStateOf(
            when (val v = sortPrefs.getString("sort", null)) {
                "NEWEST", "OLDEST" -> CatalogSort.DATE      // older versions: one entry per direction
                else -> runCatching { CatalogSort.valueOf(v ?: "") }.getOrDefault(CatalogSort.SERVER)
            },
        )
    }
    var sort by sortState
    val sortDescState = remember {
        mutableStateOf(
            if (sortPrefs.contains("sort_desc")) sortPrefs.getBoolean("sort_desc", false)
            else sortPrefs.getString("sort", null) == "NEWEST",
        )
    }
    var sortDesc by sortDescState
    var loadingAll by remember { mutableStateOf<Int?>(null) }   // entries loaded so far while fetching every page
    var sortCapped by remember { mutableStateOf(false) }   // page limit hit: stop auto-fetching
    val gridState = remember { mutableStateOf(sortPrefs.getBoolean("grid", true)) }   // book covers as a grid

    val progress by Downloads.progress.collectAsState()
    val owned by remember { db.books().observeOpdsIds() }.collectAsState(initial = emptyList())
    val ownedSet = remember(owned) { owned.toSet() }

    val current = stack.last()
    LaunchedEffect(current, attempt) {
        state = FeedState.Loading
        extra.clear(); nextUrl = null; sortCapped = false
        state = try {
            val feed = client.fetch(current)
            nextUrl = feed.next
            if (feed.searchHref != null && (rootSearch == null || stack.size == 1)) rootSearch = feed
            FeedState.Ready(feed)
        } catch (e: Exception) {
            FeedState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // a client-side sort needs the whole list: fetch the remaining pages (bounded)
    LaunchedEffect(sort, sortDesc, nextUrl, state) {
        if ((sort == CatalogSort.SERVER && !sortDesc) || state !is FeedState.Ready || loadingMore || sortCapped) return@LaunchedEffect
        var next = nextUrl ?: return@LaunchedEffect
        val ready = state as FeedState.Ready
        var pages = 0
        try {
            while (pages < MAX_SORT_PAGES) {
                loadingAll = ready.feed.entries.size + extra.size
                val f = client.fetch(next)
                extra.addAll(f.entries)
                pages++
                next = f.next ?: break
            }
            if (pages >= MAX_SORT_PAGES) { sortCapped = true; nextUrl = next } else nextUrl = null
        } catch (e: Exception) {
            com.oasisreader.app.ui.Notice.show(context, "목록 전체를 받지 못했습니다: ${e.message}")
        } finally {
            loadingAll = null
        }
    }

    fun back() { if (stack.size > 1) path.popTo(stack.size - 2) else onBack() }
    BackHandler(onBack = ::back)

    // ⭐ 즐겨찾기 and the last folder seen (OpdsFavorites): the whole path is kept
    val favoritesState = remember(serverId) { mutableStateOf(OpdsFavorites.all(app, serverId)) }
    var favorites by favoritesState
    var lastPlace by remember(serverId) { mutableStateOf(OpdsFavorites.last(app, serverId)) }
    val editFavoritesState = remember { mutableStateOf(false) }
    var editFavorites by editFavoritesState
    LaunchedEffect(current) {
        if (stack.size > 1 && labels.lastOrNull()?.startsWith("검색:") != true) OpdsFavorites.setLast(app, serverId, stack.toList(), labels.toList())
    }
    val isFav = favorites.any { it.urls.lastOrNull() == current }

    // ⬇️ 이 폴더 모두 담기 → 컬렉션으로 묶을지 묻기
    // (folder name, books, what part of it: null = the whole folder, else "처음부터 「…」까지")
    val askFolderState = remember { mutableStateOf<Triple<String, List<OpdsEntry>, String?>?>(null) }
    val rangeMenuState = remember { mutableStateOf<OpdsEntry?>(null) }   // long-pressed book: 여기까지 / 여기부터 받기
    val libBooks by remember { db.books().observeAll() }.collectAsState(initial = emptyList())
    val byOpds = remember(libBooks) { libBooks.filter { it.opdsId != null }.associateBy { it.opdsId!! } }
    val gatheringAllState = remember { mutableStateOf(false) }
    val gatheringAll by gatheringAllState
    val folder = OpdsFolderActions(
        context, app, server, client, db, scope, path, extra, nextUrlState, gatheringAllState, askFolderState, detailState, byOpds, onOpenBook,
    )
    OpdsAskFolderDialog(askFolderState, ownedSet, folder)
    if (editFavorites) FavoritesEditDialog(
        favorites,
        onChange = { favorites = it; OpdsFavorites.save(app, serverId, it) },
        onDismiss = { editFavorites = false },
    )

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OpdsHeader(
            app, context, client, scope, serverId, server.name, path, isFav, favoritesState, lastPlace,
            editFavoritesState, rootSearch, queryState, ::back, onOpenSettings, onOrganized,
        )

        OpdsFeedBody(
            state, onRetry = { attempt++ }, onOpenSettings, extra, nextUrlState, sortState, sortDescState, gridState, sortPrefs,
            loadingAll, serverId, current, libBooks, byOpds, ownedSet, gatheringAll, progress, app, server, client,
            path, paging, folder, rangeMenuState, detailState,
        )
    }

    OpdsRangeDialog(rangeMenuState, stateState, sortState, sortDescState, folder)
    OpdsEntryDialog(detailState, ownedSet, progress, client, db, scope, app, server, path, onOpenBook)
}

@Composable
private fun OpdsNeedsSetup(name: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(name, onBack = onBack)
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("「${name}」 주소나 로그인 정보가 아직 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenSettings) { Text("연결 설정하기") }
        }
    }
    BackHandler(onBack = onBack)
}
