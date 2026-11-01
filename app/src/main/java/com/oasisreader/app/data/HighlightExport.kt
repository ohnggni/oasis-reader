package com.oasisreader.app.data

import com.oasisreader.app.data.db.HighlightEntity
import com.oasisreader.app.epub.EpubPackage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Highlights as text other apps can use.
 *
 * Markdown (one book): a blog-draft shape — title, author, a summary line, then the
 * highlights in reading order under their chapter titles (`##`), each as a `>` quote with
 * its note as a plain paragraph right below. Only syntax that Ghost and most editors
 * turn into headings / quotes / a rule when pasted.
 */
object HighlightExport {

    /** Title of the toc entry a spine index falls under (the nearest one at or before it). */
    fun chapterTitle(pkg: EpubPackage?, spineIndex: Int): String {
        if (pkg == null) return ""
        var best: String? = null
        var bestIdx = -1
        for (t in pkg.toc) {
            val i = pkg.spineIndexOf(t.path)
            if (i in 0..spineIndex && i >= bestIdx) { best = t.title; bestIdx = i }
        }
        return best.orEmpty().trim()
    }

    fun markdown(
        title: String,
        author: String?,
        highlights: List<HighlightEntity>,
        chapterOf: (Int) -> String,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val day = SimpleDateFormat("yyyy.M.d", Locale.KOREA).apply { timeZone = zone }
        fun d(t: Long) = day.format(Date(t))

        val list = highlights.sortedWith(compareBy<HighlightEntity> { it.spineIndex }.thenBy { it.createdAt })
        val sb = StringBuilder()
        sb.append("# ").append(oneLine(title)).append("\n")
        author?.let(::oneLine)?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append("\n") }
        sb.append("\n")

        if (list.isNotEmpty()) {
            val notes = list.count { !it.note.isNullOrBlank() }
            val first = d(list.minOf { it.createdAt })
            val last = d(list.maxOf { it.createdAt })
            sb.append("하이라이트 ").append(list.size).append("개")
            if (notes > 0) sb.append(" · 메모 ").append(notes).append("개")
            sb.append(" · ").append(if (first == last) first else "$first ~ $last")
            sb.append("\n")
        }

        var chapter: String? = null
        for (h in list) {
            val c = chapterOf(h.spineIndex).let(::oneLine)
            if (c.isNotEmpty() && c != chapter) {
                sb.append("\n## ").append(c).append("\n")
            }
            if (c.isNotEmpty()) chapter = c
            sb.append("\n")
            quoteLines(h.text).forEach { sb.append("> ").append(it).append("\n") }
            h.note?.trim()?.takeIf { it.isNotEmpty() }?.let { note ->
                sb.append("\n").append(paragraph(note)).append("\n")
            }
        }

        sb.append("\n---\n").append("OG Reader에서 내보냄 · ").append(d(now)).append("\n")
        return sb.toString()
    }

    /** File name for a book's export, without characters file pickers reject. */
    fun fileName(title: String, ext: String): String {
        val safe = title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").replace(Regex("\\s+"), " ").trim().take(80)
        return "${safe.ifEmpty { "책" }} - 하이라이트.$ext"
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** Quote body: keep the reader's line breaks, drop empty lines (they would end the quote). */
    private fun quoteLines(text: String): List<String> =
        text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }

    /** A note stays as written, but blank-line runs collapse to one paragraph break. */
    private fun paragraph(note: String): String =
        note.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n")
}
