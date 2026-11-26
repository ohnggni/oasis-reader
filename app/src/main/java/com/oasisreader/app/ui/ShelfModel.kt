package com.oasisreader.app.ui

import com.oasisreader.app.data.ShelfKind
import com.oasisreader.app.data.ShelfSearch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.oasisreader.app.audio.AudioLibraryLink
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.CollectionEntity

enum class ShelfFilter(val label: String) {
    ALL("전체"), READING("읽는 중"), UNREAD("안 읽음"), DONE("다 읽음");

    fun matches(b: BookEntity) = when (this) {
        ALL -> true
        READING -> b.finishedAt == null && b.lastReadAt != null
        UNREAD -> b.finishedAt == null && b.lastReadAt == null
        DONE -> b.finishedAt != null
    }
}

enum class ShelfSort(val label: String, val kind: SortKind, val defaultDesc: Boolean) {
    RECENT("읽은 날짜", SortKind.DATE, true),
    ADDED("추가한 날짜", SortKind.DATE, true),
    TITLE("제목", SortKind.TEXT, false),
    AUTHOR("저자", SortKind.TEXT, false),
    PROGRESS("진행률", SortKind.AMOUNT, true),
    KIND("종류", SortKind.ORDER, false);
}

internal val shelfCollator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)

/** Ascending order for each criterion; [desc] turns it around. Titles in natural order (2권 before 10권). */
internal fun sortShelf(list: List<BookEntity>, sort: ShelfSort, desc: Boolean, kinds: List<ShelfKind> = ShelfKind.entries): List<BookEntity> {
    val byTitle = Comparator<BookEntity> { a, b -> ShelfSearch.naturalCompare(a.title, b.title) }
    val asc: Comparator<BookEntity> = when (sort) {
        ShelfSort.RECENT -> compareBy<BookEntity> { it.lastReadAt ?: it.addedAt }
        ShelfSort.ADDED -> compareBy { it.addedAt }
        ShelfSort.TITLE -> byTitle
        ShelfSort.AUTHOR -> Comparator { a, b ->
            val c = shelfCollator.compare(a.author ?: "￿", b.author ?: "￿")
            if (c != 0) c else ShelfSearch.naturalCompare(a.title, b.title)
        }
        ShelfSort.PROGRESS -> compareBy<BookEntity> { if (it.finishedAt != null) 2f else it.progress }.then(byTitle)
        // the user's kind order; inside a kind, the most recently read first
        ShelfSort.KIND -> compareBy<BookEntity> { kinds.indexOf(ShelfKind.of(it)) }
    }
    if (sort == ShelfSort.KIND) {
        val recent = compareByDescending<BookEntity> { it.lastReadAt ?: it.addedAt }
        return list.sortedWith((if (desc) asc.reversed() else asc).then(recent))
    }
    return list.sortedWith(if (desc) asc.reversed() else asc)
}

/** One thing in the 서재 grid/list: a book, a 컬렉션 folder tile, or a 컬렉션 head (list, 컬렉션별). */
internal sealed interface ShelfEntry {
    val key: String

    data class Book(val b: BookEntity, val group: String? = null, override val key: String = b.id) : ShelfEntry
    data class Folder(val c: CollectionEntity, val books: List<BookEntity>) : ShelfEntry { override val key get() = "f_" + c.id }
    data class Head(val c: CollectionEntity, val books: List<BookEntity>, val open: Boolean) : ShelfEntry { override val key get() = "h_" + c.id }
}

internal fun ShelfEntry.bookIds(): List<String> = when (this) {
    is ShelfEntry.Book -> listOf(b.id)
    is ShelfEntry.Folder -> books.map { it.id }
    is ShelfEntry.Head -> books.map { it.id }
}

/** Kept on the phone (서재에 보관, works offline)? Reads the disk. */
internal fun isKept(b: BookEntity, store: BookStore): Boolean =
    b.pinned && if (b.format == AudioLibraryLink.FORMAT) b.fileBytes > 0
    else store.originalOf(b.id) != null || store.contentDir(b.id).isDirectory
