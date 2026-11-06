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

    @Test fun breakAnywhere() {
        assertEquals("원\u200B피\u200B스 1\u200B권", ShelfSearch.breakAnywhere("원피스 1권"))
        assertEquals("📖\u00A0가\u200B나", ShelfSearch.labeled("📖", "가나"))
        // an emoji (two chars) is never split
        assertEquals("가\u200B😀", ShelfSearch.breakAnywhere("가😀"))
    }

    @Test fun seriesStem() {
        assertEquals("원피스", ShelfSearch.seriesStem("원피스 01권"))
        assertEquals("원피스", ShelfSearch.seriesStem("원피스 2권"))
        assertEquals("원피스", ShelfSearch.seriesStem("원피스 (3)"))
        assertEquals("Berserk", ShelfSearch.seriesStem("Berserk Vol.4"))
        assertEquals("해버리면 그걸로 끝", ShelfSearch.seriesStem("해버리면 그걸로 끝 3권#188"))
        assertEquals(null, ShelfSearch.seriesStem("노르웨이의 숲"))
    }
}
