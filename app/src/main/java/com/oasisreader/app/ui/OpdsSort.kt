package com.oasisreader.app.ui

import com.oasisreader.app.data.ShelfSearch
import androidx.compose.foundation.layout.size
import com.oasisreader.app.data.OpdsEntry

enum class CatalogSort(val label: String, val kind: SortKind, val defaultDesc: Boolean) {
    SERVER("서버 순서", SortKind.ORDER, false),
    DATE("날짜", SortKind.DATE, true),
    TITLE("제목", SortKind.TEXT, false),
    AUTHOR("저자", SortKind.TEXT, false),
}

private val collator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)

/** False when entries carry no dates, all the same date, or only "right now" (request time). */
internal fun datesMeaningful(all: List<OpdsEntry>): Boolean {
    val ds = all.mapNotNull { epoch(it.updated) }
    if (ds.size < 2) return false
    if (ds.toSet().size == 1) return false
    val now = System.currentTimeMillis()
    return ds.any { now - it > 10 * 60_000L }
}

/** Sortable instant for an OPDS date (ISO-8601, date-only, or anything else = null). */
private fun epoch(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(s.take(19)).toEpochSecond(java.time.ZoneOffset.UTC) * 1000 }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(s.take(10)).toEpochDay() * 86_400_000L }.getOrNull()
}

internal fun shortDate(s: String): String? = epoch(s)?.let {
    val d = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    "${d.year}.${d.monthValue}.${d.dayOfMonth}"
}

/**
 * Folders and books are sorted the same way (bookoasis keeps each book in its own folder),
 * folders first. Missing dates go last; folders have no author, so they fall back to the title.
 */
internal fun sortEntries(all: List<OpdsEntry>, sort: CatalogSort, desc: Boolean): List<OpdsEntry> {
    val (books, folders) = all.partition { it.isBook }
    if (sort == CatalogSort.SERVER) return if (desc) folders.reversed() + books.reversed() else all
    // natural order: "A2" before "A11" (digit runs compare as numbers)
    val byTitle = Comparator<OpdsEntry> { a, b -> ShelfSearch.naturalCompare(a.title, b.title) }
    val asc: Comparator<OpdsEntry> = when (sort) {
        CatalogSort.DATE -> compareBy<OpdsEntry> { epoch(it.updated) ?: 0L }.then(byTitle)
        CatalogSort.AUTHOR -> Comparator { a, b ->
            val c = collator.compare(a.author ?: "\uFFFF", b.author ?: "\uFFFF")
            if (c != 0) c else ShelfSearch.naturalCompare(a.title, b.title)
        }
        else -> byTitle
    }
    val cmp = if (desc) asc.reversed() else asc
    // entries without a date go last either way
    fun sorted(l: List<OpdsEntry>) = if (sort != CatalogSort.DATE) l.sortedWith(cmp)
        else l.partition { epoch(it.updated) != null }.let { (dated, rest) -> dated.sortedWith(cmp) + rest.sortedWith(byTitle) }
    return sorted(folders) + sorted(books)
}
