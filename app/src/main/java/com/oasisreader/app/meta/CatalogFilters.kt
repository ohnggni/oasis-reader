package com.oasisreader.app.meta

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.Server
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** File-format filter of the organized catalog. A choice may stand for several formats (만화: cbz + zip …). */
object CatalogFormats {
    data class Choice(val key: String, val label: String, val formats: List<String>)

    val ALL = listOf(
        Choice("epub", "EPUB", listOf("epub")),
        Choice("txt", "TXT", listOf("txt")),
        Choice("pdf", "PDF", listOf("pdf")),
        Choice("comic", "만화 (CBZ·ZIP)", listOf("cbz", "zip", "cbr", "rar", "cb7", "7z")),
    )

    /** The server parameter for chosen keys (null = every format). */
    fun param(keys: Set<String>): String? =
        ALL.filter { it.key in keys }.flatMap { it.formats }.takeIf { it.isNotEmpty() }?.joinToString(",")

    fun label(keys: Set<String>): String = when (keys.size) {
        0 -> "형식: 전체"
        1 -> "형식: " + (ALL.firstOrNull { it.key in keys }?.label ?: "1개")
        else -> "형식 ${keys.size}개"
    }
}

/** A saved combination of categories and formats ("만화" = 만화 categories + CBZ·ZIP …). */
@Serializable
data class CatalogPreset(val name: String, val libraries: List<Int> = emptyList(), val formats: List<String> = emptyList()) {
    fun matches(libs: Set<Int>, fmts: Set<String>) = libraries.toSet() == libs && formats.toSet() == fmts
}

object CatalogPresets {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("meta_catalog", Context.MODE_PRIVATE)

    fun load(c: Context, serverId: String): List<CatalogPreset> = runCatching {
        json.decodeFromString(ListSerializer(CatalogPreset.serializer()), prefs(c).getString("presets_$serverId", null) ?: "[]")
    }.getOrDefault(emptyList())

    fun save(c: Context, serverId: String, list: List<CatalogPreset>) {
        prefs(c).edit().putString("presets_$serverId", json.encodeToString(ListSerializer(CatalogPreset.serializer()), list)).apply()
    }
}

/** Check-box drop-down for formats (none = 전체). */
@Composable
fun FormatFilterChip(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = selected.isNotEmpty(), onClick = { open = true }, label = { Text(CatalogFormats.label(selected) + " ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("전체") },
                leadingIcon = { Checkbox(checked = selected.isEmpty(), onCheckedChange = null) },
                onClick = { onChange(emptySet()) },
            )
            HorizontalDivider()
            CatalogFormats.ALL.forEach { f ->
                DropdownMenuItem(
                    text = { Text(f.label) },
                    leadingIcon = { Checkbox(checked = f.key in selected, onCheckedChange = null) },
                    onClick = { onChange(if (f.key in selected) selected - f.key else selected + f.key) },
                )
            }
            DropdownMenuItem(text = { Text("완료", color = MaterialTheme.colorScheme.primary) }, onClick = { open = false })
        }
    }
}

/** Preset drop-down: pick a saved combination, or save the one on screen. */
@Composable
fun PresetChip(
    presets: List<CatalogPreset>,
    libs: Set<Int>,
    formats: Set<String>,
    onPick: (CatalogPreset?) -> Unit,
    onSaveCurrent: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = presets.firstOrNull { it.matches(libs, formats) }
    Box {
        FilterChip(
            selected = current != null,
            onClick = { open = true },
            label = { Text("★ " + (current?.name ?: "프리셋") + " ▾") },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text((if (libs.isEmpty() && formats.isEmpty()) "✓ " else "    ") + "모두 보기") },
                onClick = { open = false; onPick(null) },
            )
            presets.forEach { p ->
                DropdownMenuItem(
                    text = { Text((if (p === current) "✓ " else "    ") + p.name) },
                    onClick = { open = false; onPick(p) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("지금 조합을 프리셋으로 저장…", color = MaterialTheme.colorScheme.primary) },
                onClick = { open = false; onSaveCurrent() },
            )
        }
    }
}

/** Name a preset (and, in the server settings, choose its categories and formats). */
@Composable
fun PresetDialog(
    initial: CatalogPreset,
    libraries: List<MetaLibrary>?,       // null: only the name is edited
    onSave: (CatalogPreset) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var libs by remember { mutableStateOf(initial.libraries.toSet()) }
    var fmts by remember { mutableStateOf(initial.formats.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isEmpty()) "새 프리셋" else "프리셋 편집") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("이름 (예: 만화)") }, modifier = Modifier.fillMaxWidth(),
                )
                if (libraries != null) {
                    Text("카테고리 (안 고르면 전체)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    libraries.forEach { l -> CheckRow(l.name, l.id in libs) { libs = if (l.id in libs) libs - l.id else libs + l.id } }
                    Text("형식 (안 고르면 전체)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    CatalogFormats.ALL.forEach { f -> CheckRow(f.label, f.key in fmts) { fmts = if (f.key in fmts) fmts - f.key else fmts + f.key } }
                    Text(
                        "카테고리와 형식은 함께 적용됩니다 (둘 다 맞는 것만).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(CatalogPreset(name.trim(), libs.sorted(), CatalogFormats.ALL.map { it.key }.filter { it in fmts }))
            }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("지우기", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Server settings: the catalog's presets (needs the meta API for the category names). */
@Composable
fun PresetSettings(server: Server) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var presets by remember(server.id) { mutableStateOf(CatalogPresets.load(context, server.id)) }
    var libraries by remember(server.id) { mutableStateOf<List<MetaLibrary>?>(null) }
    var libError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }   // index, or -1 for a new one
    LaunchedEffect(server) {
        libraries = try { MetaApi(server).libraries() } catch (e: Exception) { libError = e.message; null }
    }
    fun store(list: List<CatalogPreset>) { presets = list; CatalogPresets.save(context, server.id, list) }
    val names = libraries.orEmpty().associate { it.id to it.name }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("보기 프리셋", style = MaterialTheme.typography.titleSmall)
        Text(
            "정리된 카탈로그(시리즈별 보기)에서 카테고리와 파일 형식의 조합을 한 번에 고릅니다. 예: 만화 = 만화 카테고리들 + CBZ·ZIP",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        presets.forEachIndexed { i, p ->
            Column(Modifier.fillMaxWidth().clickable { editing = i }.padding(vertical = 8.dp)) {
                Text("★ ${p.name}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOf(
                        if (p.libraries.isEmpty()) "카테고리 전체" else p.libraries.joinToString(", ") { names[it] ?: "#$it" },
                        CatalogFormats.label(p.formats.toSet()),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            libraries != null -> OutlinedButton(onClick = { editing = -1 }) { Text("➕ 프리셋 추가") }
            libError != null -> Text("메타 API에 연결하지 못해 카테고리를 불러올 수 없습니다: $libError", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else -> Text("카테고리를 불러오는 중…", style = MaterialTheme.typography.bodySmall)
        }
    }
    editing?.let { i ->
        PresetDialog(
            initial = presets.getOrNull(i) ?: CatalogPreset(""),
            libraries = libraries.orEmpty(),
            onSave = { p -> store(if (i in presets.indices) presets.toMutableList().also { it[i] = p } else presets + p); editing = null },
            onDelete = if (i in presets.indices) ({ store(presets.filterIndexed { k, _ -> k != i }); editing = null }) else null,
            onDismiss = { editing = null },
        )
    }
}
