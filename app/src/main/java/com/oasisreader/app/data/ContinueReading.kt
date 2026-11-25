package com.oasisreader.app.data

import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.ShelfSearch

/**
 * 이어 읽기 (TODO 65): in a list of volumes, the one to open to carry on reading.
 * The volumes are put in natural order ("2" before "11"); the one read most recently is the
 * starting point, and when that one is finished the next volume is offered instead.
 * Nothing has been read yet → null (no button).
 */
object ContinueReading {
    private const val DONE = 0.98f

    fun finished(b: BookEntity): Boolean = b.finishedAt != null || b.progress >= DONE

    /** [book] gives the 서재 entry of an item (null = not on the 서재, so never read here). */
    fun <T> pick(items: List<T>, title: (T) -> String, book: (T) -> BookEntity?): T? {
        if (items.size < 2) return null
        val ordered = items.sortedWith { a, b -> ShelfSearch.naturalCompare(title(a), title(b)) }
        var last = -1
        var lastAt = Long.MIN_VALUE
        ordered.forEachIndexed { i, item ->
            val at = book(item)?.lastReadAt ?: return@forEachIndexed
            if (at > lastAt) { lastAt = at; last = i }
        }
        if (last < 0) return null
        val done = book(ordered[last])?.let(::finished) == true
        return if (done) ordered.getOrNull(last + 1) ?: ordered[last] else ordered[last]
    }
}

/** 여기까지 / 여기부터 받기 (TODO 66): the part of a list, in the order shown, up to or from one item. */
object VolumeRange {
    fun <T> slice(ordered: List<T>, index: Int, toEnd: Boolean): List<T> =
        if (index !in ordered.indices) emptyList()
        else if (toEnd) ordered.subList(index, ordered.size)
        else ordered.subList(0, index + 1)
}
