package com.oasisreader.app.ui

import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.MutableState
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.data.OpdsEntry
import com.oasisreader.app.data.OpdsFeed
import kotlinx.coroutines.launch

internal sealed interface FeedState {
    data object Loading : FeedState
    data class Ready(val feed: OpdsFeed) : FeedState
    data class Failed(val message: String) : FeedState
}

/** Pages fetched at most for a client-side sort or a whole-folder download. */
internal const val MAX_SORT_PAGES = 40

/** Where the catalog is: the stack of pages and the folder name of each. OpdsScreen owns both lists. */
internal class OpdsPath(
    private val serverId: String,
    val stack: SnapshotStateList<String>,
    val labels: SnapshotStateList<String>,   // folder name shown for each stack level
) {
    /** This folder, as the 출처 of a book received here (다음 권 / 있던 폴더 열기). */
    fun here(): String? = com.oasisreader.app.data.BookOrigin.opds(serverId, stack.toList(), labels.toList())
    fun open(url: String, label: String) { stack.add(url); labels.add(label) }
    fun popTo(level: Int) { while (stack.size > level + 1) { stack.removeAt(stack.size - 1); labels.removeAt(labels.size - 1) } }
    fun goTo(p: OpdsFavorites.Place) {
        if (p.urls.isEmpty()) return
        stack.clear(); stack.addAll(p.urls); labels.clear(); labels.addAll(p.labels)
    }
}

/** The end of the list on screen: the next page comes by itself (a failed page waits for the button). */
internal class OpdsPaging(
    private val context: Context,
    private val client: OpdsClient,
    private val scope: CoroutineScope,
    private val extra: SnapshotStateList<OpdsEntry>,   // "more" pages appended
    nextUrlState: MutableState<String?>, loadingMoreState: MutableState<Boolean>, failedMoreState: MutableState<String?>,
) {
    private var nextUrl by nextUrlState
    var loadingMore by loadingMoreState
        private set
    var failedMore by failedMoreState
        private set

    fun loadMore(more: String) {
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            try {
                val f = client.fetch(more)
                extra.addAll(f.entries); nextUrl = f.next; failedMore = null
            } catch (e: Exception) {
                failedMore = more
                com.oasisreader.app.ui.Notice.show(context, e.message ?: "불러오기 실패")
            } finally { loadingMore = false }
        }
    }
}

@Composable
internal fun OpdsMoreRow(more: String, paging: OpdsPaging) {
    LaunchedEffect(more) { if (paging.failedMore != more) paging.loadMore(more) }
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        if (paging.loadingMore) CircularProgressIndicator(Modifier.size(24.dp))
        else TextButton(onClick = { paging.loadMore(more) }) { Text(if (paging.failedMore == more) "다시 불러오기" else "더 보기") }
    }
}
