package com.oasisreader.app.epub

import android.net.Uri
import android.util.Xml
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException

/** One reading-order document. [path] is relative to the book's content folder. */
@Serializable
data class SpineItem(
    val path: String,
    val mediaType: String = "application/xhtml+xml",
    val chars: Int = 1,          // visible text length, for overall progress / page estimates
    val linear: Boolean = true,
)

/** Table of contents entry. [path] relative to the content folder, [fragment] without '#'. */
@Serializable
data class TocItem(
    val title: String,
    val path: String,
    val fragment: String? = null,
    val level: Int = 0,
)

/** Everything the app needs from the OPF/nav, parsed once at import (book.json). */
@Serializable
data class EpubPackage(
    val title: String,
    val author: String? = null,
    val language: String? = null,
    val identifier: String? = null,
    val spine: List<SpineItem>,
    val toc: List<TocItem> = emptyList(),
    val coverPath: String? = null,
) {
    // sums[i] = chars before spine index i (sums[size] = all), added up once: overall() runs on
    // every page turn, and web novels have thousands of chapters
    private val sums: LongArray by lazy {
        LongArray(spine.size + 1).also { a -> for (i in spine.indices) a[i + 1] = a[i] + spine[i].chars.toLong() }
    }

    val totalChars: Long get() = sums[spine.size].coerceAtLeast(1)

    /** chars before spine index i */
    fun charsBefore(i: Int): Long = sums[i.coerceIn(0, spine.size)]

    fun spineIndexOf(path: String): Int = spine.indexOfFirst { it.path == path }

    /** Overall reading fraction for (chapter, fraction inside the chapter). */
    fun overall(index: Int, fraction: Float): Float {
        if (spine.isEmpty()) return 0f
        val i = index.coerceIn(0, spine.size - 1)
        val done = charsBefore(i) + spine[i].chars * fraction.coerceIn(0f, 1f)
        return (done / totalChars).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * EPUB 2/3 reader for an already-extracted book folder.
 * container.xml -> OPF (metadata, manifest, spine) -> nav.xhtml (EPUB3) or toc.ncx (EPUB2).
 */
object EpubParser {

    private class ManifestItem(val id: String, val path: String, val mediaType: String, val properties: String)

    fun parse(root: File): EpubPackage {
        val container = File(root, "META-INF/container.xml")
        if (!container.isFile) throw IOException("EPUB이 아닙니다 (container.xml 없음)")
        val opfPath = findRootfile(container.readText()) ?: throw IOException("OPF 위치를 찾을 수 없습니다")
        val opfFile = File(root, opfPath)
        if (!opfFile.isFile) throw IOException("OPF 파일이 없습니다: $opfPath")
        val opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        opfFile.inputStream().use { input ->
            p.setInput(input, null)

            var title: String? = null
            val creators = ArrayList<String>()
            var language: String? = null
            var identifier: String? = null
            var coverId: String? = null
            var tocId: String? = null
            val manifest = LinkedHashMap<String, ManifestItem>()
            val spineRefs = ArrayList<Pair<String, Boolean>>()
            var inMetadata = false

            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.END_TAG && p.name == "metadata") inMetadata = false
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "metadata" -> inMetadata = true
                    "title" -> if (inMetadata && title == null) title = text(p)
                    "creator" -> if (inMetadata) text(p)?.let { creators.add(it) }
                    "language" -> if (inMetadata && language == null) language = text(p)
                    "identifier" -> if (inMetadata && identifier == null) identifier = text(p)
                    "meta" -> if (inMetadata && p.getAttributeValue(null, "name") == "cover") {
                        coverId = p.getAttributeValue(null, "content")
                    }
                    "item" -> {
                        val id = p.getAttributeValue(null, "id") ?: continue
                        val href = p.getAttributeValue(null, "href") ?: continue
                        manifest[id] = ManifestItem(
                            id = id,
                            path = resolve(opfDir, href),
                            mediaType = p.getAttributeValue(null, "media-type").orEmpty(),
                            properties = p.getAttributeValue(null, "properties").orEmpty(),
                        )
                    }
                    "spine" -> tocId = p.getAttributeValue(null, "toc")
                    "itemref" -> {
                        val idref = p.getAttributeValue(null, "idref") ?: continue
                        spineRefs.add(idref to (p.getAttributeValue(null, "linear") != "no"))
                    }
                }
            }

            val spine = spineRefs.mapNotNull { (idref, linear) ->
                val m = manifest[idref] ?: return@mapNotNull null
                if (!File(root, m.path).isFile) return@mapNotNull null
                SpineItem(m.path, m.mediaType.ifEmpty { "application/xhtml+xml" }, visibleChars(File(root, m.path)), linear)
            }
            if (spine.isEmpty()) throw IOException("읽을 본문이 없습니다 (spine 비어 있음)")

            val navItem = manifest.values.firstOrNull { "nav" in it.properties.split(' ') }
            val ncxItem = tocId?.let { manifest[it] }
                ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            val toc = runCatching { navItem?.let { parseNav(root, it.path) } }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: runCatching { ncxItem?.let { parseNcx(root, it.path) } }.getOrNull()
                ?: emptyList()

            val cover = manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') }
                ?: coverId?.let { manifest[it] }?.takeIf { it.mediaType.startsWith("image/") }
                ?: manifest.values.firstOrNull { it.mediaType.startsWith("image/") && (it.id.contains("cover", true) || it.path.contains("cover", true)) }
            val coverPath = cover?.path?.takeIf { File(root, it).isFile }
                ?: firstImageIn(root, spine.first().path)

            return EpubPackage(
                title = title?.ifBlank { null } ?: opfFile.parentFile?.name ?: "제목 없음",
                author = creators.joinToString(", ").ifBlank { null },
                language = language,
                identifier = identifier,
                spine = spine,
                toc = toc,
                coverPath = coverPath,
            )
        }
    }

    private fun text(p: XmlPullParser): String? =
        runCatching { p.nextText() }.getOrNull()?.trim()?.ifBlank { null }

    /** Extra OPF metadata for the book info sheet (publisher, date, language, subjects, description). */
    fun extraMeta(root: File): Map<String, String> = runCatching {
        val container = File(root, "META-INF/container.xml").readText()
        val opf = File(root, findRootfile(container) ?: return@runCatching emptyMap()).readText()
        fun all(tag: String) = Regex("""<dc:$tag\b[^>]*>([\s\S]*?)</dc:$tag>""", RegexOption.IGNORE_CASE)
            .findAll(opf).map { it.groupValues[1].replace(Regex("<[^>]+>"), " ").replace(Regex("&lt;[^&]*&gt;"), " ")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }.toList()
        buildMap {
            all("publisher").firstOrNull()?.let { put("출판사", it) }
            all("date").firstOrNull()?.let { put("발행일", it.take(10)) }
            all("language").firstOrNull()?.let { put("언어", it) }
            all("subject").takeIf { it.isNotEmpty() }?.let { put("분류", it.joinToString(", ")) }
            all("description").firstOrNull()?.let { put("소개", it) }
        }
    }.getOrDefault(emptyMap())

    private fun findRootfile(xml: String): String? =
        Regex("""<(?:\w+:)?rootfile\b[^>]*\bfull-path\s*=\s*["']([^"']+)["']""").find(xml)?.groupValues?.get(1)
            ?.let { Uri.decode(it) }

    /** Resolve an href (URL-encoded, maybe with ../ or #frag) against a folder path ("OEBPS/Text/"). */
    fun resolve(dir: String, href: String): String {
        val clean = Uri.decode(href.substringBefore('#').substringBefore('?'))
        if (clean.isEmpty()) return dir.trimEnd('/')
        val parts = ArrayList<String>()
        (if (clean.startsWith("/")) clean.trimStart('/') else dir + clean).split('/').forEach { seg ->
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    fun dirOf(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

    // ── visible text ──────────────────────────────────────────────

    private val tagRe = Regex("<[^>]*>")
    private val headRe = Regex("(?is)<head\\b.*?</head>|<script\\b.*?</script>|<style\\b.*?</style>")
    private val entityRe = Regex("&#?\\w+;")

    private fun visibleChars(f: File): Int = runCatching {
        val s = f.readText()
        val body = s.replace(headRe, " ").replace(tagRe, " ").replace(entityRe, "x")
        var n = 0
        for (c in body) if (!c.isWhitespace()) n++
        // images-only pages still take a page or so
        maxOf(n, 1) + Regex("(?i)<(img|image|svg)\\b").findAll(s).count() * 300
    }.getOrDefault(1)

    private fun firstImageIn(root: File, docPath: String): String? = runCatching {
        val html = File(root, docPath).readText()
        val m = Regex("""(?i)<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""").find(html)
            ?: Regex("""(?i)<image\b[^>]*\bhref\s*=\s*["']([^"']+)["']""").find(html)
        m?.groupValues?.get(1)?.let { resolve(dirOf(docPath), it) }?.takeIf { File(root, it).isFile }
    }.getOrNull()

    // ── table of contents ────────────────────────────────────────

    private val tokenRe = Regex("""<(/?)([a-zA-Z][\w:.-]*)([^>]*?)(/?)>|([^<]+)""")
    private fun attr(attrs: String, name: String): String? =
        Regex("""(?i)(?:^|\s)$name\s*=\s*["']([^"']*)["']""").find(attrs)?.groupValues?.get(1)

    private fun unescape(s: String) = s
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim()

    private fun tocItem(base: String, title: String, href: String, level: Int): TocItem? {
        if (href.isBlank() || title.isBlank()) return null
        val frag = href.substringAfter('#', "").ifEmpty { null }
        val path = if (href.startsWith("#")) base else resolve(dirOf(base), href)
        return TocItem(unescape(title), path, frag?.let { Uri.decode(it) }, level.coerceAtLeast(0))
    }

    /** EPUB3 nav document: <nav epub:type="toc"><ol><li><a href>…</a><ol>…</ol></li></ol></nav> */
    private fun parseNav(root: File, navPath: String): List<TocItem> {
        val html = File(root, navPath).readText()
        val navs = Regex("""(?is)<nav\b([^>]*)>(.*?)</nav>""").findAll(html).toList()
        val nav = navs.firstOrNull { (attr(it.groupValues[1], "epub:type") ?: "").contains("toc") }
            ?: navs.firstOrNull() ?: return emptyList()
        val out = ArrayList<TocItem>()
        var depth = 0
        var href: String? = null
        val text = StringBuilder()
        for (m in tokenRe.findAll(nav.groupValues[2])) {
            val close = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val raw = m.groupValues[5]
            when {
                raw.isNotEmpty() -> if (href != null) text.append(raw)
                name == "ol" -> depth += if (close) -1 else 1
                name == "a" && !close -> { href = attr(m.groupValues[3], "href"); text.clear() }
                name == "a" && close -> {
                    href?.let { h -> tocItem(navPath, text.toString(), h, depth - 1)?.let { out.add(it) } }
                    href = null
                }
            }
        }
        return out
    }

    /** EPUB2 NCX: nested <navPoint><navLabel><text>…</text></navLabel><content src=…/></navPoint> */
    private fun parseNcx(root: File, ncxPath: String): List<TocItem> {
        val xml = File(root, ncxPath).readText()
        val out = ArrayList<TocItem>()
        var depth = 0
        var inText = false
        var label = StringBuilder()
        var pendingLabel: String? = null
        for (m in tokenRe.findAll(xml)) {
            val close = m.groupValues[1] == "/"
            val selfClose = m.groupValues[4] == "/"
            val name = m.groupValues[2].substringAfter(':').lowercase()
            val raw = m.groupValues[5]
            when {
                raw.isNotEmpty() -> if (inText) label.append(raw)
                name == "navpoint" && !close -> { depth++; pendingLabel = null }
                name == "navpoint" && close -> depth--
                name == "text" && !close && !selfClose -> { inText = true; label = StringBuilder() }
                name == "text" && close -> { inText = false; if (pendingLabel == null) pendingLabel = label.toString() }
                name == "content" && !close -> {
                    val src = attr(m.groupValues[3], "src")
                    if (src != null && depth > 0) {
                        tocItem(ncxPath, pendingLabel.orEmpty(), src, depth - 1)?.let { out.add(it) }
                    }
                }
            }
        }
        return out
    }
}
