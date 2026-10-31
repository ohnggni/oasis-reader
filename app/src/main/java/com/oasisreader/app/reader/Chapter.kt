package com.oasisreader.app.reader

import com.oasisreader.app.data.BookStore
import com.oasisreader.app.epub.EpubParser
import java.io.File

/** One spine document, split into what the reader page needs. */
data class ChapterDoc(
    val url: String,                 // base URL (the chapter's own address on the asset host)
    val bodyHtml: String,
    val bodyClass: String,
    val stylesheets: List<String>,   // absolute URLs of the book's CSS files
    val inlineCss: String,           // <style> blocks from the chapter head
)

object ChapterLoader {

    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    )

    private val bodyRe = Regex("(?is)<body\\b([^>]*)>(.*?)</body>")
    private val headRe = Regex("(?is)<head\\b[^>]*>(.*?)</head>")
    private val linkRe = Regex("(?is)<link\\b([^>]*)/?>")
    private val styleRe = Regex("(?is)<style\\b[^>]*>(.*?)</style>")
    private val scriptRe = Regex("(?is)<script\\b.*?</script>|<script\\b[^>]*/>")
    private val selfCloseRe = Regex("<([a-zA-Z][\\w:-]*)(\\s[^<>]*?)?/>")

    private fun attr(attrs: String, name: String): String? =
        Regex("""(?i)(?:^|\s)$name\s*=\s*["']([^"']*)["']""").find(attrs)?.groupValues?.get(1)

    fun load(store: BookStore, bookId: String, path: String): ChapterDoc {
        val file = File(store.contentDir(bookId), path)
        val raw = runCatching { file.readText() }.getOrDefault("")
        val dir = EpubParser.dirOf(path)

        val head = headRe.find(raw)?.groupValues?.get(1).orEmpty()
        val sheets = linkRe.findAll(head).mapNotNull { m ->
            val a = m.groupValues[1]
            val rel = attr(a, "rel").orEmpty().lowercase()
            val href = attr(a, "href") ?: return@mapNotNull null
            if (!rel.contains("stylesheet") || rel.contains("alternate")) return@mapNotNull null
            if (href.startsWith("http:") || href.startsWith("https:")) return@mapNotNull null   // no remote CSS
            store.contentUrl(bookId, EpubParser.resolve(dir, href))
        }.toList()
        val inline = styleRe.findAll(head).joinToString("\n") { it.groupValues[1] }

        val bm = bodyRe.find(raw)
        var body = bm?.groupValues?.get(2) ?: raw.replace(headRe, "")
        val bodyAttrs = bm?.groupValues?.get(1).orEmpty()
        body = body.replace(scriptRe, "")
        // XHTML allows <a id="x"/> or <div/>; an HTML parser would treat those as open tags
        // and swallow everything after them, so expand self-closing non-void elements.
        body = selfCloseRe.replace(body) { m ->
            val tag = m.groupValues[1]
            if (tag.lowercase() in VOID) m.value else "<$tag${m.groupValues[2]}></$tag>"
        }

        return ChapterDoc(
            url = store.contentUrl(bookId, path),
            bodyHtml = body,
            bodyClass = attr(bodyAttrs, "class").orEmpty(),
            stylesheets = sheets,
            inlineCss = inline.replace("</", "<\\/"),
        )
    }
}
