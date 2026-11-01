package com.oasisreader.app

import com.oasisreader.app.data.HighlightExport
import com.oasisreader.app.data.db.HighlightEntity
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

            하이라이트 4개 · 메모 1개 · 2026.10.15 ~ 2026.11.1

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
}
