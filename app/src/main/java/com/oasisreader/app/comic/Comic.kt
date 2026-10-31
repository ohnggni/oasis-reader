package com.oasisreader.app.comic

import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/** One image, path relative to the book's content folder, with its pixel size (read once at import). */
@Serializable
data class ComicPage(val path: String, val w: Int, val h: Int)

/** comic.json: everything the webtoon viewer needs. */
@Serializable
data class ComicPackage(
    val title: String,
    val author: String? = null,
    val pages: List<ComicPage>,
)

/** CBZ/ZIP of images (webtoon or manga), optionally with ComicInfo.xml. */
object ComicParser {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")

    fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    /** Natural order: "2.jpg" before "10.jpg", folders compared the same way. */
    val naturalOrder = Comparator<String> { a, b ->
        val ta = tokens(a.lowercase())
        val tb = tokens(b.lowercase())
        for (i in 0 until minOf(ta.size, tb.size)) {
            val x = ta[i]
            val y = tb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ta.size - tb.size
    }

    private fun tokens(s: String): List<String> = Regex("\\d+|\\D+").findAll(s).map { it.value }.toList()

    fun parse(root: File, titleHint: String?, fallbackTitle: String? = null): ComicPackage {
        val files = root.walkTopDown()
            .filter { it.isFile && isImage(it.name) }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .filterNot { p -> p.split('/').any { it.startsWith(".") || it == "__MACOSX" } }
            .sortedWith(naturalOrder)
            .toList()
        val pages = files.mapNotNull { p ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(File(root, p).path, o)
            if (o.outWidth > 0 && o.outHeight > 0) ComicPage(p, o.outWidth, o.outHeight) else null
        }
        if (pages.isEmpty()) throw IOException("이미지가 없는 압축 파일입니다")

        val info = root.walkTopDown().firstOrNull { it.isFile && it.name.equals("ComicInfo.xml", true) }
            ?.let { runCatching { it.readText() }.getOrNull() }
        fun tag(name: String) = info?.let {
            Regex("(?is)<$name>(.*?)</$name>").find(it)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        }
        val series = tag("Series")
        val number = tag("Number")
        val infoTitle = tag("Title")
        // the deepest folder all pages share, e.g. "하즈키/소녀생리학 1권/001.jpg" -> "소녀생리학 1권"
        val commonDir = files.map { it.substringBeforeLast('/', "") }.reduceOrNull { a, b ->
            var x = a
            while (x.isNotEmpty() && b != x && !b.startsWith("$x/")) x = x.substringBeforeLast('/', "")
            x
        }.orEmpty().substringAfterLast('/').ifBlank { null }
        val fromInfo = listOfNotNull(series, number?.let { "${it}권" }).joinToString(" ").ifBlank { null } ?: infoTitle
        var title = fromInfo
            ?: titleHint?.ifBlank { null }
            ?: commonDir
            ?: fallbackTitle?.ifBlank { null }
            ?: "만화"
        // keep the volume number the catalog gave ("[만화] 1") when the better name lacks it
        val vol = fallbackTitle?.let { com.oasisreader.app.opds.OpdsClient.numberIn(it) }
        if (vol != null && title != fallbackTitle && !Regex("(^|\\D)0*$vol(\\D|$)").containsMatchIn(title)) title = "$title ${vol}권"
        return ComicPackage(title = title, author = tag("Writer"), pages = pages)
    }
}
