package com.oasisreader.app.meta

import com.oasisreader.app.data.MetaSeries
import com.oasisreader.app.data.MetaBook
import com.oasisreader.app.data.MetaApi
import com.oasisreader.app.ui.EmojiText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.OpdsClient

/** What was found: the error, or the books, the series list or the series tiles. */
@Composable
internal fun MetaResults(
    api: MetaApi, opds: OpdsClient, err: String?, libIds: Set<Int>, libNames: Map<Int, String>, activeQuery: String, searchBooks: Boolean,
    seriesItems: List<MetaSeries>, bookItems: List<MetaBook>, seriesList: Boolean, seriesTile: Int, loading: Boolean, hasMore: Boolean,
    page: Int, ownedSet: Set<String>, progress: Map<String, Float>,
    onRetry: () -> Unit, onOpdsFolders: () -> Unit, onOpenSettings: () -> Unit,
    onSeries: (MetaSeries) -> Unit, onBook: (MetaBook) -> Unit, onAction: (MetaBook) -> Unit, onMore: () -> Unit,
) {
    // how many match: from the server when it says, else what is loaded so far
    // where the search looked: the chosen categories, or all of them (same-named books, e.g. a novel
    // and a comic, are told apart by the 📁 line; narrowing to one category finds only that one)
    if (activeQuery.isNotBlank()) Text(
        if (libIds.isEmpty()) "📁 모든 카테고리에서 찾았습니다 · 카테고리 칩으로 좁힐 수 있습니다"
        else "📁 " + libIds.mapNotNull { libNames[it] }.joinToString(", ").ifBlank { "고른 카테고리" } + " 안에서 찾았습니다",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
    )
    val booksMode = activeQuery.isNotBlank() && searchBooks
    when {
        err != null && seriesItems.isEmpty() && bookItems.isEmpty() -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EmojiText("⚠️ $err", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("다시 시도") }
            TextButton(onClick = onOpdsFolders) { EmojiText("📂 OPDS 폴더로 보기") }
            TextButton(onClick = onOpenSettings) { Text("서버 설정") }
        }
        activeQuery.isNotBlank() && searchBooks -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(bookItems, key = { it.id }) { b ->
                val href = api.downloadUrl(b)
                BookRow(b, api, opds, owned = href in ownedSet, progress = progress[api.key(b)], showSeries = true,
                    library = b.libraryId?.let { libNames[it] },
                    onClick = { onBook(b) }, onAction = { onAction(b) })
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            item { MoreFooter(loading, hasMore, empty = bookItems.isEmpty(), autoKey = page, onMore = onMore) }
        }
        seriesList -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(seriesItems.size, key = { i -> seriesItems[i].libraryId.toString() + "|" + seriesItems[i].seriesName }) { i ->
                val s = seriesItems[i]
                Row(
                    Modifier.fillMaxWidth().clickable { onSeries(s) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaCover(api, opds, s.coverUrl, s.firstBookId, s.seriesName, modifier = Modifier.width(48.dp).height(70.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.seriesName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(libNames[s.libraryId]?.let { "📁 $it" }, "${s.bookCount}권", metaDate(s.latestMtime)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            item { MoreFooter(loading, hasMore, empty = seriesItems.isEmpty(), autoKey = page, onMore = onMore) }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(seriesTile.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (seriesTile < 100) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (seriesTile < 100) 12.dp else 16.dp),
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            items(seriesItems.size, key = { i -> seriesItems[i].libraryId.toString() + "|" + seriesItems[i].seriesName }) { i ->
                val s = seriesItems[i]
                Column(Modifier.clickable { onSeries(s) }) {
                    MetaCover(
                        api, opds, s.coverUrl, s.firstBookId, s.seriesName,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(com.oasisreader.app.data.ShelfSearch.breakAnywhere(s.seriesName), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull("${s.bookCount}권", metaDate(s.latestMtime)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                MoreFooter(loading, hasMore, empty = seriesItems.isEmpty(), autoKey = page, onMore = onMore)
            }
        }
    }
}

@Composable
private fun MoreFooter(loading: Boolean, hasMore: Boolean, empty: Boolean, autoKey: Int, onMore: () -> Unit) {
    // reached the end of the list: fetch the next page by itself, once per page (after a failure
    // the page stays the same, so it waits for the button instead of retrying in a loop)
    androidx.compose.runtime.LaunchedEffect(autoKey, hasMore) { if (hasMore && !loading) onMore() }
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(28.dp))
            hasMore -> TextButton(onClick = onMore) { Text("더 보기") }
            empty -> Text("결과가 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
