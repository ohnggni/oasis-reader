package com.oasisreader.app.audio

import androidx.compose.runtime.MutableState
import android.content.SharedPreferences
import com.oasisreader.app.ui.OgMenu
import com.oasisreader.app.ui.EmojiText
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.ui.RemoteCover

/** The 칩 row of the 서가: presets, 검색 대상, 카테고리, 읽음, 정렬. AudiobookScreen owns the states. */
@Composable
internal fun AudioShelfFilters(
    serverId: String, prefs: SharedPreferences, libraries: List<AudioLibrary>,
    libIdsState: MutableState<Set<Int>>, presetsState: MutableState<List<com.oasisreader.app.meta.CatalogPreset>>,
    savingPresetState: MutableState<Boolean>, fieldState: MutableState<String>, readFilterState: MutableState<String>,
    sortState: MutableState<AudioSort>, sortDescState: MutableState<Boolean>,
) {
    val context = LocalContext.current
    var libIds by libIdsState
    var presets by presetsState
    var savingPreset by savingPresetState
    var field by fieldState
    var readFilter by readFilterState
    var sort by sortState
    var sortDesc by sortDescState
    fun setLibs(v: Set<Int>) { libIds = v; prefs.edit().putStringSet("libs_$serverId", v.map { it.toString() }.toSet()).apply() }
    com.oasisreader.app.meta.PresetChip(
        presets = presets, libs = libIds, formats = emptySet(),
        onPick = { p -> setLibs(p?.libraries?.toSet() ?: emptySet()) },
        onSaveCurrent = { savingPreset = true },
    )
    if (savingPreset) com.oasisreader.app.meta.PresetDialog(
        initial = com.oasisreader.app.meta.CatalogPreset("", libIds.sorted()),
        libraries = null,
        onSave = { p -> presets = presets + p; com.oasisreader.app.meta.CatalogPresets.save(context, serverId, presets); savingPreset = false },
        onDelete = null,
        onDismiss = { savingPreset = false },
    )
    SearchFieldChip(field) { field = it; prefs.edit().putString("search_field", it).apply() }
    CategoryChip(libraries, libIds) { setLibs(it) }
    ReadStateChip(readFilter) { readFilter = it; prefs.edit().putString("read_$serverId", it).apply() }
    com.oasisreader.app.ui.SortChip(
        options = AudioSort.entries, selected = sort, desc = sortDesc,
        label = { it.label }, kind = { it.kind }, defaultDesc = { it.defaultDesc },
        short = { when (it) { AudioSort.FOLDER -> "폴더"; AudioSort.ADDED -> "추가일"; else -> it.label } },
        onChange = { o, d -> sort = o; sortDesc = d; prefs.edit().putString("sort", o.name).putBoolean("sort_desc", d).apply() },
    )
}

@Composable
internal fun AudioShelf(
    filters: @Composable () -> Unit,
    query: String, onQuery: (String) -> Unit, onSearch: () -> Unit,
    items: List<AudioItem>, hasMore: Boolean, loading: Boolean, page: Int, error: String?, web: BookOasisWeb,
    count: String?,
    load: (Int) -> Unit, onOpen: (AudioItem) -> Unit,
) {
    Column {
        com.oasisreader.app.ui.CompactSearchField(
            value = query, onValueChange = onQuery, placeholder = "검색 (🔍 칩에서 제목·작가·주제)", onSearch = onSearch,
            onClear = onSearch,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(com.oasisreader.app.ui.MiniChipGap),
            verticalAlignment = Alignment.CenterVertically,
        ) { filters() }
        com.oasisreader.app.ui.CountLine(count)
        when {
            error != null && items.isEmpty() -> Column(Modifier.padding(24.dp)) {
                EmojiText("⚠️ $error", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { load(1) }) { Text("다시 시도") }
            }
            loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            // nothing yet but more to look through (the 읽음 filter): offer to read on, never a dead end
            items.isEmpty() && hasMore -> Column(Modifier.padding(24.dp)) {
                Text("아직 맞는 오디오북을 못 찾았습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { load(page + 1) }) { EmojiText("🔎 더 찾아보기") }
            }
            items.isEmpty() -> Text("오디오북이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp),
            ) {
                itemsIndexed(items, key = { i, it -> "${it.id}:${it.title}:$i" }) { i, it ->
                    if (i == items.lastIndex && hasMore && !loading) LaunchedEffect(page) { load(page + 1) }
                    Column(Modifier.padding(6.dp).clickable { onOpen(it) }) {
                        RemoteCover(it.cover?.let(web::absolute), it.title, { u -> web.bytes(u) }, Modifier.fillMaxWidth().aspectRatio(1f))
                        it.progress?.takeIf { p -> p > 0 }?.let { p ->
                            LinearProgressIndicator(progress = { (p / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp))
                        }
                        Text((if (it.completed) "✅ " else "") + it.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        it.author?.let { a -> Text(a, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** Categories with check boxes (none = 전체), like the book catalog. */
@Composable
internal fun CategoryChip(libraries: List<AudioLibrary>, selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        com.oasisreader.app.ui.MiniChip(
            "🗂️ " + when (selected.size) {
                0 -> "전체"
                1 -> libraries.firstOrNull { it.id.toIntOrNull() in selected }?.name ?: "1개"
                else -> "${selected.size}개"
            },
            selected = selected.isNotEmpty(), onClick = { open = true },
            description = "카테고리",
        )
        OgMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text("전체") },
                leadingIcon = { androidx.compose.material3.Checkbox(checked = selected.isEmpty(), onCheckedChange = null) },
                onClick = { onChange(emptySet()) },
            )
            HorizontalDivider()
            libraries.forEach { l ->
                val id = l.id.toIntOrNull() ?: return@forEach
                DropdownMenuItem(
                    text = { Text(l.name) },
                    leadingIcon = { androidx.compose.material3.Checkbox(checked = id in selected, onCheckedChange = null) },
                    onClick = { onChange(if (id in selected) selected - id else selected + id) },
                )
            }
            DropdownMenuItem(text = { Text("완료", color = MaterialTheme.colorScheme.primary) }, onClick = { open = false })
        }
    }
}

/** 전체 / 듣는 중·안 들음 / 다 들음 (bookoasis marks a book done when all of it was heard). */
@Composable
internal fun ReadStateChip(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf("" to "전체", "listening" to "다 듣지 않음", "done" to "다 들음")
    Box {
        com.oasisreader.app.ui.MiniChip(
            "🔖 " + (options.firstOrNull { it.first == value }?.second ?: "전체"),
            selected = value.isNotEmpty(), onClick = { open = true },
        )
        OgMenu(open, { open = false }) {
            options.forEach { (v, label) ->
                DropdownMenuItem(text = { Text(label) }, leadingIcon = { com.oasisreader.app.ui.CheckMark(v == value) }, onClick = { open = false; onChange(v) })
            }
        }
    }
}

private val SEARCH_FIELDS = listOf("title" to "제목", "author" to "작가", "topic" to "주제")

/** 🔍 제목 / 작가 / 주제: where the search looks. */
@Composable
internal fun SearchFieldChip(field: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        com.oasisreader.app.ui.MiniChip(
            "🔍 " + (SEARCH_FIELDS.firstOrNull { it.first == field }?.second ?: "제목"),
            onClick = { open = true }, selected = field != "title", description = "검색 대상",
        )
        com.oasisreader.app.ui.OgMenu(expanded = open, onDismissRequest = { open = false }) {
            SEARCH_FIELDS.forEach { (k, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label + "에서 찾기") },
                    leadingIcon = { com.oasisreader.app.ui.CheckMark(k == field) },
                    onClick = { open = false; onPick(k) },
                )
            }
        }
    }
}
