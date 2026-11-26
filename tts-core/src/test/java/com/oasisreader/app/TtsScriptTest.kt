package com.oasisreader.app

import com.oasisreader.app.reader.TtsScript
import com.oasisreader.app.reader.TtsTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsScriptTest {

    /** The JS lines as the 2.x ReaderHtml had them by hand (git b86b283), byte for byte — plus TTS_COMMA (3.0, was inline in the script). */
    private val bs = "\\"   // a raw string would still have its unicode escapes read by the compiler

    private val oldLines = listOf(
        """var TTS_END = '.!?。！？…', TTS_CLOSE = '.!?。！？…"\'”’」』)]）〉》';""",
        """var TTS_QEND = '"\'”’」』)]）〉》';""",
        """var TTS_OPEN = '“‘「『（〈《([', TTS_LONG = 250, TTS_HARD = 400, TTS_MIN = 30;""",
        """var TTS_LABEL = /(?:제\s?[0-9]+\s?[장부편권화절]|[0-9]+\s?[장부편]|(?:part|chapter|book)\s*[0-9IVXivx]+|[${bs}u2460-${bs}u2473]|[${bs}u2160-${bs}u216B]|[0-9]+\.?|prologue|epilogue|프롤로그|에필로그)/i;""",
        """var TTS_LEAD = /(?:제\s?[0-9]+\s?[장부편권화절]|[0-9]+\s?[장부편]|(?:part|chapter|book)\s*[0-9IVXivx]+|[${bs}u2460-${bs}u2473]|[${bs}u2160-${bs}u216B]|[0-9]+\.|prologue|epilogue|프롤로그|에필로그)(?=\s+\S)/i;""",
        """var TTS_COMMA = ',，、';""",
    )

    /** Reads a single-quoted JS string literal back (what a JS engine would make of it). */
    private fun unJs(lit: String): String {
        assertTrue(lit.startsWith("'") && lit.endsWith("'"))
        val sb = StringBuilder()
        var i = 1
        while (i < lit.length - 1) {
            val c = lit[i]
            if (c != '\\') { sb.append(c); i++; continue }
            when (val e = lit[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                'u' -> { sb.append(lit.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                else -> { sb.append(e); i += 2 }   // \\ \' \" \/
            }
        }
        return sb.toString()
    }

    @Test fun declarationsAreTheOldHandWrittenOnes() {
        assertEquals(oldLines, TtsScript.declarations().split("\n").map { it.trim() })
    }

    @Test fun declaredStringsReadBackAsTheTuningValues() {
        val d = TtsScript.declarations()
        fun str(name: String): String {
            val m = Regex("""$name = ('(?:[^'\\]|\\.)*')""").find(d)!!
            return unJs(m.groupValues[1])
        }
        fun num(name: String) = Regex("""$name = (\d+)""").find(d)!!.groupValues[1].toInt()
        assertEquals(TtsTuning.TEXT_END, str("TTS_END"))
        assertEquals(TtsTuning.TEXT_CLOSE, str("TTS_CLOSE"))
        assertEquals(TtsTuning.TEXT_QUOTE_END, str("TTS_QEND"))
        assertEquals(TtsTuning.TEXT_OPEN, str("TTS_OPEN"))
        assertEquals(TtsTuning.TEXT_LONG, num("TTS_LONG"))
        assertEquals(TtsTuning.TEXT_HARD, num("TTS_HARD"))
        assertEquals(TtsTuning.TEXT_MIN, num("TTS_MIN"))
        assertEquals(TtsTuning.TEXT_COMMA, str("TTS_COMMA"))
        assertEquals("/" + TtsTuning.TEXT_LABEL + "/i", Regex("""TTS_LABEL = (/.*/i);""").find(d)!!.groupValues[1])
        assertEquals("/" + TtsTuning.TEXT_LEAD + """(?=\s+\S)/i""", Regex("""TTS_LEAD = (/.*/i);""").find(d)!!.groupValues[1])
    }

    @Test fun jsStringEscapesWhatMustBeEscaped() {
        val nasty = "a'b\"c\\d\ne\rf g h</script>i"
        val lit = TtsScript.jsString(nasty)
        assertFalse(lit.contains("\n") || lit.contains("\r") || lit.contains(" ") || lit.contains("</"))
        assertEquals(nasty, unJs(lit))
        assertEquals("'.!?\\'\"'", TtsScript.jsString(".!?'\""))
    }

    @Test fun tuningValuesAreTheOnesFrom2x() {
        assertEquals(".!?。！？…", TtsTuning.TEXT_END)
        assertEquals(250, TtsTuning.TEXT_LONG); assertEquals(400, TtsTuning.TEXT_HARD); assertEquals(30, TtsTuning.TEXT_MIN)
        assertEquals(",，、", TtsTuning.TEXT_COMMA)
        assertEquals(".?!…。？！\"'”’」』)]", TtsTuning.GAP_END_MARKS); assertEquals(150, TtsTuning.GAP_NO_MARK_MS)
        assertEquals(listOf(0, 150, 350, 600), TtsTuning.GAP_MS.toList())
        assertEquals(listOf(150, 350, 600, 900), TtsTuning.HEAD_MS.toList())
        assertEquals(2, TtsTuning.GAP_NORMAL); assertEquals(8_000L, TtsTuning.CUE_GAP_MS); assertEquals(3000, TtsTuning.MAX_LEN)
        assertEquals(0.5f, TtsTuning.RATE_MIN); assertEquals(2f, TtsTuning.RATE_MAX); assertEquals(0.05f, TtsTuning.RATE_STEP)
        assertEquals(listOf(0.8f to "낮게", 1f to "보통", 1.2f to "높게"), TtsTuning.PITCHES)
        assertEquals(listOf(15, 30, 45, 60), TtsTuning.SLEEP)
        assertEquals(22050, TtsTuning.CUE_SAMPLE_RATE); assertEquals(0.64, TtsTuning.CUE_LEN_S, 0.0); assertEquals(700L, TtsTuning.CUE_MS)
        assertEquals(2, TtsTuning.AZURE_AHEAD); assertEquals(3, TtsTuning.AZURE_MAX_FAILS)
        assertEquals(209_715_200L, TtsTuning.AZURE_CACHE_MAX)
    }
}
