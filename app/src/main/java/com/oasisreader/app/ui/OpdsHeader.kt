package com.oasisreader.app.ui

import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.MutableState
import android.content.Context
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.data.OpdsFeed
import kotlinx.coroutines.launch

/** Above the list: the top bar, where we are, ⭐ 즐겨찾기 and the search field. */
@Composable
internal fun OpdsHeader(
    app: Context, context: Context, client: OpdsClient, scope: CoroutineScope, serverId: String, serverName: String,
    path: OpdsPath, isFav: Boolean, favoritesState: MutableState<List<OpdsFavorites.Place>>, lastPlace: OpdsFavorites.Place?,
    editFavoritesState: MutableState<Boolean>, rootSearch: OpdsFeed?, queryState: MutableState<String>,
    onBack: () -> Unit, onOpenSettings: () -> Unit, onOrganized: (() -> Unit)?,
) {
    val stack = path.stack
    val labels = path.labels
    var favorites by favoritesState
    var editFavorites by editFavoritesState
    var query by queryState
    // the folder the user opened, by the name they tapped (feed titles like "Library Series" say little)
    TopBar(if (stack.size == 1) serverName else "📂 " + labels.last(), onBack = onBack) {
        HomeButton()
        if (stack.size > 1) androidx.compose.material3.IconButton(
            onClick = {
                val added = OpdsFavorites.toggle(app, serverId, stack.toList(), labels.toList())
                favorites = OpdsFavorites.all(app, serverId)
                android.widget.Toast.makeText(context, if (added) "⭐ 즐겨찾기에 넣었습니다" else "즐겨찾기에서 뺐습니다", android.widget.Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.semantics { contentDescription = if (isFav) "즐겨찾기에서 빼기" else "이 폴더 즐겨찾기" },
        ) { Text("⭐", style = MaterialTheme.typography.titleMedium, modifier = Modifier.alpha(if (isFav) 1f else 0.3f)) }
        EmojiButton("⚙️", "연결 설정", onOpenSettings)
    }
    if (onOrganized != null) com.oasisreader.app.ui.CatalogViewSwitch(organized = false, onOrganized = onOrganized, onFolders = {})
    // where am I: server › folder › folder (tap a part to go back there)
    if (stack.size > 1) Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, l ->
            if (i > 0) Text("  ›  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = i == labels.size - 1
            Text(
                (if (i == 0) "🏠 " else "") + l,
                style = MaterialTheme.typography.labelLarge,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = if (last) Modifier else Modifier.clickable { path.popTo(i) },
            )
        }
    }

    // the server's first page: ⭐ favourites and the last folder, one tap away
    if (stack.size == 1 && (favorites.isNotEmpty() || lastPlace != null)) Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(MiniChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lastPlace?.takeIf { lp -> favorites.none { it.urls.lastOrNull() == lp.urls.lastOrNull() } }?.let { lp ->
            MiniChip("🕘 ${lp.name}", onClick = { path.goTo(lp) }, description = "지난번 본 폴더")
        }
        favorites.forEach { f -> MiniChip("⭐ ${f.name}", onClick = { path.goTo(f) }) }
        if (favorites.isNotEmpty()) MiniChip("✏️", onClick = { editFavorites = true }, description = "즐겨찾기 편집", plain = true)
    }

    rootSearch?.let { sf ->
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        com.oasisreader.app.ui.CompactSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "제목·저자 검색",
            onSearch = {
                keyboard?.hide(); focus.clearFocus()
                val q = query.trim()
                if (q.isNotEmpty()) scope.launch {
                    val u = runCatching { client.searchUrl(sf, q) }.getOrNull()
                    if (u != null) path.open(u, "검색: $q")
                    else android.widget.Toast.makeText(context, "검색 주소를 찾지 못했습니다", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        )
    }
}
