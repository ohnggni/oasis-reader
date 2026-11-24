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

    private val WORDY = Regex("[0-9A-Za-z\\u00C0-\\u024F\\u0370-\\u03FF\\u0400-\\u04FF\\u3040-\\u30FF\\u3400-\\u9FFF\\uAC00-\\uD7A3\\u2160-\\u217B\\u2460-\\u249B\\u3260-\\u327B]")

    fun sentences(html: String): List<String> {
        var body = Regex("(?is)<body\\b[^>]*>(.*)</body>").find(html)?.groupValues?.get(1) ?: html
        body = body
            .replace(Regex("(?is)<script\\b.*?</script>|<style\\b.*?</style>|<!--.*?-->|<rt\\b.*?</rt>|<rp\\b.*?</rp>"), "")
            // note references: <sup>…</sup> with a short mark, links marked as notes or holding a short number
            .replace(Regex("(?is)<sup\\b[^>]*>\\s*(?:<a\\b[^>]*>)?\\s*[\\[(]?[0-9*†‡§¶a-z]{1,3}[\\])]?\\s*(?:</a>)?\\s*</sup>"), "")
            .replace(Regex("(?is)<a\\b[^>]*(?:noteref|footnote)[^>]*>.*?</a>"), "")
            .replace(Regex("(?is)<a\\b[^>]*href=\"[^\"]*#[^\"]*\"[^>]*>\\s*[\\[(]?[0-9*†‡§¶]{1,3}[\\])]?\\s*</a>"), "")
            // headings (85): lines broken by <br> are one sentence; a chapter number line stays apart
            .replace(HEADING) { m -> "\n" + heading(m.groupValues[2]) + "\n" }
            // blocks: a line each
            .replace(Regex("(?i)<br\\s*/?>|</?(?:p|div|li|h[1-6]|blockquote|dd|dt|td|th|tr|figcaption|caption|pre|section|article|aside|header|footer|table)\\b[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
        val text = BookSearch.decode(body)
        val out = ArrayList<String>()
        for (block in text.split('\n')) split(block, out)
        return out
    }

    /**
     * Ends a heading's sentence (85): not spoken (ReadAloud.voice takes it off), only tells
     * ReadAloud.gapAfter to pause a little longer than after a sentence. The page script adds the same.
     */
    const val HEAD = '⁣'

    private val HEADING = Regex("(?is)<(h[1-6])\\b[^>]*>(.*?)</\\1\\s*>")
    private val BR = Regex("(?i)<br\\s*/?>")

    /** A line that is only a chapter's number: 제3장, 3장, 2부, PART 1, Chapter IV, ⑥, Ⅲ, 1., 프롤로그. Same as the page script's TTS_LABEL. */
    private val LABEL = Regex(
        "(?:제\\s?[0-9]+\\s?[장부편권화절]|[0-9]+\\s?[장부편]|(?:part|chapter|book)\\s*[0-9IVXivx]+|[\\u2460-\\u2473]|[\\u2160-\\u216B]|[0-9]+\\.?|prologue|epilogue|프롤로그|에필로그)",
        RegexOption.IGNORE_CASE,
    )

    fun isLabel(line: String): Boolean = LABEL.matches(line.replace(Regex("\\s+"), " ").trim())

    /** A chapter number at the start of a one-line heading, then its title (89): 4장 목표에 미칠 때. Bare digits need a dot. Same as the page script's TTS_LEAD. */
    private val LEAD = Regex(
        "^\\s*((?:제\\s?[0-9]+\\s?[장부편권화절]|[0-9]+\\s?[장부편]|(?:part|chapter|book)\\s*[0-9IVXivx]+|[\\u2460-\\u2473]|[\\u2160-\\u216B]|[0-9]+\\.|prologue|epilogue|프롤로그|에필로그))\\s+(?=\\S)",
        RegexOption.IGNORE_CASE,
    )

    /** "4장 목표에 미칠 때" → ["4장", "목표에 미칠 때"]; anything else → itself. Each read with a heading's pause after it. */
    fun splitLead(t: String): List<String> {
        val m = LEAD.find(t) ?: return listOf(t)
        val rest = t.substring(m.range.last + 1)
        return if (rest.isBlank()) listOf(t) else listOf(m.groupValues[1], rest)
    }

    /** A heading's inside as one line ending with [HEAD], or two (number line, title) when it starts with a number line. */
    private fun heading(inner: String): String {
        val lines = inner.split(BR).filter { BookSearch.decode(it.replace(Regex("<[^>]+>"), "")).isNotBlank() }
        if (lines.isEmpty()) return inner
        val plain = lines.map { it.replace(Regex("<[^>]+>"), "") }
        val first = BookSearch.decode(plain[0])
        // 89: "4장 목표에 미칠 때" on one line also gets its number apart
        fun lead(t: String) = splitLead(t).joinToString("\n") { it + HEAD }
        return if (plain.size > 1 && isLabel(first)) plain[0] + HEAD + "\n" + lead(plain.drop(1).joinToString(" "))
        else lead(plain.joinToString(" "))
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
