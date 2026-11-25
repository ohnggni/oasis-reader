package com.oasisreader.app

import com.oasisreader.app.reader.ChapterDoc
import com.oasisreader.app.reader.PageHighlight
import com.oasisreader.app.reader.ReaderConfig
import com.oasisreader.app.reader.ReaderHtml
import com.oasisreader.app.reader.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 3.0: CSS/JS moved to resources/reader — the page must be exactly what the old raw string built. */
class ReaderHtmlSplitTest {

    private fun config(paged: Boolean, head: String = "", hl: List<PageHighlight> = emptyList()) = ReaderConfig(
        paged = paged, fontPx = 18, start = 0.25f, padTop = 72, padBottom = 40,
        fontFamily = "'Noto Serif KR', serif", highlights = hl, colors = ReaderHtml.COLORS,
        docUrl = "https://x/a.xhtml", head = head, spread = paged, turn = if (paged) "curl" else "slide",
    )

    private val plainDoc = ChapterDoc("https://x/a.xhtml", "<p>글.</p>", "", emptyList(), "")
    private val bookDoc = ChapterDoc(
        "https://x/b.xhtml",
        "<h2>제3장</h2><p>“간다.”고 했다. 50% \$5 <a href=\"#n1\">1</a></p>",
        "chapter body",
        listOf("https://x/style.css", "https://x/a&b.css"),
        "p { text-indent: 1em; }",
    )

    private fun same(doc: ChapterDoc, cfg: ReaderConfig, font: String?, pub: Boolean, theme: ReaderTheme) {
        val old = LegacyReaderHtml.build(doc, cfg, font, pub, theme)
        val new = ReaderHtml.build(doc, cfg, font, pub, theme)
        assertEquals(old, new)
    }

    @Test fun scrollPlainPaper() = same(plainDoc, config(false), null, false, ReaderTheme.PAPER)

    @Test fun pagedBookCssDark() = same(bookDoc, config(true), "https://x/font.css", true, ReaderTheme.NIGHT)

    @Test fun einkWithHighlights() = same(
        bookDoc,
        config(true, hl = listOf(PageHighlight("h1", "간다", "#fff176", "p3"), PageHighlight("h2", "a\"b</c>", "#82b1ff"))),
        null, true, ReaderTheme.EINK,
    )

    /** Book text in the config must reach the page untouched, even when it looks like a placeholder. */
    @Test fun titleThatLooksLikeAPlaceholder() {
        val head = "__OG_TTS_DECL__ __OG_CFG__ \$cfg </script>"
        same(bookDoc, config(true, head = head), null, false, ReaderTheme.SEPIA)
        val html = ReaderHtml.build(bookDoc, config(true, head = head), null, false, ReaderTheme.SEPIA)
        assertFalse(html.contains("__OG_CFG__ \$cfg </script>"))   // "</" is escaped inside the config JSON
        assertEquals(1, Regex("var TTS_END =").findAll(html).count())
    }
}
