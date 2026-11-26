package com.oasisreader.app.ui

import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.Server
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import android.content.Context
import com.oasisreader.app.data.ShelfCollections
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.oasisreader.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oasisreader.app.data.ContinueReading
import com.oasisreader.app.data.Downloads
import com.oasisreader.app.data.VolumeRange
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.OpdsClient
import com.oasisreader.app.data.OpdsEntry
import kotlinx.coroutines.launch

/**
 * ⬇️ 모두 담기 · 범위 받기 · ▶️ 이어 읽기 of the folder on screen. A new one on each composition,
 * so it holds that composition's 서재 list ([byOpds]).
 */
internal class OpdsFolderActions(
    private val context: Context,
    private val app: Context,
    private val server: Server,
    private val client: OpdsClient,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val path: OpdsPath,
    private val extra: SnapshotStateList<OpdsEntry>,
    nextUrlState: MutableState<String?>, gatheringAllState: MutableState<Boolean>,
    askFolderState: MutableState<Triple<String, List<OpdsEntry>, String?>?>, detailState: MutableState<OpdsEntry?>,
    private val byOpds: Map<String, BookEntity>,
    private val onOpenBook: (String) -> Unit,
) {
    private var nextUrl by nextUrlState
    private var gatheringAll by gatheringAllState
    private var askFolder by askFolderState
    private var detail by detailState

    fun keepFolder(name: String, list: List<OpdsEntry>, collection: Boolean) {
        // one question for the whole folder (103), none when every book is on the 서재 already
        if (list.any { it.id !in byOpds }) WifiAsk.run(app) { mobile -> receiveFolder(name, list, collection, mobile) }
        else receiveFolder(name, list, collection, false)
    }
    private fun receiveFolder(name: String, list: List<OpdsEntry>, collection: Boolean, mobile: Boolean) {
        scope.launch {
            val col = if (collection) ShelfCollections.findOrCreate(db, name) else null
            var n = 0
            list.forEach { e ->
                val already = db.books().findBySource(e.id, null)
                if (already != null) {
                    if (!already.pinned) db.books().setPinned(already.id, true)
                    col?.let { ShelfCollections.set(db, it.id, already.id, true) }
                } else {
                    n++
                    Downloads.start(app, server, e, collectionId = col?.id, origin = path.here(), mobile = mobile)
                }
            }
            android.widget.Toast.makeText(
                context, (if (n > 0) "${n}권을 받습니다" else "모두 서재에 있습니다") + (col?.let { " · 📚 ${it.name}에 묶습니다" } ?: ""),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    /** Every book of this folder (all its pages), then ask about the 컬렉션. */
    fun downloadFolder(ready: FeedState.Ready) {
        if (gatheringAll) return
        gatheringAll = true
        scope.launch {
            try {
                var next = nextUrl
                var pages = 0
                while (next != null && pages < MAX_SORT_PAGES) {
                    val f = client.fetch(next)
                    extra.addAll(f.entries); pages++
                    next = f.next
                }
                nextUrl = next
                val books = (ready.feed.entries + extra).filter { it.isBook && it.readable != null }.distinctBy { it.id }
                askFolder = Triple(path.labels.last(), books, null)
            } catch (e: Exception) {
                com.oasisreader.app.ui.Notice.show(context, "목록 전체를 받지 못했습니다: ${e.message}")
            } finally { gatheringAll = false }
        }
    }
    /** The rest of this folder's pages (bounded), into [extra]. */
    private suspend fun gatherRest() {
        var next = nextUrl
        var pages = 0
        while (next != null && pages < MAX_SORT_PAGES) {
            val f = client.fetch(next)
            extra.addAll(f.entries); pages++
            next = f.next
        }
        nextUrl = next
    }
    /** 여기까지 / 여기부터 받기 (TODO 66): [order] is the list as shown, so "1권부터 3권까지" is what the eye sees. */
    fun downloadRange(ready: FeedState.Ready, from: OpdsEntry, toEnd: Boolean, order: (List<OpdsEntry>) -> List<OpdsEntry>) {
        if (gatheringAll) return
        gatheringAll = true
        scope.launch {
            try {
                if (toEnd) gatherRest()
                val ordered = order((ready.feed.entries + extra).distinctBy { it.id })
                val i = ordered.indexOfFirst { it.id == from.id }
                val part = VolumeRange.slice(ordered, i, toEnd).filter { it.isBook && it.readable != null }
                if (part.isEmpty()) return@launch
                askFolder = Triple(path.labels.last(), part, if (toEnd) "「${from.title}」부터 끝까지" else "처음부터 「${from.title}」까지")
            } catch (e: Exception) {
                com.oasisreader.app.ui.Notice.show(context, "목록 전체를 받지 못했습니다: ${e.message}")
            } finally { gatheringAll = false }
        }
    }
    /** 이어 읽기 (TODO 65): the volume read last (or the next, when it is finished) — the whole folder is looked at. */
    fun continueReading(ready: FeedState.Ready) {
        if (gatheringAll) return
        gatheringAll = true
        scope.launch {
            try {
                gatherRest()
                val books = (ready.feed.entries + extra).filter { it.isBook && it.readable != null }.distinctBy { it.id }
                val target = ContinueReading.pick(books, { it.title }) { byOpds[it.id] }
                val onShelf = target?.let { byOpds[it.id] }
                when {
                    target == null -> android.widget.Toast.makeText(context, "이 폴더에서 읽은 책이 없습니다", android.widget.Toast.LENGTH_SHORT).show()
                    onShelf != null -> onOpenBook(onShelf.id)
                    else -> detail = target   // the next volume is not on the 서재 yet: 바로 읽기 / 담기
                }
            } catch (e: Exception) {
                com.oasisreader.app.ui.Notice.show(context, "목록 전체를 받지 못했습니다: ${e.message}")
            } finally { gatheringAll = false }
        }
    }
}

/** "컬렉션으로 묶을까요?" before a folder (or a range of it) is received. */
@Composable
internal fun OpdsAskFolderDialog(
    askFolderState: MutableState<Triple<String, List<OpdsEntry>, String?>?>, ownedSet: Set<String>, folder: OpdsFolderActions,
) {
    var askFolder by askFolderState
    askFolder?.let { (name, list, range) ->
        val missing = list.count { it.id !in ownedSet }
        AlertDialog(
            onDismissRequest = { askFolder = null },
            title = { EmojiText(if (range == null) "⬇️ 「$name」 모두 담기" else "⬇️ $range 담기") },
            text = {
                Text(
                    (if (range == null) "이 폴더의 책 ${list.size}권" else "$range ${list.size}권") + " 중 서재에 없는 ${missing}권을 받습니다.\n" +
                        "같은 이름의 📚 컬렉션으로 묶을까요? (이미 서재에 있는 이 폴더 책도 함께 묶습니다)",
                )
            },
            confirmButton = { TextButton(onClick = { askFolder = null; folder.keepFolder(name, list, true) }) { Text("묶어서 받기") } },
            dismissButton = { TextButton(onClick = { askFolder = null; folder.keepFolder(name, list, false) }) { Text("그냥 받기") } },
        )
    }
}

/** Long press on a book: 여기까지 / 여기부터 받기. */
@Composable
internal fun OpdsRangeDialog(
    rangeMenuState: MutableState<OpdsEntry?>, stateState: State<FeedState>, sortState: State<CatalogSort>,
    sortDescState: State<Boolean>, folder: OpdsFolderActions,
) {
    var rangeMenu by rangeMenuState
    val state by stateState
    val sort by sortState
    val sortDesc by sortDescState
    rangeMenu?.let { e ->
        val ready = state as? FeedState.Ready
        AlertDialog(
            onDismissRequest = { rangeMenu = null },
            title = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = {
                        rangeMenu = null
                        if (ready != null) folder.downloadRange(ready, e, false) { l -> sortEntries(l, if (!datesMeaningful(l) && sort == CatalogSort.DATE) CatalogSort.SERVER else sort, sortDesc) }
                    }) { EmojiText("⬇️ 처음부터 여기까지 받기") }
                    TextButton(onClick = {
                        rangeMenu = null
                        if (ready != null) folder.downloadRange(ready, e, true) { l -> sortEntries(l, if (!datesMeaningful(l) && sort == CatalogSort.DATE) CatalogSort.SERVER else sort, sortDesc) }
                    }) { EmojiText("⬇️ 여기부터 끝까지 받기") }
                    Text(
                        "지금 화면에 보이는 순서 기준입니다. 이미 서재에 있는 책은 건너뜁니다.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { rangeMenu = null }) { Text("닫기") } },
        )
    }
}
