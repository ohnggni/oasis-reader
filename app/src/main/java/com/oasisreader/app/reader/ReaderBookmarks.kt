package com.oasisreader.app.reader

import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.runtime.Composable
import com.oasisreader.app.data.KoSync
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookmarkEntity
import com.oasisreader.app.epub.EpubPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 91: a bookmark in / out at the place on screen ([here] is the one already there, if any). */
internal fun toggleBookmarkHere(
    here: BookmarkEntity?, bookId: String, curIndex: Int, curFraction: Float,
    db: AppDatabase, scope: CoroutineScope, context: Context, webView: WebView?,
) {
    val m = here
    if (m != null) {
        scope.launch { db.bookmarks().delete(m.id) }
        Toast.makeText(context, "책갈피를 지웠습니다.", Toast.LENGTH_SHORT).show()
        return
    }
    val idx = curIndex; val f = curFraction
    fun save(snippet: String) {
        scope.launch {
            db.bookmarks().insert(BookmarkEntity(
                id = System.currentTimeMillis().toString(36) + (Math.random() * 1e6).toLong().toString(36),
                bookId = bookId, spineIndex = idx, fraction = f, snippet = snippet, createdAt = System.currentTimeMillis(),
            ))
        }
        Toast.makeText(context, "책갈피를 저장했습니다.", Toast.LENGTH_SHORT).show()
    }
    val wv = webView
    if (wv == null) save("") else wv.evaluateJavascript("window.Reader && Reader.pageText()") { r ->
        save(runCatching { org.json.JSONTokener(r).nextValue() as? String }.getOrNull().orEmpty())
    }
}

/** 검색 and 책갈피 목록 pop-ups. */
@Composable
internal fun ReaderSearchMarkDialogs(
    findOpen: Boolean, findState: FindState, store: BookStore, bookId: String, pkg: EpubPackage?,
    onFindPick: (BookSearch.Hit, String) -> Unit, onFindDismiss: () -> Unit,
    marksOpen: Boolean, bookmarks: List<BookmarkEntity>,
    onMarkPick: (BookmarkEntity) -> Unit, onMarkDelete: (BookmarkEntity) -> Unit, onMarksDismiss: () -> Unit,
) {
    if (findOpen) pkg?.let { pp ->
        FindDialog(
            state = findState, store = store, bookId = bookId, pkg = pp,
            onPick = onFindPick,
            onDismiss = onFindDismiss,
        )
    }
    if (marksOpen) BookmarksDialog(
        list = bookmarks,
        where = { b ->
            val pp = pkg
            if (pp == null) "" else listOfNotNull(
                chapterTitleFor(pp, b.spineIndex).ifBlank { null },
                "${(pp.overall(b.spineIndex, b.fraction) * 100).roundToInt()}%",
            ).joinToString(" · ")
        },
        onPick = onMarkPick,
        onDelete = onMarkDelete,
        onDismiss = onMarksDismiss,
    )
}

/** bookoasis progress sync: offer another device's newer place, upload ours. */
@Composable
internal fun ReaderRemoteSync(
    bookId: String, document: String?, lastReadAt: Long?, ready: Boolean,
    curIndex: Int, curFraction: Float, pkg: EpubPackage?,
    remote: KoSync.Remote?, onRemote: (KoSync.Remote?) -> Unit, onGo: (Int, Float) -> Unit,
) {
    com.oasisreader.app.ui.ProgressSync(
        bookId = bookId,
        document = document,
        lastReadAt = lastReadAt,
        ready = ready,
        positionKey = curIndex to curFraction,
        place = { "/body/DocFragment[${curIndex + 1}]/body" to (pkg?.overall(curIndex, curFraction) ?: 0f) },
        differs = { r ->
            val pp = pkg
            val t = pp?.let { remoteTarget(it, r) }
            pp != null && t != null &&
                (t.first != curIndex || kotlin.math.abs(pp.overall(t.first, t.second) - pp.overall(curIndex, curFraction)) > 0.005f)
        },
        onRemote = { onRemote(it) },
    )
    remote?.let { r ->
        val pct = r.percentage?.let { "${(it * 100).roundToInt()}%" } ?: "다른 위치"
        com.oasisreader.app.ui.RemoteProgressDialog(
            where = "${com.oasisreader.app.ui.remoteDeviceLabel(r)}에서 ${pct}까지 읽었습니다.",
            onGo = {
                onRemote(null)
                pkg?.let { pp -> remoteTarget(pp, r)?.let { (i, f) -> onGo(i, f) } }
            },
            onStay = { onRemote(null) },
        )
    }
}
