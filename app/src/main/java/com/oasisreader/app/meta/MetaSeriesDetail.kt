package com.oasisreader.app.meta

import com.oasisreader.app.data.MetaSeries
import com.oasisreader.app.data.MetaBook
import com.oasisreader.app.data.MetaApi
import com.oasisreader.app.ui.EmojiText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.ComicParser
import com.oasisreader.app.data.ContinueReading
import com.oasisreader.app.data.VolumeRange
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.ui.TopBar

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun SeriesDetail(
    series: MetaSeries,
    format: String?,
    api: MetaApi,
    opds: OpdsClient,
    ownedSet: Set<String>,
    pinnedSet: Set<String>,
    progress: Map<String, Float>,
    onBack: () -> Unit,
    onBook: (MetaBook) -> Unit,
    onAction: (MetaBook) -> Unit,
    onDownloadAll: (List<MetaBook>) -> Unit,
    onDownloadRange: (List<MetaBook>, String) -> Unit,
) {
    BackHandler(onBack = onBack)
    // 이어 읽기 (TODO 65): the 서재 entry of each volume, by its file address
    val ctx = LocalContext.current
    val shelf by remember { AppDatabase.get(ctx.applicationContext).books().observeAll() }.collectAsState(initial = emptyList())
    val byUrl = remember(shelf) { shelf.filter { it.sourceUrl != null }.associateBy { it.sourceUrl!! } }
    var rangeMenu by remember { mutableStateOf<MetaBook?>(null) }   // long-pressed volume: 여기까지 / 여기부터 받기
    var books by remember { mutableStateOf<List<MetaBook>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(series, attempt) {
        error = null
        books = try {
            // volume order: natural sort of the clean titles ("2권" before "10권")
            api.books(series.libraryId.toString(), series.seriesName, null, "title", 1, 500, format = format).items
                .sortedWith { a, b -> ComicParser.naturalOrder.compare(a.name, b.name) }
        } catch (e: Exception) { error = e.message; null }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(series.seriesName, onBack = onBack) { com.oasisreader.app.ui.HomeButton() }
        val list = books
        when {
            error != null -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmojiText("⚠️ $error", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { attempt++ }) { Text("다시 시도") }
            }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val missing = list.count { b -> b.readable && api.downloadUrl(b)?.let { it !in pinnedSet } == true }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${list.size}권" + (metaDate(series.latestMtime)?.let { " · 최근 $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (missing > 1) OutlinedButton(onClick = { onDownloadAll(list) }) { EmojiText("⬇️ 서재에 없는 ${missing}권 모두 담기") }
                }
                val resume = ContinueReading.pick(list, { it.name }) { b -> api.downloadUrl(b)?.let { byUrl[it] } }
                if (resume != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    com.oasisreader.app.ui.MiniChip(
                        "▶️ 이어 읽기",
                        onClick = { if (api.downloadUrl(resume) in ownedSet) onAction(resume) else onBook(resume) },
                        description = "읽던 회차로 바로 가기",
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                    items(list, key = { it.id }) { b ->
                        BookRow(b, api, opds, owned = api.downloadUrl(b) in ownedSet, progress = progress[api.key(b)],
                            showSeries = false, onClick = { onBook(b) }, onAction = { onAction(b) },
                            onLongClick = if (b.readable && list.size > 1) ({ rangeMenu = b }) else null)
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
    rangeMenu?.let { m -> books?.let { l -> RangeMenu(l, m, onDownloadRange) { rangeMenu = null } } }
}

@Composable
private fun RangeMenu(
    list: List<MetaBook>, at: MetaBook,
    onPick: (List<MetaBook>, String) -> Unit, onDismiss: () -> Unit,
) {
    val i = list.indexOfFirst { it.id == at.id }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(at.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                TextButton(onClick = {
                    onDismiss(); onPick(VolumeRange.slice(list, i, false).filter { it.readable }, "처음부터 「${at.name}」까지")
                }) { EmojiText("⬇️ 처음부터 여기까지 받기") }
                TextButton(onClick = {
                    onDismiss(); onPick(VolumeRange.slice(list, i, true).filter { it.readable }, "「${at.name}」부터 끝까지")
                }) { EmojiText("⬇️ 여기부터 끝까지 받기") }
                Text(
                    "지금 화면에 보이는 순서 기준입니다. 이미 서재에 있는 책은 건너뜁니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun BookRow(
    b: MetaBook,
    api: MetaApi,
    opds: OpdsClient,
    owned: Boolean,
    progress: Float?,
    showSeries: Boolean,
    library: String? = null, // shown with the series: "📁 만화 › 괴력난신" (so same-named books can be told apart)
    onClick: () -> Unit,     // details
    onAction: () -> Unit,    // download / open
    onLongClick: (() -> Unit)? = null,   // series detail: 여기까지 / 여기부터 받기
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaCover(api, opds, b.coverUrl, b.id, b.name, modifier = Modifier.width(48.dp).height(70.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(formatEmoji(b.fileFormat) + " " + b.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                if (showSeries) listOfNotNull(library, b.seriesName).joinToString(" › ").ifBlank { null }?.let { "📁 $it" } else null,
                metaDate(b.fileMtime),
                b.fileFormat?.uppercase(),
                mb(b.fileSize),
                b.pageCount?.let { "${it}쪽" },
            ).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        when {
            owned -> Box(Modifier.size(48.dp).clickable(onClick = onAction), contentAlignment = Alignment.Center) {
                EmojiText("📖", style = MaterialTheme.typography.titleMedium)
            }
            b.readable -> com.oasisreader.app.ui.DownloadControl(api.key(b), progress, onAction, size = 44.dp)
            else -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
