package com.oasisreader.app

import com.oasisreader.app.audio.bookPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookPlaceTest {
    @Test fun allLengthsKnown() {
        val p = bookPlace(listOf(100.0, 200.0, 300.0), 1, 50.0, 200.0)!!
        assertEquals(150.0, p.doneSec, 0.001)
        assertEquals(600.0, p.totalSec, 0.001)
        assertEquals(true, p.exact)
    }

    @Test fun unknownLengthsUseTheAverage() {
        // 소장: only the playing track's length is known (from the player)
        val p = bookPlace(listOf(0.0, 0.0, 0.0, 0.0), 2, 30.0, 120.0)!!
        assertEquals(270.0, p.doneSec, 0.001)
        assertEquals(480.0, p.totalSec, 0.001)
        assertEquals(false, p.exact)
    }

    @Test fun nothingKnown() {
        assertNull(bookPlace(listOf(0.0, 0.0), 0, 10.0, 0.0))
        assertNull(bookPlace(emptyList(), 0, 0.0, 0.0))
    }

    @Test fun placeNeverPassesTheEnd() {
        val p = bookPlace(listOf(100.0, 100.0), 1, 500.0, 100.0)!!
        assertEquals(200.0, p.doneSec, 0.001)
    }
}
