package com.oasisreader.app.reader

import com.oasisreader.app.data.BookStore
import com.oasisreader.app.epub.EpubPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Whole-book text search. Each chapter's text is built the way the page script builds it
 * (the body's text nodes joined, whitespace runs collapsed, lower-cased), so "the n-th match in
 * this chapter" here is the same match the page marks when it opens with find = (query, n).
 */
object BookSearch {

    data class Hit(
        val spineIndex: Int,
        val nth: Int,             // n-th match inside the chapter (0-based)
        val fraction: Float,      // where in the chapter (0..1, by text)
        val before: String,       // a little text around the match, for the list
        val match: String,
        val after: String,
    )

    const val MAX_HITS = 500

    /** The page script's text: body text nodes, tags dropped (not turned into spaces), entities decoded. */
    fun chapterText(html: String): String {
        val body = Regex("(?is)<body\\b[^>]*>(.*)</body>").find(html)?.groupValues?.get(1) ?: html
        return decode(
            body.replace(Regex("(?is)<script\\b.*?</script>|<style\\b.*?</style>|<!--.*?-->"), "")
                .replace(Regex("<[^>]+>"), ""),
        )
    }

    private fun decode(s: String): String = s
        .replace(Regex("&#(x?)([0-9a-fA-F]+);")) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isNotEmpty()) 16 else 10)
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&hellip;", "…").replace("&middot;", "·").replace("&amp;", "&")

    /** Same as the page script's collapse(): whitespace runs become one space, leading whitespace dropped. */
    fun collapse(s: String): String {
        val out = StringBuilder(s.length)
        var prevSpace = true
        for (c in s) {
            if (c.isWhitespace()) { if (!prevSpace) { out.append(' '); prevSpace = true } }
            else { out.append(c); prevSpace = false }
        }
        return out.toString()
    }

    fun normalizeQuery(q: String) = collapse(q).trim().lowercase()

    /** Matches in one chapter's text. */
    fun inText(spineIndex: Int, text: String, query: String, limit: Int = MAX_HITS): List<Hit> {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return emptyList()
        val shown = collapse(text)
        val low = shown.lowercase()
        val hits = ArrayList<Hit>()
        var from = 0
        while (hits.size < limit) {
            val at = low.indexOf(q, from)
            if (at < 0) break
            val end = at + q.length
            hits += Hit(
                spineIndex, hits.size,
                if (low.isEmpty()) 0f else at.toFloat() / low.length,
                shown.substring((at - 28).coerceAtLeast(0), at).trimStart().let { if (at > 28) "…$it" else it },
                shown.substring(at, end),
                shown.substring(end, (end + 48).coerceAtMost(shown.length)).trimEnd().let { if (end + 48 < shown.length) "$it…" else it },
            )
            from = at + 1
        }
        return hits
    }

    /** Every match in the book, chapter by chapter (stops at [MAX_HITS]). */
    suspend fun search(store: BookStore, bookId: String, pkg: EpubPackage, query: String): List<Hit> = withContext(Dispatchers.IO) {
        val out = ArrayList<Hit>()
        val dir = store.contentDir(bookId)
        for ((i, item) in pkg.spine.withIndex()) {
            ensureActive()
            val f = File(dir, item.path)
            if (!f.isFile) continue
            val text = runCatching { chapterText(f.readText()) }.getOrNull() ?: continue
            out += inText(i, text, query, MAX_HITS - out.size)
            if (out.size >= MAX_HITS) break
        }
        out
    }
}
