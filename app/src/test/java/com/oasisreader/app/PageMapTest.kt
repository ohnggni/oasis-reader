package com.oasisreader.app

import com.oasisreader.app.comic.PageMap
import com.oasisreader.app.comic.ScreenPage
import com.oasisreader.app.comic.groupsOf
import com.oasisreader.app.comic.isTwoPageScan
import com.oasisreader.app.comic.slicesOf
import com.oasisreader.app.data.ComicPackage
import com.oasisreader.app.data.ComicPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 108: screen page numbers ↔ image numbers. */
class PageMapTest {
    /** cover, a two-page scan, a plain page, a scan of odd width */
    private val pages = listOf(
        ComicPage("0.jpg", 1000, 1500), ComicPage("1.jpg", 2000, 1500),
        ComicPage("2.jpg", 1000, 1500), ComicPage("3.jpg", 2001, 1500),
    )

    @Test fun wideEnough() {
        assertTrue(isTwoPageScan(1200, 1000))
        assertFalse(isTwoPageScan(1199, 1000))
        assertTrue(isTwoPageScan(2000, 1500))
        assertFalse(isTwoPageScan(1000, 1500))
        assertFalse(isTwoPageScan(1, 0))
    }

    @Test fun offIsTheSameNumbering() {
        val m = PageMap(pages, split = false, rtl = true)
        assertEquals(4, m.count)
        for (i in 0..3) {
            assertEquals(i, m.sourceOf(i))
            assertEquals(i, m.viewAt(i, 0f))
            assertEquals(i, m.viewAt(i, 0.9f))
            assertEquals(0f, m.fractionOf(i), 0f)
            assertEquals(i, m.readThrough(i))
        }
        assertEquals(ScreenPage(1, "1.jpg", 0, 2000, 1500), m.screen[1])
    }

    @Test fun leftHalfFirst() {
        val m = PageMap(pages, split = true, rtl = false)
        assertEquals(6, m.count)
        assertEquals(listOf(0, 1, 1, 2, 3, 3), m.screen.map { it.source })
        assertEquals(listOf(0, 0, 1000, 0, 0, 1000), m.screen.map { it.left })
        assertEquals(listOf(1000, 1000, 1000, 1000, 1000, 1001), m.screen.map { it.w })
    }

    @Test fun rightHalfFirstWhenReadRightToLeft() {
        val m = PageMap(pages, split = true, rtl = true)
        assertEquals(listOf(0, 1000, 0, 0, 1000, 0), m.screen.map { it.left })
        assertEquals(listOf(1000, 1000, 1000, 1000, 1001, 1000), m.screen.map { it.w })
        assertEquals(listOf(0, 0, 1, 0, 0, 1), m.screen.map { it.part })
    }

    @Test fun savedPlaceAndBack() {
        val m = PageMap(pages, split = true, rtl = false)
        // every screen page comes back from what is saved for it
        for (v in 0 until m.count) assertEquals(v, m.viewAt(m.sourceOf(v), m.fractionOf(v)))
        // a bookmark keeps the image only: the first half
        assertEquals(1, m.viewAt(1))
        assertEquals(4, m.viewAt(3))
        // a webtoon-scroll fraction anywhere in the second half
        assertEquals(2, m.viewAt(1, 0.75f))
        assertEquals(1, m.viewAt(1, 0.49f))
        assertEquals(5, m.viewAt(3, 1f))
        // a plain page has one screen page whatever the fraction
        assertEquals(3, m.viewAt(2, 0.9f))
        // out of range
        assertEquals(4, m.viewAt(99, 0f))
        assertEquals(0, m.viewAt(-1, 0f))
        assertEquals(3, m.sourceOf(99))
    }

    @Test fun aFirstHalfIsNotReadThrough() {
        val m = PageMap(pages, split = true, rtl = false)
        assertEquals(0, m.readThrough(1))   // first half of image 1: image 0 is done
        assertEquals(1, m.readThrough(2))
        assertEquals(2, m.readThrough(4))   // not the end of the book yet
        assertEquals(3, m.readThrough(5))
        assertEquals(0, PageMap(listOf(pages[1]), true, false).readThrough(0))
    }

    @Test fun spreadsPairTheHalvesAgain() {
        val m = PageMap(pages, split = true, rtl = false)
        val shown = ComicPackage("t", null, m.screen.map { ComicPage(it.path, it.w, it.h) })
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), groupsOf(shown, spread = true, coverAlone = true))
    }

    @Test fun stripsFollowTheScreenPages() {
        val tall = listOf(ComicPage("a.jpg", 4000, 3300))
        val s = slicesOf(PageMap(tall, split = true, rtl = true).screen)
        assertEquals(6, s.size)                                   // 3 strips for each half
        assertEquals(listOf(0, 0, 0, 1, 1, 1), s.map { it.view })
        assertEquals(listOf(2000, 2000, 2000, 0, 0, 0), s.map { it.left })
        assertEquals(listOf(0, 1600, 3200, 0, 1600, 3200), s.map { it.top })
        assertTrue(s.all { it.page == 0 && it.w == 2000 })
    }
}
