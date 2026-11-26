package com.oasisreader.app.meta

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import android.content.SharedPreferences
import android.content.Context
import com.oasisreader.app.data.MetaLibrary
import com.oasisreader.app.ui.OgMenu
import androidx.compose.foundation.horizontalScroll
import com.oasisreader.app.ui.grouped
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/** The 칩 row: presets · 카테고리 · 형식 · 정렬. MetaCatalogScreen owns the states passed in. */
@Composable
internal fun MetaFilterRow(
    app: Context, serverId: String, prefs: SharedPreferences, libraries: List<MetaLibrary>?,
    libIdsState: MutableState<Set<Int>>, fmtKeysState: MutableState<Set<String>>, presetsState: MutableState<List<CatalogPreset>>,
    savingPresetState: MutableState<Boolean>, sortState: MutableState<SeriesSort>, sortDescState: MutableState<Boolean>,
    activeQuery: String, searchBooks: Boolean,
) {
    var libIds by libIdsState
    var fmtKeys by fmtKeysState
    var presets by presetsState
    var savingPreset by savingPresetState
    var sort by sortState
    var sortDesc by sortDescState
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(com.oasisreader.app.ui.MiniChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // categories: several at once (none = 전체), picked in a drop-down with check boxes
        fun setLibs(v: Set<Int>) { libIds = v; prefs.edit().putStringSet("libraries_$serverId", v.map { it.toString() }.toSet()).apply() }
        fun setFormats(v: Set<String>) { fmtKeys = v; prefs.edit().putStringSet("formats_$serverId", v).apply() }
        // saved combinations (made here or in the server settings)
        PresetChip(
            presets = presets, libs = libIds, formats = fmtKeys,
            onPick = { p -> setLibs(p?.libraries?.toSet() ?: emptySet()); setFormats(p?.formats?.toSet() ?: emptySet()) },
            onSaveCurrent = { savingPreset = true },
        )
        if (savingPreset) PresetDialog(
            initial = CatalogPreset("", libIds.sorted(), CatalogFormats.ALL.map { it.key }.filter { it in fmtKeys }),
            libraries = null,
            onSave = { p -> presets = presets + p; CatalogPresets.save(app, serverId, presets); savingPreset = false },
            onDelete = null,
            onDismiss = { savingPreset = false },
        )
        var libOpen by remember { mutableStateOf(false) }
        val libs = libraries.orEmpty()
        Box {
            com.oasisreader.app.ui.MiniChip(
                "🗂️ " + when (libIds.size) {
                    0 -> "전체"
                    1 -> libs.firstOrNull { it.id in libIds }?.name ?: "1개"
                    else -> "${libIds.size}개"
                },
                selected = libIds.isNotEmpty(),
                onClick = { libOpen = true },
                description = "카테고리",
            )
            com.oasisreader.app.ui.OgMenu(expanded = libOpen, onDismissRequest = { libOpen = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("전체") },
                    leadingIcon = { androidx.compose.material3.Checkbox(checked = libIds.isEmpty(), onCheckedChange = null) },
                    onClick = { setLibs(emptySet()) },
                )
                HorizontalDivider()
                libs.forEach { l ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(l.name) },
                        leadingIcon = { androidx.compose.material3.Checkbox(checked = l.id in libIds, onCheckedChange = null) },
                        onClick = { setLibs(if (l.id in libIds) libIds - l.id else libIds + l.id) },
                    )
                }
                androidx.compose.material3.DropdownMenuItem(text = { Text("완료", color = MaterialTheme.colorScheme.primary) }, onClick = { libOpen = false })
            }
        }
        FormatFilterChip(fmtKeys) { setFormats(it) }
        if (!(activeQuery.isNotBlank() && searchBooks)) {
            com.oasisreader.app.ui.SortChip(
                options = SeriesSort.entries, selected = sort, desc = sortDesc,
                label = { it.label }, kind = { it.kind }, defaultDesc = { it.defaultDesc },
                short = { when (it) { SeriesSort.DATE -> "올라온 날"; SeriesSort.ADDED -> "추가일"; else -> it.label } },
                onChange = { o, d -> sort = o; sortDesc = d; prefs.edit().putString("sort", o.name).putBoolean("sort_desc", d).apply() },
            )
        }
    }
}

/** One line: how many (left) · 시리즈/권 · 타일·크기 (right). */
@Composable
internal fun MetaCountRow(
    prefs: SharedPreferences, loading: Boolean, seriesEmpty: Boolean, booksEmpty: Boolean, seriesCount: Int, bookCount: Int,
    hasMore: Boolean, total: Int?, totalBooks: Int?, activeQuery: String,
    searchBooksState: MutableState<Boolean>, seriesListState: MutableState<Boolean>, seriesTileState: MutableIntState,
) {
    var searchBooks by searchBooksState
    var seriesList by seriesListState
    var seriesTile by seriesTileState
    val countText = when {
        loading && seriesEmpty && booksEmpty -> null
        activeQuery.isNotBlank() && searchBooks -> total?.let { "📕 ${it.grouped()}권" } ?: "📕 " + com.oasisreader.app.ui.loadedCount(bookCount, hasMore, "권")
        total != null -> "📚 시리즈 ${total.grouped()}개" + (totalBooks?.let { " · ${it.grouped()}권" } ?: "")
        !seriesEmpty -> "📚 시리즈 " + com.oasisreader.app.ui.loadedCount(seriesCount, hasMore)
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(com.oasisreader.app.ui.MiniChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Text(
                countText.orEmpty(), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (activeQuery.isNotBlank()) {
            com.oasisreader.app.ui.MiniChip("📚 시리즈", selected = !searchBooks, onClick = { searchBooks = false })
            com.oasisreader.app.ui.MiniChip("📕 권", selected = searchBooks, onClick = { searchBooks = true })
        }
        if (!(activeQuery.isNotBlank() && searchBooks)) {
            com.oasisreader.app.ui.MiniChip(
                if (seriesList) "📃" else "🔲", plain = true,
                onClick = { seriesList = !seriesList; prefs.edit().putBoolean("series_list", seriesList).apply() },
                description = if (seriesList) "목록으로 보는 중 (누르면 타일)" else "타일로 보는 중 (누르면 목록)",
            )
            if (!seriesList) {
                var sizeOpen by remember { mutableStateOf(false) }
                Box {
                    com.oasisreader.app.ui.MiniChip("📐", onClick = { sizeOpen = true }, description = "타일 크기", plain = true)
                    com.oasisreader.app.ui.OgMenu(expanded = sizeOpen, onDismissRequest = { sizeOpen = false }) {
                        Column(Modifier.width(260.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text("타일 크기", style = MaterialTheme.typography.labelLarge)
                            androidx.compose.material3.Slider(
                                value = SERIES_TILE_STEPS.indexOf(seriesTile).toFloat(),
                                onValueChange = {
                                    seriesTile = SERIES_TILE_STEPS[Math.round(it).coerceIn(0, SERIES_TILE_STEPS.size - 1)]
                                    prefs.edit().putInt("series_tile", seriesTile).apply()
                                },
                                valueRange = 0f..(SERIES_TILE_STEPS.size - 1).toFloat(),
                                steps = SERIES_TILE_STEPS.size - 2,
                            )
                            Row {
                                Text("작게", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                Text("크게", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
