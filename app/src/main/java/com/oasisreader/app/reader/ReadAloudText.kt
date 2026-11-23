package com.oasisreader.app.reader

/**
 * Sentences of a chapter straight from its HTML, for 소리 내 읽기 while the screen is off (the page
 * on screen cannot load the next chapter then). Same rules as the page script: blocks are kept
 * apart, note references are not read, a sentence ends at . ! ? 。 … (plus closing quotes)
 * followed by a space, the end of the block, a Korean letter or an opening quote (텍본 EPUBs leave
 * no space); invisible spaces count as spaces; a sentence over [LONG] letters is cut again at the
 * last comma (else the last space) before that.
 */
object ReadAloudText {

    private const val END = ".!?。！？…"
    private const val CLOSE = ".!?。！？…\"'”’」』)]）〉》"
    private const val OPEN = "“‘「『（〈《(["
    private const val COMMA = ",，、"
    /** Over this a sentence is cut at a comma (2.9: was 120 — book sentences of 150–200 letters were cut mid-phrase). */
    private const val LONG = 250
    /** Over this, with no comma, at a space too (text with no sentence ends — 텍본). */
    private const val HARD = 400
    private const val MIN = 30
    private val INVISIBLE = Regex("[\\u200B-\\u200D\\u2060\\uFEFF]")

    private fun space(c: Char) = c.isWhitespace() || c == '\u00A0' || c in '\u200B'..'\u200D' || c == '\u2060' || c == '\uFEFF'
    private fun after(c: Char) = space(c) || OPEN.indexOf(c) >= 0 || c in '\uAC00'..'\uD7A3' || c in '\u3131'..'\u318E'

    private val WORDY = Regex("[0-9A-Za-z\\u00C0-\\u024F\\u0370-\\u03FF\\u0400-\\u04FF\\u3040-\\u30FF\\u3400-\\u9FFF\\uAC00-\\uD7A3]")

    fun sentences(html: String): List<String> {
        var body = Regex("(?is)<body\\b[^>]*>(.*)</body>").find(html)?.groupValues?.get(1) ?: html
        body = body
            .replace(Regex("(?is)<script\\b.*?</script>|<style\\b.*?</style>|<!--.*?-->|<rt\\b.*?</rt>|<rp\\b.*?</rp>"), "")
            // note references: <sup>…</sup> with a short mark, links marked as notes or holding a short number
            .replace(Regex("(?is)<sup\\b[^>]*>\\s*(?:<a\\b[^>]*>)?\\s*[\\[(]?[0-9*†‡§¶a-z]{1,3}[\\])]?\\s*(?:</a>)?\\s*</sup>"), "")
            .replace(Regex("(?is)<a\\b[^>]*(?:noteref|footnote)[^>]*>.*?</a>"), "")
            .replace(Regex("(?is)<a\\b[^>]*href=\"[^\"]*#[^\"]*\"[^>]*>\\s*[\\[(]?[0-9*†‡§¶]{1,3}[\\])]?\\s*</a>"), "")
            // blocks: a line each
            .replace(Regex("(?i)<br\\s*/?>|</?(?:p|div|li|h[1-6]|blockquote|dd|dt|td|th|tr|figcaption|caption|pre|section|article|aside|header|footer|table)\\b[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
        val text = BookSearch.decode(body)
        val out = ArrayList<String>()
        for (block in text.split('\n')) split(block, out)
        return out
    }

    private fun split(s: String, out: MutableList<String>) {
        fun push(x0: Int, y0: Int) {
            var x = x0; var y = y0
            while (x < y && space(s[x])) x++
            while (y > x && space(s[y - 1])) y--
            if (y <= x) return
            val t = s.substring(x, y).replace(INVISIBLE, "").replace(Regex("[\\s\\u00A0]+"), " ").trim()
            if (t.isNotEmpty() && WORDY.containsMatchIn(t)) out += t
        }
        fun piece(x0: Int, y: Int) {
            var x = x0
            while (y - x > LONG) {
                val lim = x + LONG
                var cut = -1
                var k = lim
                while (k > x + MIN) { if (COMMA.indexOf(s[k - 1]) >= 0 && space(s[k])) { cut = k; break }; k-- }
                if (cut < 0) {
                    if (y - x <= HARD) break
                    k = lim; while (k > x + MIN) { if (space(s[k])) { cut = k; break }; k-- }
                }
                if (cut < 0) cut = lim
                if (y - cut < MIN) break
                push(x, cut); x = cut
            }
            push(x, y)
        }
        var a = 0
        var i = 0
        while (i < s.length) {
            if (END.indexOf(s[i]) >= 0) {
                var j = i + 1
                while (j < s.length && CLOSE.indexOf(s[j]) >= 0) j++
                if (j >= s.length || after(s[j])) { piece(a, j); a = j; i = j; continue }
            }
            i++
        }
        if (a < s.length) piece(a, s.length)
    }
}
