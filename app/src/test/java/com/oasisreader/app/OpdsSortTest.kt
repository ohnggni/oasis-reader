package com.oasisreader.app

import com.oasisreader.app.data.Acquisition
import com.oasisreader.app.data.OpdsEntry
import com.oasisreader.app.ui.CatalogSort
import com.oasisreader.app.ui.datesMeaningful
import com.oasisreader.app.ui.sortEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPDS 목록 정렬: 폴더가 먼저, 날짜 없는 것은 맨 뒤, 숫자는 숫자로. */
class OpdsSortTest {
    private fun book(title: String, author: String? = null, updated: String? = null) =
        OpdsEntry(title, title, author = author, acquisitions = listOf(Acquisition("x.epub", "application/epub+zip")), updated = updated)
    private fun folder(title: String, updated: String? = null) = OpdsEntry(title, title, navHref = "n", updated = updated)

    private val list = listOf(
        book("A11", "나", "2024-01-02T00:00:00Z"),
        folder("F2"),
        book("A2", "가", "2024-03-01"),
        book("B", null),
        folder("F1", "2023-05-05T10:00:00+09:00"),
    )

    private fun sorted(s: CatalogSort, desc: Boolean) = sortEntries(list, s, desc).map { it.title }

    @Test fun serverOrder() {
        assertEquals(listOf("A11", "F2", "A2", "B", "F1"), sorted(CatalogSort.SERVER, false))
        assertEquals(listOf("F1", "F2", "B", "A2", "A11"), sorted(CatalogSort.SERVER, true))
    }

    @Test fun byTitle() {
        assertEquals(listOf("F1", "F2", "A2", "A11", "B"), sorted(CatalogSort.TITLE, false))
        assertEquals(listOf("F2", "F1", "B", "A11", "A2"), sorted(CatalogSort.TITLE, true))
    }

    @Test fun byDateUndatedLast() {
        assertEquals(listOf("F1", "F2", "A11", "A2", "B"), sorted(CatalogSort.DATE, false))
        assertEquals(listOf("F1", "F2", "A2", "A11", "B"), sorted(CatalogSort.DATE, true))
    }

    @Test fun byAuthorMissingAfter() {
        assertEquals(listOf("F1", "F2", "A2", "A11", "B"), sorted(CatalogSort.AUTHOR, false))
    }

    @Test fun datesThatSayNothing() {
        assertTrue(datesMeaningful(list))
        assertFalse(datesMeaningful(listOf(book("a", updated = "2024-01-01"))))
        assertFalse(datesMeaningful(listOf(book("a", updated = "2024-01-01"), book("b", updated = "2024-01-01"))))
        val now = java.time.Instant.now()
        assertFalse(datesMeaningful(listOf(book("a", updated = now.toString()), book("b", updated = now.minusSeconds(5).toString()))))
    }
}
