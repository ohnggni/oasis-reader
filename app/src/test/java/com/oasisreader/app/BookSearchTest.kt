package com.oasisreader.app

import com.oasisreader.app.reader.BookSearch
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSearchTest {
    @Test fun matchesLikeThePageScript() {
        val html = "<html><head><title>제목 무지</title></head><body><p>우리는  <b>무지</b>의 섬에</p>\n<p>사는 무지&#xAC00;요. 무지!</p></body></html>"
        val text = BookSearch.chapterText(html)
        // the title in <head> is not part of the page; tags vanish without adding spaces; entities decoded
        val hits = BookSearch.inText(3, text, "무지")
        assertEquals(3, hits.size)
        assertEquals(listOf(0, 1, 2), hits.map { it.nth })
        assertEquals("무지", hits[0].match)
        assertEquals(1, BookSearch.inText(3, text, "무지가").size)          // &#xAC00; = 가
        assertEquals(1, BookSearch.inText(3, text, "우리는 무지의").size)   // double space collapses
        assertEquals(0, BookSearch.inText(3, text, "   ").size)
    }
}
