package com.oasisreader.app

import com.oasisreader.app.data.ShelfNotices
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfNoticesTest {
    // one test: ShelfNotices is one object for the whole process
    @Test fun latestWaitsForTheScreenThenGoesStraight() {
        ShelfNotices.added("첫째", "a")
        ShelfNotices.added("둘째", "b")
        val got = ArrayList<Pair<String, String>>()
        ShelfNotices.listen { m, id -> got += m to id }
        assertEquals(listOf("둘째" to "b"), got)
        ShelfNotices.added("셋째", "c")
        assertEquals(listOf("둘째" to "b", "셋째" to "c"), got)
        // listening again does not repeat what was already handed over
        val again = ArrayList<String>()
        ShelfNotices.listen { m, _ -> again += m }
        assertEquals(emptyList<String>(), again)
    }
}
