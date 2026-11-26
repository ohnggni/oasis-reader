package com.oasisreader.app

import com.oasisreader.app.data.BookOrigin
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.seriesKeyOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 묶음 키는 설정(글꼴·보기 공유, 묶음 풀기)에 저장되므로 글자 하나도 달라지면 안 된다. */
class SeriesKeyTest {
    private fun book(title: String, format: String = "epub", origin: String? = null) =
        BookEntity(id = "id", title = title, addedAt = 0, format = format, origin = origin)

    @Test fun fromTheServerSeriesOrFolder() {
        assertEquals("BOOK|m|s1|3|괴력난신", seriesKeyOf(book("1권", origin = BookOrigin.meta("s1", "괴력난신", 3))))
        assertEquals("COMIC|m|s1|null|원피스", seriesKeyOf(book("1권", "cbz", BookOrigin.meta("s1", "원피스", null))))
        assertEquals("PDF|o|s2|http://h/b", seriesKeyOf(book("x", "pdf", BookOrigin.opds("s2", listOf("http://h/a", "http://h/b"), listOf("a", "b")))))
        assertEquals("BOOK|f|content://t|만화/원피스", seriesKeyOf(book("x", "txt", BookOrigin.folder("content://t", "만화/원피스"))))
        assertEquals("AUDIO|f|content://t|낭독", seriesKeyOf(book("x", "audiobook", BookOrigin.folder("content://t", "낭독"))))
    }

    @Test fun elseFromTheTitle() {
        assertEquals("COMIC|t|원피스", seriesKeyOf(book("원피스 01권", "cbz")))
        assertEquals("BOOK|t|harrypotter", seriesKeyOf(book("Harry Potter 2", origin = BookOrigin.folder("content://t", ""))))
        assertEquals("BOOK|t|berserk", seriesKeyOf(book("Berserk Vol.4", origin = BookOrigin.remote("r", "/a"))))
        assertNull(seriesKeyOf(book("데미안")))
        assertNull(seriesKeyOf(book("1984")))
    }
}
