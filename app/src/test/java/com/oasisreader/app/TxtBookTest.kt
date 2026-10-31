package com.oasisreader.app

import com.oasisreader.app.epub.TxtBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TxtBookTest {

    @Test fun encodings() {
        val s = "한글 텍스트입니다. 똠방각하 뷁"
        assertEquals(s, TxtBook.decode(s.toByteArray(Charsets.UTF_8)))
        assertEquals(s, TxtBook.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.toByteArray(Charsets.UTF_8)))
        assertEquals(s, TxtBook.decode(s.toByteArray(charset("x-windows-949"))))   // 똠·뷁 exist only in CP949
    }

    @Test fun headings() {
        listOf("1화", "제 12 화", "제3장 귀환", "第5章", "Chapter 7", "<15화>", "[23화] 결전", "#3", "프롤로그", "에필로그 - 그 후", "10화. 시작")
            .forEach { assertTrue(it, TxtBook.isHeading(it)) }
        listOf("2권을 샀다.", "그는 1화를 다 보고 잠들었다. 아주 긴 문장이라서 제목이 아니다 정말로 아니다 길다 길어", "", "안녕하세요")
            .forEach { assertFalse(it, TxtBook.isHeading(it)) }
    }

    @Test fun splitByHeadings() {
        val text = "작품 소개\n\n1화\n첫 문단\n\n2화\n둘째\n\n3화 마지막\n셋째\n"
        val ch = TxtBook.split(text)
        assertEquals(listOf("처음", "1화", "2화", "3화 마지막"), ch.map { it.title })
    }

    @Test fun splitWithoutHeadings() {
        val para = "가".repeat(900)
        val text = (1..100).joinToString("\n\n") { para }   // ~90k chars, no headings
        val ch = TxtBook.split(text)
        assertTrue("parts: ${ch.size}", ch.size in 4..6)
        assertEquals("1", ch.first().title)
    }

    @Test fun writesEpubFolder() {
        val dir = createTempDir()
        val src = File(dir, "a.txt").apply { writeText("1화\n가\n\n2화\n나 & <다>\n\n3화\n라\n") }
        val out = File(dir, "out")
        TxtBook.write(src, out, "테스트")
        assertTrue(File(out, "META-INF/container.xml").isFile)
        assertTrue(File(out, "OEBPS/content.opf").readText().contains("<dc:title>테스트</dc:title>"))
        assertTrue(File(out, "OEBPS/c00002.xhtml").readText().contains("<p>나 &amp; &lt;다&gt;</p>"))
        assertTrue(File(out, "OEBPS/nav.xhtml").readText().contains(">3화</a>"))
        dir.deleteRecursively()
    }

    @Test fun sniff() {
        val dir = createTempDir()
        assertTrue(TxtBook.looksLikeText(File(dir, "t").apply { writeBytes("안녕".toByteArray(charset("x-windows-949"))) }))
        assertFalse(TxtBook.looksLikeText(File(dir, "z").apply { writeBytes(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 0, 0)) }))
        dir.deleteRecursively()
    }
}
