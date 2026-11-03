package com.oasisreader.app.reader

/**
 * Sentences of a chapter straight from its HTML, for 소리 내 읽기 while the screen is off (the page
 * on screen cannot load the next chapter then). Same rules as the page script: blocks are kept
 * apart, note references are not read, a sentence ends at . ! ? 。 … (plus closing quotes)
 * followed by a space or the end of the block.
 */
object ReadAloudText {

    private const val END = ".!?。！？…"
    private const val CLOSE = ".!?。！？…\"'”’」』)]）〉》"
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
        var a = 0
        var i = 0
        fun push(x: Int, y: Int) {
            val t = s.substring(x, y).replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty() && WORDY.containsMatchIn(t)) out += t
        }
        while (i < s.length) {
            if (END.indexOf(s[i]) >= 0) {
                var j = i + 1
                while (j < s.length && CLOSE.indexOf(s[j]) >= 0) j++
                if (j >= s.length || s[j].isWhitespace()) { push(a, j); a = j; i = j; continue }
            }
            i++
        }
        if (a < s.length) push(a, s.length)
    }
}
