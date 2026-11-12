package com.oasisreader.app

import com.oasisreader.app.data.ReviewText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTextTest {
    @Test fun stars() {
        assertEquals("★★★☆☆", ReviewText.stars(3))
        assertEquals("☆☆☆☆☆", ReviewText.stars(0))
        assertEquals("★★★★★", ReviewText.stars(9))
    }

    @Test fun appendLeavesABlankLine() {
        assertEquals("새 글", ReviewText.append("  ", " 새 글\n"))
        assertEquals("앞\n\n뒤", ReviewText.append("앞\n\n\n", "\n뒤"))
    }

    @Test fun quoteWithNote() {
        assertEquals("> 첫 줄\n> 둘째 줄\n\n내 생각", ReviewText.quote("첫 줄\n둘째 줄", " 내 생각 "))
        assertEquals("> 한 줄", ReviewText.quote("한 줄", "  "))
        assertEquals("> 가\n\n> 나\n\n메모", ReviewText.quotes(listOf("가" to null, "나" to "메모")))
    }

    @Test fun markdownHasWhatIsThere() {
        val full = ReviewText.markdown("책", "저자", 4, "2026.11.12", "좋았다.", listOf("문장" to null))
        assertTrue(full.startsWith("# 책\n\n- 저자: 저자\n- 별점: ★★★★☆\n- 다 읽은 날: 2026.11.12\n\n## 독후감\n\n좋았다.\n\n## 하이라이트\n\n> 문장\n"))
        val bare = ReviewText.markdown("책", null, 0, null, "글", emptyList())
        assertEquals("# 책\n\n## 독후감\n\n글\n", bare)
        assertFalse(bare.contains("별점"))
    }

    @Test fun shareText() {
        assertEquals("「책」  ★★☆☆☆\n\n글", ReviewText.shareText("책", 2, "글\n"))
        assertEquals("「책」\n\n글", ReviewText.shareText("책", 0, "글"))
    }

    @Test fun decodeUtf8AndKorean() {
        assertEquals("가나다", ReviewText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "가나다".toByteArray(Charsets.UTF_8)))
        assertEquals("한글", ReviewText.decode("한글".toByteArray(charset("EUC-KR"))))
    }
}
