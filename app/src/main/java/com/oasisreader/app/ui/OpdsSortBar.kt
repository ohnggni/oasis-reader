package com.oasisreader.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SortBar(
    sort: CatalogSort, desc: Boolean, loading: Int?, datesUseful: Boolean, onSort: (CatalogSort, Boolean) -> Unit,
    grid: Boolean, onGrid: (Boolean) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(MiniChipGap),
        ) {
            // same switch as in the library
            MiniChip(
                if (grid) "🔲 표지" else "📃 목록", onClick = { onGrid(!grid) }, plain = true,
                description = if (grid) "표지로 보는 중 (누르면 목록)" else "목록으로 보는 중 (누르면 표지)",
            )
            SortChip(
                options = CatalogSort.entries, selected = sort, desc = desc,
                label = { it.label }, kind = { it.kind }, defaultDesc = { it.defaultDesc },
                onChange = onSort,
                enabled = { datesUseful || it != CatalogSort.DATE },
            )
        }
        if (!datesUseful) {
            Text(
                "이 목록에는 항목별 날짜가 없어 날짜 정렬은 쓸 수 없습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (loading != null) {
            Text(
                "정렬하려고 목록 전체를 받는 중… ($loading)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
    }
}
