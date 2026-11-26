package com.oasisreader.app.meta

import com.oasisreader.app.data.BookStore
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import android.content.Context
import com.oasisreader.app.data.MetaSeries
import com.oasisreader.app.data.MetaBook
import com.oasisreader.app.data.MetaApi
import com.oasisreader.app.ui.EmojiText
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.oasisreader.app.data.Downloads
import com.oasisreader.app.data.Server
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.OpdsClient
import kotlinx.coroutines.launch

/**
 * Fills the catalog list, a page at a time. MetaCatalogScreen owns (remembers) every state and
 * hands them in; a new one of these is made on each composition.
 */
internal class MetaPageLoader(
    private val api: MetaApi,
    private val libParam: String?,
    private val fmtParam: String?,
    private val seriesItems: SnapshotStateList<MetaSeries>,
    private val bookItems: SnapshotStateList<MetaBook>,
    libIdsState: State<Set<Int>>, fmtKeysState: State<Set<String>>, sortState: State<SeriesSort>,
    sortDescState: State<Boolean>, activeQueryState: State<String>, searchBooksState: State<Boolean>,
    pageState: MutableIntState, hasMoreState: MutableState<Boolean>, totalState: MutableState<Int?>,
    totalBooksState: MutableState<Int?>, loadingState: MutableState<Boolean>, errorState: MutableState<String?>,
) {
    private val libIds by libIdsState
    private val fmtKeys by fmtKeysState
    private val sort by sortState
    private val sortDesc by sortDescState
    private val activeQuery by activeQueryState
    private val searchBooks by searchBooksState
    private var page by pageState
    private var hasMore by hasMoreState
    private var total by totalState
    private var totalBooks by totalBooksState
    private var loading by loadingState
    private var error by errorState

    suspend fun loadPage(p: Int) {
        loading = true; error = null
        try {
            if (activeQuery.isNotBlank() && searchBooks) {
                val r = api.books(libParam, null, activeQuery, "mtime_desc", p, limit = 100, format = fmtParam)
                if (p == 1) { bookItems.clear(); total = r.total; totalBooks = r.total }
                bookItems.addAll(r.items); hasMore = r.hasMore
            } else {
                val r = api.series(libParam, activeQuery.ifBlank { null }, sort.api(sortDesc), p, format = fmtParam)
                if (p == 1) { seriesItems.clear(); total = r.total; totalBooks = r.totalBooks }
                seriesItems.addAll(r.items); hasMore = r.hasMore
            }
            page = p
        } catch (e: Exception) {
            error = (e.message ?: e.javaClass.simpleName) +
                (if (libIds.size > 1 || fmtKeys.isNotEmpty()) "\n(여러 카테고리·형식 필터는 서버의 oasis-meta 최신 버전이 필요합니다)" else "")
        } finally {
            loading = false
        }
    }
}

/**
 * 바로 읽기 · 서재에 담기 from the catalog. A new one on each composition, so it holds that
 * composition's 서재 sets.
 */
internal class MetaShelfActions(
    private val context: Context,
    private val app: Context,
    private val server: Server,
    private val api: MetaApi,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val store: BookStore,
    private val ownedSet: Set<String>,
    private val pinnedSet: Set<String>,
    private val onOpenBook: (String) -> Unit,
) {
    /** 바로 읽기: fetch into the cache and open (or just open). */
    fun readNow(b: MetaBook) {
        val href = api.downloadUrl(b) ?: return
        if (href in ownedSet) scope.launch { db.books().findBySource(null, href)?.let { onOpenBook(it.id) } }
        else Downloads.startUrl(app, server, api.key(b), href, b.name, pinned = false, onDone = { onOpenBook(it.id) }, origin = com.oasisreader.app.data.BookOrigin.meta(server.id, b.seriesName, b.libraryId))
    }

    /** 서재에 담기 / 보관: keep for good (a cached book with its file still here is just marked). */
    fun keep(b: MetaBook, quiet: Boolean = false, collectionId: String? = null) {
        val href = api.downloadUrl(b) ?: return
        if (href in ownedSet) scope.launch {
            val book = db.books().findBySource(null, href) ?: return@launch
            if (store.hasFiles(book.id)) {
                db.books().setPinned(book.id, true)
                if (!quiet) android.widget.Toast.makeText(context, "서재에 보관합니다: ${book.title}", android.widget.Toast.LENGTH_SHORT).show()
                collectionId?.let { com.oasisreader.app.data.ShelfCollections.set(db, it, book.id, true) }
            } else Downloads.startUrl(app, server, api.key(b), href, b.name, collectionId = collectionId, origin = com.oasisreader.app.data.BookOrigin.meta(server.id, b.seriesName, b.libraryId))
        } else Downloads.startUrl(app, server, api.key(b), href, b.name, collectionId = collectionId, origin = com.oasisreader.app.data.BookOrigin.meta(server.id, b.seriesName, b.libraryId))
    }

    /**
     * A whole series to the 서재: the missing books are kept, and with [collection] every book
     * of the series on the 서재 (kept now or before) goes on the 컬렉션 of that name.
     */
    fun keepSeries(list: List<MetaBook>, collection: String?) {
        scope.launch {
            val col = collection?.let { com.oasisreader.app.data.ShelfCollections.findOrCreate(db, it) }
            var n = 0
            list.forEach { b ->
                val href = api.downloadUrl(b) ?: return@forEach
                if (!b.readable) return@forEach
                if (href !in pinnedSet) { keep(b, quiet = true, collectionId = col?.id); n++ }
                else if (col != null) db.books().findBySource(null, href)?.let { com.oasisreader.app.data.ShelfCollections.set(db, col.id, it.id, true) }
            }
            android.widget.Toast.makeText(
                context,
                (if (n > 0) "${n}권을 받습니다" else "모두 서재에 있습니다") + (col?.let { " · 📚 ${it.name}에 묶습니다" } ?: ""),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun openOrDownload(b: MetaBook) {
        val href = api.downloadUrl(b) ?: return
        if (href in ownedSet) {
            scope.launch { db.books().findBySource(null, href)?.let { onOpenBook(it.id) } }
        } else {
            Downloads.startUrl(app, server, api.key(b), href, b.name, origin = com.oasisreader.app.data.BookOrigin.meta(server.id, b.seriesName, b.libraryId))
        }
    }
}

/** "컬렉션으로 묶을까요?" before a whole series (or a range of it) is received. */
@Composable
internal fun MetaAskSeriesDialog(askSeriesState: MutableState<Triple<String, List<MetaBook>, String?>?>, actions: MetaShelfActions) {
    var askSeries by askSeriesState
    askSeries?.let { (name, list, range) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askSeries = null },
            title = { EmojiText("📚 컬렉션으로 묶을까요?") },
            text = {
                Text(
                    if (range == null) "「$name」 시리즈의 책을 서재에 담으면서 같은 이름의 컬렉션으로 묶습니다. 이미 서재에 있는 이 시리즈의 책도 함께 묶습니다."
                    else "「$name」 시리즈의 $range ${list.size}권을 서재에 담으면서 같은 이름의 컬렉션으로 묶습니다. 이미 서재에 있는 같은 범위의 책도 함께 묶습니다.",
                )
            },
            confirmButton = { TextButton(onClick = { askSeries = null; actions.keepSeries(list, name) }) { Text("묶어서 받기") } },
            dismissButton = { TextButton(onClick = { askSeries = null; actions.keepSeries(list, null) }) { Text("그냥 받기") } },
        )
    }
}

/** One volume's details over the list, while one is open. */
@Composable
internal fun MetaBookOverlay(
    openBookState: MutableState<MetaBook?>, api: MetaApi, opds: OpdsClient, ownedSet: Set<String>, pinnedSet: Set<String>,
    progress: Map<String, Float>, libNames: Map<Int, String>, actions: MetaShelfActions,
) {
    var openBook by openBookState
    openBook?.let { b ->
        BookDetail(
            b, api, opds, owned = api.downloadUrl(b) in ownedSet, pinned = api.downloadUrl(b) in pinnedSet,
            progress = progress[api.key(b)],
            library = b.libraryId?.let { libNames[it] },
            onBack = { openBook = null },
            onRead = { actions.readNow(b) },
            onKeep = { actions.keep(b) },
        )
    }
}
