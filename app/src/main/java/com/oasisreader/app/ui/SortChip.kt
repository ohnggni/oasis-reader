package com.oasisreader.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** How a sort criterion reads in each direction: dates "최근 것부터", names "가나다순" … */
enum class SortKind(val asc: String, val desc: String) {
    DATE("오래된 것부터", "최근 것부터"),
    TEXT("가나다순", "가나다 역순"),
    AMOUNT("적은 것부터", "많은 것부터"),
    ORDER("처음부터", "거꾸로"),
}

/**
 * One drop-down for sorting: the criterion on top, the direction (🔼 오름차순 / 🔽 내림차순) below.
 * Picking a criterion starts it in its usual direction ([defaultDesc]); the chip shows both,
 * with [short] (a shorter name) when the full one would widen the row.
 */
@Composable
fun <T> SortChip(
    options: List<T>,
    selected: T,
    desc: Boolean,
    label: (T) -> String,
    kind: (T) -> SortKind,
    defaultDesc: (T) -> Boolean,
    onChange: (T, Boolean) -> Unit,
    enabled: (T) -> Boolean = { true },
    short: (T) -> String = label,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        MiniChip("${if (desc) "🔽" else "🔼"} ${short(selected)}", onClick = { open = true }, description = "정렬: ${label(selected)}")
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("정렬 기준", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(label(o)) },
                    leadingIcon = { CheckMark(o == selected) },
                    enabled = enabled(o),
                    onClick = { open = false; onChange(o, if (o == selected) desc else defaultDesc(o)) },
                )
            }
            HorizontalDivider()
            Text("순서", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            val k = kind(selected)
            DropdownMenuItem(
                text = { Text("🔼 오름차순 · ${k.asc}") },
                leadingIcon = { CheckMark(!desc) },
                onClick = { open = false; onChange(selected, false) },
            )
            DropdownMenuItem(
                text = { Text("🔽 내림차순 · ${k.desc}") },
                leadingIcon = { CheckMark(desc) },
                onClick = { open = false; onChange(selected, true) },
            )
        }
    }
}
