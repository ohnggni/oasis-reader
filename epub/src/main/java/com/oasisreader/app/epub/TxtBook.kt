package com.oasisreader.app.epub

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A plain-text book turned into a minimal EPUB folder (container.xml, OPF, nav, one XHTML per
 * chapter) so the normal reader — paging, fonts, highlights, progress — works on it unchanged.
 */
object TxtBook {

    class Chapter(val title: String, val lines: List<String>)

    /** Is this file text at all (not a zip/pdf/image)? Looks at the first few KB. */
    fun looksLikeText(f: File): Boolean {
        val head = f.inputStream().use { s -> ByteArray(8192).let { b -> val n = s.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } }
        if (head.isEmpty()) return false
        if (head.size >= 2 && ((head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) || (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()))) return true
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return false
        if (head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()) return false
        val controls = head.count { b -> val c = b.toInt() and 0xFF; c < 0x09 || (c in 0x0E..0x1F && c != 0x1B) }
        return controls * 100 < head.size   // < 1 % odd control bytes
    }

    /** UTF-8 / UTF-16 (BOM) when the bytes say so, otherwise the Korean legacy encoding (CP949). */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { }
        val legacy = listOf("x-windows-949", "windows-949", "MS949", "CP949", "EUC-KR")
            .firstNotNullOfOrNull { n -> runCatching { Charset.forName(n) }.getOrNull() } ?: Charsets.ISO_8859_1
        return String(bytes, legacy)
    }

    private const val NUM = """[0-9０-９]{1,5}|[一二三四五六七八九十百千]{1,6}"""

    /** Lines that open a chapter: 1화 · 제1장 · 第1章 · Chapter 1 · <1화> · [1] · #1 · 프롤로그 … */
    private val HEADING = listOf(
        Regex("""^(제\s*)?($NUM)\s*(화|장|권|부|편|회|막|절|話|章|回)([\s.:\-–—)\]].*)?$"""),
        Regex("""^第\s*($NUM)\s*[章話话回節节卷部].*$"""),
        Regex("""^(chapter|ch\.|episode|ep\.|part)\s*($NUM)\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""^[<\[〈《【(]\s*(제\s*)?($NUM)\s*(화|장|회|話)?\s*[>\]〉》】)].{0,40}$"""),
        Regex("""^#\s*($NUM)(\s.*)?$"""),
        Regex("""^(프롤로그|에필로그|외전|서장|종장|序章|終章|후기|작가\s*후기|작가의\s*말|prologue|epilogue|interlude)(\s*[.:\-–—]?\s*.*)?$""", RegexOption.IGNORE_CASE),
    )

    fun isHeading(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 50) return false
        return HEADING.any { it.matches(t) }
    }

    private const val TARGET = 20_000          // chars per part when there are no headings
    private const val MAX_CHAPTER = 80_000     // longer chapters are cut into parts (paging stays quick)

    fun split(text: String): List<Chapter> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trimEnd() }
        val heads = lines.indices.filter { isHeading(lines[it]) }
        val raw = ArrayList<Chapter>()
        if (heads.size >= 3) {
            val before = lines.subList(0, heads[0])
            if (before.any { it.isNotBlank() }) raw.add(Chapter("처음", before))
            heads.forEachIndexed { i, h ->
                val end = if (i + 1 < heads.size) heads[i + 1] else lines.size
                raw.add(Chapter(lines[h].trim(), lines.subList(h + 1, end)))
            }
        } else {
            raw.add(Chapter("", lines))
        }
        // cut long chapters (or the whole text) at blank lines
        val out = ArrayList<Chapter>()
        for (c in raw) {
            val limit = if (c.title.isEmpty()) TARGET else MAX_CHAPTER
            if (c.lines.sumOf { it.length + 1 } <= limit) { out.add(c); continue }
            val parts = ArrayList<List<String>>()
            var cur = ArrayList<String>(); var size = 0
            for (l in c.lines) {
                cur.add(l); size += l.length + 1
                if (size >= (if (c.title.isEmpty()) TARGET else TARGET * 2) && l.isBlank()) { parts.add(cur); cur = ArrayList(); size = 0 }
                else if (size >= limit * 2) { parts.add(cur); cur = ArrayList(); size = 0 }   // no blank lines at all
            }
            if (cur.any { it.isNotBlank() }) parts.add(cur)
            parts.forEachIndexed { i, p ->
                val t = if (c.title.isEmpty()) "${i + 1}" else if (i == 0) c.title else "${c.title} (${i + 1})"
                out.add(Chapter(t, p))
            }
        }
        return out.filter { ch -> ch.lines.any { it.isNotBlank() } || ch.title.isNotEmpty() }
            .ifEmpty { listOf(Chapter("", listOf(""))) }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Write the EPUB folder for [src] into [into]. */
    fun write(src: File, into: File, title: String) {
        if (src.length() > 64L * 1024 * 1024) throw IOException("텍스트 파일이 너무 큽니다 (64MB 넘음)")
        val chapters = split(decode(src.readBytes()))
        val book = title.ifBlank { "제목 없음" }
        into.mkdirs()
        File(into, "META-INF").mkdirs()
        File(into, "mimetype").writeText("application/epub+zip")
        File(into, "META-INF/container.xml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""")
        val dir = File(into, "OEBPS").apply { mkdirs() }
        val names = chapters.indices.map { "c%05d.xhtml".format(it + 1) }
        chapters.forEachIndexed { i, c ->
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="UTF-8"/><title>""").append(esc(c.title.ifEmpty { book })).append("</title></head><body>\n")
            if (c.title.isNotEmpty() && !(chapters.size == 1)) sb.append("<h2>").append(esc(c.title)).append("</h2>\n")
            for (l in c.lines) {
                val t = l.trim()
                if (t.isNotEmpty()) sb.append("<p>").append(esc(t)).append("</p>\n")
            }
            sb.append("</body></html>\n")
            File(dir, names[i]).writeText(sb.toString())
        }
        val nav = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><meta charset="UTF-8"/><title>목차</title></head><body>
<nav epub:type="toc"><ol>
""")
        chapters.forEachIndexed { i, c -> nav.append("<li><a href=\"").append(names[i]).append("\">").append(esc(c.title.ifEmpty { book })).append("</a></li>\n") }
        nav.append("</ol></nav></body></html>\n")
        File(dir, "nav.xhtml").writeText(nav.toString())
        val opf = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">txt:""").append(esc(book)).append("""</dc:identifier>
<dc:title>""").append(esc(book)).append("""</dc:title>
<dc:language>ko</dc:language>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
""")
        names.forEachIndexed { i, n -> opf.append("<item id=\"c${i + 1}\" href=\"$n\" media-type=\"application/xhtml+xml\"/>\n") }
        opf.append("</manifest>\n<spine>\n")
        names.indices.forEach { i -> opf.append("<itemref idref=\"c${i + 1}\"/>\n") }
        opf.append("</spine>\n</package>\n")
        File(dir, "content.opf").writeText(opf.toString())
    }
}
