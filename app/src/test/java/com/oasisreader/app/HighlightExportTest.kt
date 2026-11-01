package com.oasisreader.app

import com.oasisreader.app.data.BookDetails
import com.oasisreader.app.data.HighlightExport
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.HighlightEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class HighlightExportTest {

    private val seoul = TimeZone.getTimeZone("Asia/Seoul")
    // 2026-10-15 12:00 KST, 2026-11-01 12:00 KST
    private val oct15 = 1792033200000L
    private val nov1 = 1793502000000L

    private fun h(id: String, spine: Int, text: String, at: Long, note: String? = null) =
        HighlightEntity(id, "b", spine, text, "#ffeb3b", null, note, at)

    @Test fun markdownShape() {
        val chapters = mapOf(0 to "", 1 to "크툴루의 부름", 2 to "크툴루의 부름", 3 to "광기의 산맥")
        val md = HighlightExport.markdown(
            title = "러브크래프트 걸작선",
            author = "하워드 필립스 러브크래프트",
            highlights = listOf(
                h("3", 3, "남극의 산맥", nov1),
                h("1", 1, "첫 문장\n\n둘째 줄", oct15, note = "분위기가 정해진다.\n\n\n\n다음 문단"),
                h("2", 2, "무지의 섬", oct15 + 1),
                h("0", 0, "머리말 인용", oct15 + 2),
            ),
            chapterOf = { chapters[it].orEmpty() },
            now = nov1,
            zone = seoul,
        )
        val expected = """
            # 러브크래프트 걸작선
            하워드 필립스 러브크래프트

            - 하이라이트 4개 · 메모 1개

            > 머리말 인용

            ## 크툴루의 부름

            > 첫 문장
            > 둘째 줄

            분위기가 정해진다.

            다음 문단

            > 무지의 섬

            ## 광기의 산맥

            > 남극의 산맥

            ---
            OG Reader에서 내보냄 · 2026.11.1

        """.trimIndent()
        assertEquals(expected, md)
    }

    @Test fun fileNames() {
        assertEquals("A B - 하이라이트.md", HighlightExport.fileName("A/B", "md"))
        assertEquals("책 - 하이라이트.json", HighlightExport.fileName("  ", "json"))
    }

    @Test fun prefix() {
        assertEquals("그는 말했다. ", HighlightExport.prefixOf("그는 말했다. 안녕  ", "안녕"))
        assertNull(HighlightExport.prefixOf("안녕", "안녕"))            // nothing before the quote
        assertNull(HighlightExport.prefixOf("끝부분만 남은 앵커", "아주 긴 인용문"))  // quote longer than the anchor
        assertNull(HighlightExport.prefixOf(null, "안녕"))
    }

    @Test fun jsonShape() {
        val book = BookEntity(id = "b", title = "러브크래프트 걸작선", author = "러브크래프트", addedAt = 0, sourceUrl = "https://x/1")
        val hs = listOf(
            HighlightEntity("2", "b", 1, "무지의 섬", "#ffeb3b", "우리는 무지의 섬", "메모", oct15),
            HighlightEntity("1", "gone", 0, "지운 책 인용", "#90caf9", null, null, oct15),
        )
        val root = Json.parseToJsonElement(HighlightExport.json(listOf(book), hs, { null }, now = nov1, zone = seoul)).jsonObject
        assertEquals("og-reader-highlights", root["format"]!!.jsonPrimitive.content)
        assertEquals("2026-11-01T12:00:00+09:00", root["exportedAt"]!!.jsonPrimitive.content)
        val books = root["books"]!!.jsonArray
        val first = books[0].jsonObject
        assertEquals("b", first["bookId"]!!.jsonPrimitive.content)
        val a = first["annotations"]!!.jsonArray[0].jsonObject
        assertEquals("commenting", a["motivation"]!!.jsonPrimitive.content)
        assertEquals("메모", a["body"]!!.jsonArray[0].jsonObject["value"]!!.jsonPrimitive.content)
        val sel = a["target"]!!.jsonObject["selector"]!!.jsonObject
        assertEquals("무지의 섬", sel["exact"]!!.jsonPrimitive.content)
        assertEquals("우리는 ", sel["prefix"]!!.jsonPrimitive.content)
        assertEquals("true", books[1].jsonObject["removed"]!!.jsonPrimitive.content)
        assertEquals("highlighting", books[1].jsonObject["annotations"]!!.jsonArray[0].jsonObject["motivation"]!!.jsonPrimitive.content)
    }

    @Test fun markdownWithDetails() {
        val md = HighlightExport.markdown(
            title = "러브크래프트 걸작선",
            author = "러브크래프트",
            highlights = listOf(h("1", 0, "머리말 인용", oct15)),
            chapterOf = { "" },
            details = BookDetails(
                series = "걸작선", publisher = "황금가지", published = "2009-05-01", subjects = "소설, 공포",
                description = "첫 문단\n\n둘째 문단",
                firstReadDay = "2026-10-15", lastReadDay = "2026-10-30", readingSeconds = 6 * 3600 + 20 * 60 + 5,
                finishedAt = nov1,
            ),
            now = nov1, zone = seoul,
        )
        val expected = """
            # 러브크래프트 걸작선
            러브크래프트 · 황금가지 · 2009

            - 시리즈: 걸작선
            - 분류: 소설, 공포
            - 읽은 기간: 2026.10.15 ~ 2026.11.1 (다 읽음)
            - 독서 시간: 약 6시간 20분
            - 하이라이트 1개

            ## 책 소개

            첫 문단

            둘째 문단

            ## 하이라이트

            > 머리말 인용

            ---
            OG Reader에서 내보냄 · 2026.11.1

        """.trimIndent()
        assertEquals(expected, md)
    }

    @Test fun detailRules() {
        assertEquals("정상 줄거리", BookDetails.description("정상 줄거리", "ok", "EPUB 소개"))
        assertEquals("EPUB 소개", BookDetails.description("엉뚱한 책", "suspect", "EPUB 소개"))
        assertNull(BookDetails.description("엉뚱한 책", "suspect", null))
        assertEquals("앤틱 로맨틱", BookDetails.series("앤틱 로맨틱 [하즈키 카오루]", "앤틱 로맨틱 01권"))
        assertNull(BookDetails.series("러브크래프트 걸작선 [하워드 필립스 러브크래프트]", "러브크래프트 걸작선"))
        assertEquals("약 35분", HighlightExport.readingTime(35 * 60))
        assertNull(HighlightExport.readingTime(30))
    }
}
