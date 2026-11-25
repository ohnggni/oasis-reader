package com.oasisreader.app.reader

/**
 * The page script's copy of the sentence-splitting numbers, made from [TtsTuning] (3.0) so that
 * the app and the page cannot drift apart. ReaderHtml.build puts [declarations] in the page
 * script. Kept out of the page's raw string on purpose: strings with quotes and backslashes are
 * written here by [jsString], one place that escapes them.
 */
object TtsScript {

    /** A JS string literal in single quotes. Only what must be escaped is: the page text stays as it was written by hand. */
    fun jsString(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '\'' -> sb.append("\\'")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\u2028' -> sb.append("\\u2028")
            '\u2029' -> sb.append("\\u2029")
            else -> sb.append(c)
        }
        // the script sits inside a script tag: a closing tag must never appear in it
        return sb.append('\'').toString().replace("</", "<\\/")
    }

    /** The var lines: TTS_END, TTS_CLOSE, TTS_QEND, TTS_OPEN, TTS_LONG, TTS_HARD, TTS_MIN, TTS_LABEL, TTS_LEAD. */
    fun declarations(): String = listOf(
        "var TTS_END = ${jsString(TtsTuning.TEXT_END)}, TTS_CLOSE = ${jsString(TtsTuning.TEXT_CLOSE)};",
        "var TTS_QEND = ${jsString(TtsTuning.TEXT_QUOTE_END)};",
        "var TTS_OPEN = ${jsString(TtsTuning.TEXT_OPEN)}, TTS_LONG = ${TtsTuning.TEXT_LONG}, TTS_HARD = ${TtsTuning.TEXT_HARD}, TTS_MIN = ${TtsTuning.TEXT_MIN};",
        "var TTS_LABEL = /${TtsTuning.TEXT_LABEL}/i;",
        "var TTS_LEAD = /${TtsTuning.TEXT_LEAD}(?=\\s+\\S)/i;",
    ).joinToString("\n  ")
}
