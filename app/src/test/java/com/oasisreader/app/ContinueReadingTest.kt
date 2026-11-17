package com.oasisreader.app

import com.oasisreader.app.data.ContinueReading
import com.oasisreader.app.data.VolumeRange
import com.oasisreader.app.data.db.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueReadingTest {
    private fun book(title: String, read: Long? = null, done: Boolean = false, progress: Float = 0f) =
        BookEntity(id = title, title = title, addedAt = 0, lastReadAt = read, finishedAt = if (done) 1L else null, progress = progress)

    private fun pick(vararg b: BookEntity, extra: List<String> = emptyList()): String? {
        val shelf = b.associateBy { it.title }
        val names = (b.map { it.title } + extra)
        return ContinueReading.pick(names, { it }) { shelf[it] }
    }

    @Test fun nothingReadGivesNothing() {
        assertNull(pick(book("1권"), book("2권")))
    }

    @Test fun lastReadVolumeIsOffered() {
        assertEquals("2권", pick(book("1권", read = 10, done = true), book("2권", read = 20, progress = 0.4f), book("3권")))
    }

    @Test fun finishedVolumeMovesToTheNext() {
        assertEquals("3권", pick(book("2권", read = 20, done = true), extra = listOf("1권", "3권")))
    }

    @Test fun naturalOrderNotTextOrder() {
        // "10권" comes after "9권": finishing 9 offers 10, not 1
        assertEquals("10권", pick(book("9권", read = 5, done = true), extra = listOf("1권", "10권")))
    }

    @Test fun lastFinishedVolumeStaysPut() {
        assertEquals("3권", pick(book("3권", read = 9, progress = 1f), extra = listOf("1권", "2권")))
    }

    @Test fun mostRecentWinsNotHighestVolume() {
        assertEquals("1권", pick(book("1권", read = 99, progress = 0.2f), book("5권", read = 50, done = true)))
    }

    @Test fun rangeSlices() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "b", "c"), VolumeRange.slice(l, 2, false))
        assertEquals(listOf("c", "d"), VolumeRange.slice(l, 2, true))
        assertEquals(emptyList<String>(), VolumeRange.slice(l, -1, true))
    }
}
