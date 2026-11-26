package com.oasisreader.app

import com.oasisreader.app.reader.ChapterDoc
import com.oasisreader.app.reader.ReaderConfig
import com.oasisreader.app.reader.ReaderHtml
import com.oasisreader.app.reader.ReaderTheme
import com.oasisreader.app.reader.TtsScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app side of TtsScript (its own tests are in :tts-core): the page really gets its lines. */
class TtsScriptPageTest {

    @Test fun pageHasTheDeclarationsOnce() {
        val html = ReaderHtml.build(
            ChapterDoc("https://x/a.xhtml", "<p>글.</p>", "", emptyList(), ""),
            ReaderConfig(paged = false, fontPx = 18, start = 0f, padTop = 72, padBottom = 40, fontFamily = "serif", highlights = emptyList(), colors = ReaderHtml.COLORS, docUrl = "https://x/a.xhtml"),
            null, true, ReaderTheme.PAPER,
        )
        assertTrue(html.contains(TtsScript.declarations()))
        assertEquals(1, Regex("var TTS_END =").findAll(html).count())
        assertEquals(1, Regex("var TTS_LEAD =").findAll(html).count())
        assertEquals(1, Regex("var TTS_COMMA =").findAll(html).count())
    }
}
