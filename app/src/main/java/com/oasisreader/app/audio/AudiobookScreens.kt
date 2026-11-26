package com.oasisreader.app.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.ui.TopBar
import kotlinx.coroutines.launch

/**
 * 오디오북: the audiobook shelves of bookoasis (its own web API, not OPDS), a book's tracks,
 * and the player. [onPlayer] opens the full player.
 */
@Composable
fun AudiobookScreen(serverId: String, onBack: () -> Unit, onPlayer: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val server = remember(serverId) { ServerSettings(context.applicationContext).get(serverId) }
    if (server == null || server.username.isBlank()) {
        AudioNeedsAccount(onBack, onOpenSettings)
        return
    }
    val web = remember(server) { BookOasisWeb.of(server) }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("audio", android.content.Context.MODE_PRIVATE) }

    var libraries by remember(server) { mutableStateOf<List<AudioLibrary>>(emptyList()) }
    // categories: several at once (bookoasis takes one, or all: several are picked from "all" in its order)
    val libIdsState = remember(server) {
        mutableStateOf(
            prefs.getStringSet("libs_${server.id}", null)?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: setOfNotNull(prefs.getString("library_${server.id}", null)?.toIntOrNull()),
        )
    }
    var libIds by libIdsState
    val readFilterState = remember(server) { mutableStateOf(prefs.getString("read_${server.id}", "") ?: "") }   // "" | "listening" | "done"
    val readFilter by readFilterState
    val sortState = remember { mutableStateOf(runCatching { AudioSort.valueOf(prefs.getString("sort", null) ?: "") }.getOrDefault(AudioSort.NAME)) }
    val sort by sortState
    val sortDescState = remember { mutableStateOf(prefs.getBoolean("sort_desc", false)) }
    val sortDesc by sortDescState
    val presetsState = remember(server) { mutableStateOf(com.oasisreader.app.meta.CatalogPresets.load(context, server.id)) }
    val savingPresetState = remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val activeState = remember { mutableStateOf("") }
    var active by activeState
    // what the search looks in: bookoasis takes a prefix ("작가:", "주제:"); none = the title
    val fieldState = remember { mutableStateOf(prefs.getString("search_field", "title") ?: "title") }
    val field by fieldState
    val items = remember(server) { mutableStateListOf<AudioItem>() }
    val pageState = remember { mutableIntStateOf(1) }
    val page by pageState
    val hasMoreState = remember { mutableStateOf(false) }
    val hasMore by hasMoreState
    val totalState = remember { mutableStateOf<Int?>(null) }
    val total by totalState
    val totals = remember(server) { HashMap<String, Int>() }   // all that match, when the server says and no app-side filter applies
    val loadingState = remember { mutableStateOf(false) }
    val loading by loadingState
    val errorState = remember { mutableStateOf<String?>(null) }
    var error by errorState
    var open by remember { mutableStateOf<AudioItem?>(null) }

    LaunchedEffect(server) {
        runCatching { web.get("/api/media/libraries", mapOf("type" to "audiobook")) }
            .onSuccess { o ->
                libraries = audioLibraries(o)
            }
            .onFailure { error = it.message }
    }
    // several categories: bookoasis takes one library_id, so each is asked on its own (OR) and the
    // results merged in the chosen order; each keeps its own next page (a merged page can be a
    // little out of order at the seams, never empty while any category has more)
    val cursors = remember(server) { HashMap<String, Int>() }       // library ("all" = none chosen) → next page
    val done = remember(server) { HashSet<String>() }
    val shelf = AudioShelfLoader(
        web, items, totals, cursors, done,
        libIdsState, readFilterState, activeState, fieldState, sortState, sortDescState,
        pageState, hasMoreState, totalState, loadingState, errorState,
    )
    LaunchedEffect(server, libIds, readFilter, active, field, sort, sortDesc) { shelf.load(1) }

    BackHandler { if (open != null) open = null else onBack() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(server.name.ifBlank { "오디오북" }, onBack = { if (open != null) open = null else onBack() }) {
            com.oasisreader.app.ui.HomeButton()
            com.oasisreader.app.ui.EmojiButton("⚙️", "서버 설정", onOpenSettings)
            if (AudioPlayback.book != null) com.oasisreader.app.ui.EmojiButton("🎧", "재생 중", onPlayer)
        }
        val o = open
        if (o != null) AudiobookDetail(web, o, onPlayer) else AudioShelf(
            filters = {
                AudioShelfFilters(
                    server.id, prefs, libraries, libIdsState, presetsState, savingPresetState,
                    fieldState, readFilterState, sortState, sortDescState,
                )
            },
            query = query, onQuery = { query = it }, onSearch = { active = query.trim() },
            items, hasMore, loading, page, error, web,
            count = when {
                loading && items.isEmpty() -> null
                total != null -> "🎧 ${"%,d".format(total)}권"
                items.isNotEmpty() || !loading -> "🎧 " + com.oasisreader.app.ui.loadedCount(items.size, hasMore, "권")
                else -> null
            },
            load = { p -> scope.launch { shelf.load(p) } },
            onOpen = { open = it },
        )
    }
}

@Composable
private fun AudioNeedsAccount(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("오디오북", onBack = onBack)
        Text("북오아시스 계정(아이디·비밀번호)이 필요합니다. 서버 설정에서 넣어 주세요.", modifier = Modifier.padding(24.dp))
        TextButton(onClick = onOpenSettings, modifier = Modifier.padding(horizontal = 16.dp)) { Text("서버 설정") }
    }
}
