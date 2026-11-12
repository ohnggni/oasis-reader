package com.oasisreader.app

import com.oasisreader.app.stats.nextHabitTime
import com.oasisreader.app.stats.readingStreak
import com.oasisreader.app.ui.isBookFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class HabitTest {
    // 2026-11-12 is a Thursday (4)
    private val thu = LocalDateTime.of(2026, 11, 12, 21, 0)

    @Test fun laterToday() {
        assertEquals(LocalDateTime.of(2026, 11, 12, 22, 0), nextHabitTime(thu, 22, 0, (1..7).toSet()))
    }

    @Test fun passedTodayGoesToTheNextDay() {
        assertEquals(LocalDateTime.of(2026, 11, 13, 20, 30), nextHabitTime(thu, 20, 30, (1..7).toSet()))
        assertEquals(LocalDateTime.of(2026, 11, 13, 21, 0), nextHabitTime(thu, 21, 0, (1..7).toSet()))
    }

    @Test fun onlyChosenDays() {
        assertEquals(LocalDateTime.of(2026, 11, 14, 9, 0), nextHabitTime(thu, 9, 0, setOf(6, 7)))        // 토
        assertEquals(LocalDateTime.of(2026, 11, 19, 9, 0), nextHabitTime(thu, 9, 0, setOf(4)))           // next 목
        assertNull(nextHabitTime(thu, 9, 0, emptySet()))
    }

    @Test fun streak() {
        val today = LocalDate.of(2026, 11, 12)
        val days = mapOf("2026-11-12" to 600L, "2026-11-11" to 90L, "2026-11-10" to 30L, "2026-11-09" to 900L)
        assertEquals(2, readingStreak(days, today))
        assertEquals(1, readingStreak(days - "2026-11-12", today))      // today not read yet: yesterday still counts
        assertEquals(0, readingStreak(emptyMap(), today))
    }

    @Test fun bookFiles() {
        assertTrue(isBookFile("만화 01권.CBZ", null))
        assertTrue(isBookFile("book.epub", "application/octet-stream"))
        assertFalse(isBookFile("photo.jpg", "image/jpeg"))
        assertFalse(isBookFile("data.bin", "application/zip"))          // the name says what it is
        assertTrue(isBookFile(null, "application/epub+zip"))
        assertTrue(isBookFile("12345", "application/pdf"))              // no extension: go by the type
        assertFalse(isBookFile("12345", "application/octet-stream"))
    }
}
