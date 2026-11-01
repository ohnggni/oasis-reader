package com.oasisreader.app.data

import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.data.db.HighlightEntity
import com.oasisreader.app.epub.EpubPackage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Highlights as text other apps can use.
 *
 * Markdown (one book): a blog-draft shape — title, author, a summary line, then the
 * highlights in reading order under their chapter titles (`##`), each as a `>` quote with
 * its note as a plain paragraph right below. Only syntax that Ghost and most editors
 * turn into headings / quotes / a rule when pasted.
 */
/**
 * What we know about a book besides title/author, gathered from the EPUB's own metadata,
 * the bookoasis meta server and the app's reading records. Every field is optional.
 */
data class BookDetails(
    val series: String? = null,
    val publisher: String? = null,
    val published: String? = null,      // as the EPUB gives it (yyyy or yyyy-MM-dd)
    val subjects: String? = null,
    val description: String? = null,    // full text, paragraphs kept
    val firstReadDay: String? = null,   // yyyy-MM-dd, from the reading-time records
    val lastReadDay: String? = null,
    val readingSeconds: Long = 0,
    val finishedAt: Long? = null,
    val progress: Float? = null,        // 0..1, only for a book that is not finished
) {
    companion object {
        /**
         * Pick the description: the server's synopsis when it is trusted ("ok"), else the
         * EPUB's own; a "suspect" one (likely another book's blurb) is never used.
         */
        fun description(metaSummary: String?, metaTrust: String?, epubDescription: String?): String? =
            metaSummary?.trim()?.takeIf { it.isNotEmpty() && metaTrust == "ok" }
                ?: epubDescription?.trim()?.takeIf { it.isNotEmpty() }

        /** Series from the server, without its "[author]" tail; dropped when it only repeats the title. */
        fun series(metaSeries: String?, title: String): String? {
            val s = metaSeries?.replace(Regex("""\s*\[[^\]]*]\s*$"""), "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return s.takeUnless { it == title.trim() }
        }
    }
}

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
        details: BookDetails = BookDetails(),
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val day = SimpleDateFormat("yyyy.M.d", Locale.KOREA).apply { timeZone = zone }
        fun d(t: Long) = day.format(Date(t))
        fun ymd(v: String): String = v.split('-').let { p -> if (p.size == 3) "${p[0]}.${p[1].toInt()}.${p[2].toInt()}" else v }

        val list = highlights.sortedWith(compareBy<HighlightEntity> { it.spineIndex }.thenBy { it.createdAt })
        val sb = StringBuilder()
        sb.append("# ").append(oneLine(title)).append("\n")
        listOfNotNull(
            author?.let(::oneLine)?.takeIf { it.isNotEmpty() },
            details.publisher?.let(::oneLine)?.takeIf { it.isNotEmpty() },
            details.published?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
        ).takeIf { it.isNotEmpty() }?.let { sb.append(it.joinToString(" · ")).append("\n") }
        sb.append("\n")

        val facts = ArrayList<String>()
        details.series?.let { facts += "시리즈: ${oneLine(it)}" }
        details.subjects?.let(::oneLine)?.takeIf { it.isNotEmpty() }?.let { facts += "분류: $it" }
        run {
            val from = details.firstReadDay?.let(::ymd)
            val to = details.finishedAt?.let { d(it) } ?: details.lastReadDay?.let(::ymd)
            val span = when {
                from != null && to != null && from != to -> "$from ~ $to"
                else -> from ?: to
            }
            val state = when {
                details.finishedAt != null -> "다 읽음"
                details.progress != null && details.progress > 0f -> "${(details.progress * 100).toInt()}% 읽는 중"
                else -> null
            }
            when {
                span != null -> facts += "읽은 기간: $span" + (state?.let { " ($it)" } ?: "")
                state != null -> facts += "읽은 정도: $state"
            }
        }
        readingTime(details.readingSeconds)?.let { facts += "독서 시간: $it" }
        if (list.isNotEmpty()) {
            val notes = list.count { !it.note.isNullOrBlank() }
            facts += "하이라이트 ${list.size}개" + (if (notes > 0) " · 메모 ${notes}개" else "")
        }
        facts.forEach { sb.append("- ").append(it).append("\n") }

        // after "책 소개", quotes from before the first toc entry need a heading of their own
        var needHeading = false
        details.description?.trim()?.takeIf { it.isNotEmpty() }?.let { desc ->
            sb.append("\n## 책 소개\n\n").append(paragraph(desc)).append("\n")
            needHeading = true
        }

        var chapter: String? = null
        for (h in list) {
            val c = chapterOf(h.spineIndex).let(::oneLine)
            if (c.isNotEmpty() && c != chapter) {
                sb.append("\n## ").append(c).append("\n")
            } else if (c.isEmpty() && needHeading) {
                sb.append("\n## 하이라이트\n")
            }
            needHeading = false
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

    /**
     * Every highlight as JSON: one W3C Web Annotation per highlight (TextQuoteSelector =
     * quote + preceding text), grouped by book with every id we know the book by, so a
     * later import can find the book again even if it was re-added under a new id.
     * OG Reader's own values (chapter, colour, raw anchor) sit in an "ogreader" block.
     */
    fun json(
        books: List<BookEntity>,
        highlights: List<HighlightEntity>,
        packageOf: (String) -> EpubPackage?,
        detailsOf: (String) -> BookDetails = { BookDetails() },
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = zone }
        fun t(ms: Long) = iso.format(Date(ms))
        val byId = books.associateBy { it.id }
        val groups = highlights.groupBy { it.bookId }.entries
            .sortedWith(compareBy({ byId[it.key]?.title ?: "\uFFFF" }, { it.key }))
        val root = buildJsonObject {
            put("format", "og-reader-highlights")
            put("version", 1)
            put("exportedAt", t(now))
            put("count", highlights.size)
            putJsonArray("books") {
                for ((bookId, list) in groups) {
                    val b = byId[bookId]
                    val pkg = packageOf(bookId)
                    addJsonObject {
                        put("bookId", bookId)
                        (b?.title ?: pkg?.title)?.let { put("title", it) }
                        (b?.author ?: pkg?.author)?.let { put("author", it) }
                        (b?.identifier ?: pkg?.identifier)?.let { put("identifier", it) }
                        b?.opdsId?.let { put("opdsId", it) }
                        b?.sourceUrl?.let { put("sourceUrl", it) }
                        b?.format?.let { put("bookFormat", it) }
                        if (b == null) put("removed", true)
                        val info = detailsOf(bookId)
                        info.series?.let { put("series", it) }
                        info.publisher?.let { put("publisher", it) }
                        info.published?.let { put("published", it) }
                        info.subjects?.let { put("subjects", it) }
                        info.description?.let { put("description", it) }
                        putJsonObject("reading") {
                            info.firstReadDay?.let { put("firstDay", it) }
                            info.lastReadDay?.let { put("lastDay", it) }
                            if (info.readingSeconds > 0) put("seconds", info.readingSeconds)
                            info.finishedAt?.let { put("finishedAt", t(it)) }
                            info.progress?.let { put("progress", (it * 1000).toInt() / 1000.0) }
                        }
                        putJsonArray("annotations") {
                            list.sortedWith(compareBy<HighlightEntity> { it.spineIndex }.thenBy { it.createdAt })
                                .forEach { h -> add(annotation(h, bookId, pkg, ::t)) }
                        }
                    }
                }
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private fun annotation(h: HighlightEntity, bookId: String, pkg: EpubPackage?, t: (Long) -> String): JsonObject {
        val quote = h.text.trim()
        val note = h.note?.trim()?.takeIf { it.isNotEmpty() }
        return buildJsonObject {
            put("@context", "http://www.w3.org/ns/anno.jsonld")
            put("id", "urn:ogreader:highlight:${h.id}")
            put("type", "Annotation")
            put("motivation", if (note != null) "commenting" else "highlighting")
            put("created", t(h.createdAt))
            if (note != null) putJsonArray("body") {
                addJsonObject {
                    put("type", "TextualBody")
                    put("purpose", "commenting")
                    put("format", "text/plain")
                    put("value", note)
                }
            }
            putJsonObject("target") {
                put("source", "urn:ogreader:book:$bookId")
                putJsonObject("selector") {
                    put("type", "TextQuoteSelector")
                    put("exact", quote)
                    prefixOf(h.anchor, quote)?.let { put("prefix", it) }
                }
            }
            putJsonObject("ogreader") {
                put("spineIndex", h.spineIndex)
                chapterTitle(pkg, h.spineIndex).takeIf { it.isNotEmpty() }?.let { put("chapter", it) }
                put("color", h.color)
                h.anchor?.let { put("anchor", it) }
            }
        }
    }

    /** The anchor is the last 50 characters up to the end of the selection; what comes before the quote is the prefix. */
    internal fun prefixOf(anchor: String?, quote: String): String? {
        val a = anchor?.trimEnd() ?: return null
        if (quote.isEmpty() || !a.endsWith(quote)) return null
        return a.dropLast(quote.length).takeIf { it.isNotBlank() }
    }

    /** File name for a book's export, without characters file pickers reject. */
    fun fileName(title: String, ext: String): String {
        val safe = title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").replace(Regex("\\s+"), " ").trim().take(80)
        return "${safe.ifEmpty { "책" }} - 하이라이트.$ext"
    }

    /** "약 6시간 20분" / "약 35분"; nothing under a minute. */
    internal fun readingTime(seconds: Long): String? {
        val m = seconds / 60
        if (m < 1) return null
        val h = m / 60
        return "약 " + (if (h > 0) "${h}시간" + (if (m % 60 > 0) " ${m % 60}분" else "") else "${m}분")
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** Quote body: keep the reader's line breaks, drop empty lines (they would end the quote). */
    private fun quoteLines(text: String): List<String> =
        text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }

    /** A note stays as written, but blank-line runs collapse to one paragraph break. */
    private fun paragraph(note: String): String =
        note.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n")
}
