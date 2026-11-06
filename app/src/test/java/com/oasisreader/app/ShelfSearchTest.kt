package com.oasisreader.app

import com.oasisreader.app.ui.ShelfSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfSearchTest {
    @Test fun plainAndSpaces() {
        assertTrue(ShelfSearch.matches("원피스 01권", "원피스"))
        assertTrue(ShelfSearch.matches("원피스 01권", "피스01"))
        assertTrue(ShelfSearch.matches("Harry Potter", "harrypot"))
        assertFalse(ShelfSearch.matches("원피스", "나루토"))
        assertTrue(ShelfSearch.matches("아무거나", "  "))
        assertFalse(ShelfSearch.matches(null, "a"))
    }

    @Test fun initials() {
        assertTrue(ShelfSearch.matches("원피스", "ㅇㅍㅅ"))
        assertTrue(ShelfSearch.matches("원피스 1권", "원ㅍ"))
        assertTrue(ShelfSearch.matches("쌍둥이", "ㅆㄷ"))
        assertFalse(ShelfSearch.matches("원피스", "ㄴㅍ"))
    }

    @Test fun initialLabel() {
        assertEquals("ㅇ", ShelfSearch.initial("원피스"))
        assertEquals("H", ShelfSearch.initial("harry"))
        assertEquals("#", ShelfSearch.initial("1984"))
    }

    @Test fun naturalOrder() {
        val sorted = listOf("원피스 10권", "원피스 2권", "원피스 1권", "원피스 01권").sortedWith(ShelfSearch.natural)
        assertEquals(listOf("원피스 1권", "원피스 01권", "원피스 2권", "원피스 10권"), sorted)
    }
}
