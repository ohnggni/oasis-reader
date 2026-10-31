package com.oasisreader.app

import com.oasisreader.app.data.Http
import com.oasisreader.app.meta.MetaApi
import com.oasisreader.app.meta.MetaBook
import com.oasisreader.app.meta.MetaPage
import com.oasisreader.app.meta.MetaSeries
import com.oasisreader.app.opds.OpdsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses copied from oasis-meta-api.md. */
class MetaApiTest {

    @Test fun seriesPage() {
        val json = """
        {
          "items": [
            {
              "library_id": 23,
              "series_name": "초세기담 메이즈폭열시공",
              "book_count": 6,
              "latest_mtime": "2026-09-21T05:13:56Z",
              "first_mtime": "2026-09-21T05:13:46Z",
              "latest_added": "2026-10-03 04:31:36",
              "first_book_id": 267582
            }
          ],
          "page": 1, "limit": 3, "has_more": true
        }"""
        val p = Http.json.decodeFromString(MetaPage.serializer(MetaSeries.serializer()), json)
        assertEquals(1, p.items.size)
        assertEquals("초세기담 메이즈폭열시공", p.items[0].seriesName)
        assertEquals(267582L, p.items[0].firstBookId)
        assertTrue(p.hasMore)
    }

    @Test fun book() {
        val json = """
        {
          "id": 152398,
          "title": "3",
          "display_title": "앤틱 로맨틱 03권 (완결)",
          "series_name": "앤틱 로맨틱 [하즈키 카오루]",
          "author": "이영빈",
          "library_id": 23,
          "file_name": "앤틱 로맨틱 03권 (완결)#190.cbz",
          "file_format": "cbz",
          "file_size": 62433496,
          "total_pages": 190,
          "file_mtime": "2022-05-20T01:45:54Z",
          "created_at": "2026-06-30 05:25:03",
          "download_path": "/opds/download/general/152398"
        }"""
        val b = Http.json.decodeFromString(MetaBook.serializer(), json)
        assertEquals("앤틱 로맨틱 03권 (완결)", b.name)
        assertTrue(b.isComic)
        assertTrue(b.readable)
        assertEquals("general" to 152398L, MetaApi.idFromDownload("https://book.example.com" + b.downloadPath))
    }

    @Test fun genericTitles() {
        assertTrue(OpdsClient.isGenericTitle("[만화] 1"))
        assertTrue(OpdsClient.isGenericTitle("3권"))
        assertFalse(OpdsClient.isGenericTitle("앤틱 로맨틱 03권"))
        assertEquals("1", OpdsClient.numberIn("[만화] 1"))
    }

    @Test fun contentDisposition() {
        val cd = "inline; filename=\"book_152396.cbz\"; filename*=UTF-8''%EC%95%A4%ED%8B%B1%20%EB%A1%9C%EB%A7%A8%ED%8B%B1%2001%EA%B6%8C%23188.cbz"
        assertEquals("앤틱 로맨틱 01권#188.cbz", OpdsClient.fileNameFrom(cd))
        assertEquals("a b.epub", OpdsClient.fileNameFrom("attachment; filename=\"a b.epub\""))
    }

    @Test fun seriesCoverField() {
        val json = """{"items":[{"library_id":23,"series_name":"x","book_count":1,"first_book_id":5,"cover_url":"/covers/2/book_ab.webp"}],"page":1,"limit":1,"has_more":false}"""
        val p = Http.json.decodeFromString(MetaPage.serializer(MetaSeries.serializer()), json)
        assertEquals("/covers/2/book_ab.webp", p.items[0].coverUrl)
    }

    @Test fun bookSummary() {
        val json = """{"id":2768,"display_title":"도시정벌 10부","summary":"달도 차면 기운다 했다.\n하지만...","summary_trust":"ok","latest_added":"x"}"""
        val b = Http.json.decodeFromString(MetaBook.serializer(), json)
        assertEquals("달도 차면 기운다 했다.\n하지만...", b.summary)
        assertEquals("ok", b.summaryTrust)
        val n = Http.json.decodeFromString(MetaBook.serializer(), """{"id":1,"summary":null,"summary_trust":null}""")
        assertEquals(null, n.summary)
    }

    @Test fun junkTitles() {
        listOf("unknown title", "Unknown", "Untitled", "", "제목 없음").forEach { assertTrue(it, OpdsClient.isPoorTitle(it)) }
        listOf("월간 샘터 2026년 1월호", "Unknown Pleasures", "Book of Days").forEach { assertFalse(it, OpdsClient.isPoorTitle(it)) }
    }
}
