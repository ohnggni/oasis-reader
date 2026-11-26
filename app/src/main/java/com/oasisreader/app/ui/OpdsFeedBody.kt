package com.oasisreader.app.ui

import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.Server
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import android.content.SharedPreferences
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.ContinueReading
import com.oasisreader.app.data.Downloads
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.data.OpdsEntry

/** The page itself: loading, failed, or the sort bar, the counts and the folders and books. */
@Composable
internal fun OpdsFeedBody(
    state: FeedState, onRetry: () -> Unit, onOpenSettings: () -> Unit, extra: List<OpdsEntry>,
    nextUrlState: State<String?>, sortState: MutableState<CatalogSort>, sortDescState: MutableState<Boolean>,
    gridState: MutableState<Boolean>, sortPrefs: SharedPreferences, loadingAll: Int?, serverId: String, current: String,
    libBooks: List<BookEntity>, byOpds: Map<String, BookEntity>, ownedSet: Set<String>, gatheringAll: Boolean,
    progress: Map<String, Float>, app: Context, server: Server, client: OpdsClient,
    path: OpdsPath, paging: OpdsPaging, folder: OpdsFolderActions,
    rangeMenuState: MutableState<OpdsEntry?>, detailState: MutableState<OpdsEntry?>,
) {
    val nextUrl by nextUrlState
    var sort by sortState
    var sortDesc by sortDescState
    var grid by gridState
    var rangeMenu by rangeMenuState
    var detail by detailState
    when (val s = state) {
        is FeedState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is FeedState.Failed -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("다시 시도") }
            TextButton(onClick = onOpenSettings) { Text("연결 설정") }
        }
        is FeedState.Ready -> {
            val all = s.feed.entries + extra
            // some servers stamp every entry with the request time: then dates say nothing
            val datesUseful = remember(all.size, s) { datesMeaningful(all) }
            val effSort = if (!datesUseful && sort == CatalogSort.DATE) CatalogSort.SERVER else sort
            val entries = remember(all.size, s, effSort, sortDesc) { sortEntries(all, effSort, sortDesc) }
            if (all.size > 1) {
                SortBar(
                    sort = effSort, desc = sortDesc, loading = loadingAll, datesUseful = datesUseful,
                    onSort = { o, d -> sort = o; sortDesc = d; sortPrefs.edit().putString("sort", o.name).putBoolean("sort_desc", d).apply() },
                    grid = grid, onGrid = { grid = it; sortPrefs.edit().putBoolean("grid", it).apply() },
                )
            }
            // how many are here: books and folders (the server's count when it gives one)
            val nBooks = all.count { it.isBook }
            val nFolders = all.size - nBooks
            val more = nextUrl != null
            CountLine(
                s.feed.totalResults?.let { "📚 ${"%,d".format(it)}개" }
                    ?: listOfNotNull(
                        nFolders.takeIf { it > 0 }?.let { "📂 폴더 " + loadedCount(it, more && nBooks == 0) },
                        nBooks.takeIf { it > 0 }?.let { "📕 " + loadedCount(it, more, "권") },
                    ).joinToString(" · ").ifEmpty { null },
            )
            val folderMissing = all.count { it.isBook && it.readable != null && it.id !in ownedSet }
            // ▶ 이어 읽기: shown when something of this folder was read — in the loaded part, or (more pages
            // still to come) when any book of the 서재 came from this very folder
            val volumes = all.filter { it.isBook && it.readable != null }
            val fromHere = remember(libBooks, current) {
                libBooks.any { b ->
                    com.oasisreader.app.data.BookOrigin.decode(b.origin)
                        ?.let { o -> o.type == com.oasisreader.app.data.BookOrigin.OPDS && o.server == serverId && o.urls.lastOrNull() == current } == true
                }
            }
            val canResume = nBooks > 1 && ((more && fromHere) || ContinueReading.pick(volumes, { it.title }) { byOpds[it.id] } != null)
            if (nBooks > 1 && (folderMissing > 0 || more || canResume)) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(MiniChipGap),
            ) {
                if (canResume) MiniChip(
                    if (gatheringAll) "⏳ 찾는 중…" else "▶️ 이어 읽기",
                    onClick = { folder.continueReading(s) }, enabled = !gatheringAll, description = "읽던 회차로 바로 가기",
                )
                if (folderMissing > 0 || more) MiniChip(
                    if (gatheringAll) "⏳ 목록을 모으는 중…" else "⬇️ 이 폴더 " + loadedCount(folderMissing, more, "권") + " 모두 담기",
                    onClick = { folder.downloadFolder(s) }, enabled = !gatheringAll,
                )
            }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("항목이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (grid && entries.any { it.isBook }) {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(112.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                ) {
                    items(entries.size) { i ->
                        val e = entries[i]
                        EntryTile(
                            entry = e,
                            client = client,
                            owned = e.id in ownedSet,
                            progress = progress[e.id],
                            onClick = { if (e.navHref != null && !e.isBook) path.open(e.navHref, e.title) else detail = e },
                            onDownload = { WifiAsk.run(app) { mobile -> Downloads.start(app, server, e, origin = path.here(), mobile = mobile) } },
                            onLongClick = if (e.isBook && e.readable != null && nBooks > 1) ({ rangeMenu = e }) else null,
                        )
                    }
                    nextUrl?.let { more ->
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            OpdsMoreRow(more, paging)
                        }
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(entries) { e ->
                    EntryRow(
                        entry = e,
                        client = client,
                        owned = e.id in ownedSet,
                        showDate = effSort == CatalogSort.DATE,
                        progress = progress[e.id],
                        onClick = {
                            if (e.navHref != null && !e.isBook) path.open(e.navHref, e.title) else detail = e
                        },
                        onDownload = { WifiAsk.run(app) { mobile -> Downloads.start(app, server, e, origin = path.here(), mobile = mobile) } },
                        onLongClick = if (e.isBook && e.readable != null && nBooks > 1) ({ rangeMenu = e }) else null,
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                nextUrl?.let { more ->
                    item {
                        OpdsMoreRow(more, paging)
                    }
                }
            }
        }
    }
}
