package com.oasisreader.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.oasisreader.app.comic.ComicPackage
import com.oasisreader.app.comic.ComicParser
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookEntity
import com.oasisreader.app.epub.EpubPackage
import com.oasisreader.app.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Books live in filesDir/books/<id>/:
 *   book.epub | book.cbz   the original file (kept for backup / device moves)
 *   content/    the extracted archive (EPUB served to the reader WebView, or webtoon images)
 *   book.json   parsed EPUB package (spine, toc, char counts)   — EPUB
 *   comic.json  image list with sizes                           — CBZ/ZIP
 *   cover.jpg   thumbnail for the library
 */
class BookStore(private val context: Context) {

    val root = File(context.filesDir, "books").apply { mkdirs() }
    private val db get() = AppDatabase.get(context)

    fun dir(id: String) = File(root, id)
    fun contentDir(id: String) = File(dir(id), "content")
    fun coverFile(id: String) = File(dir(id), "cover.jpg")

    /** WebView URL of a file inside the book's content folder. */
    fun contentUrl(id: String, path: String): String =
        "https://$ASSET_HOST/books/$id/content/" + path.split('/').joinToString("/") { Uri.encode(it) }

    private val packageCache = HashMap<String, EpubPackage>()

    fun readPackage(id: String): EpubPackage = synchronized(packageCache) {
        packageCache.getOrPut(id) {
            Http.json.decodeFromString(EpubPackage.serializer(), File(dir(id), "book.json").readText())
        }
    }

    private val comicCache = HashMap<String, ComicPackage>()

    fun readComic(id: String): ComicPackage = synchronized(comicCache) {
        comicCache.getOrPut(id) {
            Http.json.decodeFromString(ComicPackage.serializer(), File(dir(id), "comic.json").readText())
        }
    }

    /** Copy a picked file (SAF) into the cache, then import it. The file name is the title hint for comics. */
    suspend fun importUri(uri: Uri): BookEntity = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "import_${System.nanoTime()}.bin")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("파일을 열 수 없습니다")
            import(tmp, sourceUrl = null, opdsId = null, titleHint = displayName(uri)?.substringBeforeLast('.'))
        } finally {
            tmp.delete()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** What the archive holds: an EPUB (container.xml) or a comic (images). */
    private fun detectFormat(content: File): String =
        if (File(content, "META-INF/container.xml").isFile) FORMAT_EPUB else FORMAT_COMIC

    private fun originalName(format: String) = when (format) {
        FORMAT_COMIC -> "book.cbz"
        FORMAT_TXT -> "book.txt"
        FORMAT_PDF -> com.oasisreader.app.comic.PdfPages.FILE
        else -> "book.epub"
    }

    /** The kept original of a book, whatever its kind. */
    fun originalOf(id: String): File? = ORIGINALS.map { File(dir(id), it) }.firstOrNull { it.isFile }

    /**
     * Unpack a book file into [into]: EPUB/CBZ/ZIP are extracted, a text file is turned into
     * an EPUB folder. Returns the book's format.
     */
    fun extract(src: File, into: File, title: String?): String {
        if (com.oasisreader.app.comic.PdfPages.isPdf(src)) {
            into.mkdirs()   // nothing to unpack: pages are drawn from book.pdf beside it
            return FORMAT_PDF
        }
        if (com.oasisreader.app.epub.TxtBook.looksLikeText(src)) {
            com.oasisreader.app.epub.TxtBook.write(src, into, title ?: "")
            return FORMAT_TXT
        }
        unzipSafely(src, into)
        return detectFormat(into)
    }

    /** Parse an extracted archive into [into] (book.json / comic.json + cover). */
    private fun describe(format: String, into: File, content: File, titleHint: String?, fallbackTitle: String? = null): Described {
        if (format == FORMAT_PDF) {
            val pdf = File(content.parentFile, com.oasisreader.app.comic.PdfPages.FILE)
            val pkg = com.oasisreader.app.comic.ComicPackage(
                title = titleHint?.ifBlank { null } ?: fallbackTitle ?: "PDF",
                pages = com.oasisreader.app.comic.PdfPages.pages(pdf),
            )
            File(into, "comic.json").writeText(Http.json.encodeToString(ComicPackage.serializer(), pkg))
            val cover = runCatching {
                val p0 = pkg.pages.first()
                val bmp = com.oasisreader.app.comic.PdfPages.render(pdf, 0, (THUMB_H * p0.w.toFloat() / p0.h).toInt().coerceIn(100, 1200))
                File(into, "cover.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }.isSuccess
            return Described(pkg.title, null, null, null, cover)
        }
        return if (format != FORMAT_COMIC) {
            val pkg = EpubParser.parse(content)
            File(into, "book.json").writeText(Http.json.encodeToString(EpubPackage.serializer(), pkg))
            val cover = pkg.coverPath?.let { makeThumb(File(content, it), File(into, "cover.jpg")) } == true
            // "unknown title" & co. in the OPF: the catalog's name or the file name is better
            val title = pkg.title.takeUnless { com.oasisreader.app.opds.OpdsClient.isPoorTitle(it) }
                ?: titleHint?.takeUnless { com.oasisreader.app.opds.OpdsClient.isPoorTitle(it) }
                ?: fallbackTitle ?: pkg.title
            Described(title, pkg.author, pkg.language, pkg.identifier, cover)
        } else {
            val pkg = ComicParser.parse(content, titleHint, fallbackTitle)
            File(into, "comic.json").writeText(Http.json.encodeToString(ComicPackage.serializer(), pkg))
            val cover = makeComicThumb(File(content, pkg.pages.first().path), File(into, "cover.jpg"))
            Described(pkg.title, pkg.author, null, null, cover)
        }
    }

    private class Described(val title: String, val author: String?, val language: String?, val identifier: String?, val hasCover: Boolean)

    /** Extract, parse and register an EPUB or CBZ/ZIP. The file itself is copied into the book folder. */
    suspend fun import(
        file: File,
        sourceUrl: String?,
        opdsId: String?,
        titleHint: String? = null,
        fallbackTitle: String? = null,
        pinned: Boolean = true,
        moveSource: Boolean = false,
    ): BookEntity = withContext(Dispatchers.IO) {
        if (opdsId != null || sourceUrl != null) {
            db.books().findBySource(opdsId, sourceUrl)?.let { old ->
                if (pinned && !old.pinned) db.books().setPinned(old.id, true)
                // a cached book whose file was dropped: put the file back
                if (!hasFiles(old.id)) rebuild(old.id, file)
                return@withContext old.copy(pinned = old.pinned || pinned)
            }
        }
        val id = newId()
        val tmp = File(root, "$id.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            val content = File(tmp, "content")
            val format = extract(file, content, titleHint ?: fallbackTitle)
            val fileBytes = file.length()
            // move, not copy, when the source is a throwaway download (big PDFs: no second copy)
            val kept = File(tmp, originalName(format))
            if (!(moveSource && file.renameTo(kept))) file.copyTo(kept, overwrite = true)   // before describe: a PDF is read from it
            val d = describe(format, tmp, content, titleHint, fallbackTitle)
            if (!tmp.renameTo(dir(id))) throw IOException("책을 저장하지 못했습니다")
            val book = BookEntity(
                id = id,
                title = d.title,
                author = d.author,
                language = d.language,
                identifier = d.identifier,
                addedAt = System.currentTimeMillis(),
                sourceUrl = sourceUrl,
                opdsId = opdsId,
                fileBytes = fileBytes,
                hasCover = d.hasCover,
                format = format,
                pinned = pinned,
                kosyncDoc = runCatching { KoSync.partialMd5(kept) }.getOrNull(),
            )
            db.books().insert(book)
            reattachHighlights(book)
            book
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** The same book as one removed earlier (same source, or same identifier): its kept highlights come back. */
    private suspend fun reattachHighlights(book: BookEntity) {
        val match = db.removedBooks().all().firstOrNull { r ->
            (book.opdsId != null && r.opdsId == book.opdsId) ||
                (book.sourceUrl != null && r.sourceUrl == book.sourceUrl) ||
                (!book.identifier.isNullOrBlank() && r.identifier == book.identifier && r.format == book.format)
        } ?: return
        db.highlights().moveBook(match.id, book.id)
        db.removedBooks().delete(match.id)
    }

    /** Book files missing (e.g. restored from a backup without books)? */
    fun hasFiles(id: String) =
        (File(dir(id), "book.json").isFile || File(dir(id), "comic.json").isFile) && contentDir(id).isDirectory

    /** Re-extract content/ from the kept original (after a restore) — or from a freshly downloaded file. */
    suspend fun rebuild(id: String, downloaded: File? = null) = withContext(Dispatchers.IO) {
        val d = dir(id).apply { mkdirs() }
        val book = db.books().get(id)
        val source = downloaded
            ?: originalOf(id)
            ?: throw IOException("책 파일이 없습니다")
        val tmp = File(d, "content.tmp").apply { deleteRecursively() }
        val format = extract(source, tmp, book?.title)
        if (downloaded != null) downloaded.copyTo(File(d, originalName(format)), overwrite = true)
        if (format == FORMAT_PDF) com.oasisreader.app.comic.PdfPages.close()
        val described = describe(format, d, tmp, book?.title)
        contentDir(id).deleteRecursively()
        if (!tmp.renameTo(contentDir(id))) throw IOException("책을 저장하지 못했습니다")
        synchronized(packageCache) { packageCache.remove(id) }
        synchronized(comicCache) { comicCache.remove(id) }
        book?.let {
            val doc = it.kosyncDoc ?: runCatching { KoSync.partialMd5(source) }.getOrNull()
            db.books().insert(it.copy(hasCover = described.hasCover, fileBytes = source.length(), format = format, kosyncDoc = doc))
        }
    }

    /**
     * Remove a book from the 서재. With [keepHighlights] (and highlights to keep) its highlights
     * stay, with the title, author and chapter titles they need, in removed_books.
     */
    suspend fun delete(id: String, keepHighlights: Boolean = false) = withContext(Dispatchers.IO) {
        val keep = keepHighlights && db.highlights().countFor(id) > 0
        if (keep) db.books().get(id)?.let { b ->
            val pkg = if (File(dir(id), "book.json").isFile) runCatching { readPackage(id) }.getOrNull() else null
            val chapters = db.highlights().allFor(id).map { it.spineIndex }.distinct()
                .associate { i -> i.toString() to HighlightExport.chapterTitle(pkg, i) }
            db.removedBooks().insert(
                com.oasisreader.app.data.db.RemovedBookEntity(
                    id = id, title = b.title, author = b.author, format = b.format,
                    chapters = kotlinx.serialization.json.JsonObject(chapters.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString(),
                    opdsId = b.opdsId, sourceUrl = b.sourceUrl, identifier = b.identifier, removedAt = System.currentTimeMillis(),
                ),
            )
        }
        if (!keep) db.highlights().deleteForBook(id)
        db.bookmarks().deleteForBook(id)
        db.collections().unlinkBook(id)
        db.books().delete(id)
        synchronized(packageCache) { packageCache.remove(id) }
        synchronized(comicCache) { comicCache.remove(id) }
        dir(id).deleteRecursively()
        File(context.filesDir, "media/$id").deleteRecursively()   // an audiobook kept on the phone
    }

    /** Bytes the book's files take (original + unpacked content). */
    fun filesBytes(id: String): Long =
        (ORIGINALS.map { File(dir(id), it) } + contentDir(id)).sumOf { f -> if (f.exists()) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L }

    /** Cached ("바로 읽기") books and the space their files take. */
    suspend fun cacheUsage(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val list = db.books().all().filter { !it.pinned && hasFiles(it.id) }
        list.size to list.sumOf { filesBytes(it.id) }
    }

    /**
     * Keep cached books under [limitBytes]: drop the files of the ones read longest ago
     * (never [keep]). Their row, position, highlights and cover stay; opening fetches the file again.
     * [limitBytes] = 0 empties the cache.
     */
    suspend fun trimCache(limitBytes: Long, keep: String? = null): Int = withContext(Dispatchers.IO) {
        val cached = db.books().all().filter { !it.pinned && hasFiles(it.id) }
            .sortedBy { it.lastReadAt ?: it.addedAt }
            .map { it to filesBytes(it.id) }
        var total = cached.sumOf { it.second }
        var dropped = 0
        for ((b, size) in cached) {
            if (total <= limitBytes) break
            if (b.id == keep) continue
            dropFiles(b.id)
            total -= size; dropped++
        }
        dropped
    }

    /**
     * 폰에서 지우기 (보관함 관리): the book's file goes, the 서재 entry, place and highlights stay
     * (☁ — it is received again when opened). Only for books that came from a server.
     */
    suspend fun removeFiles(id: String): Boolean = withContext(Dispatchers.IO) {
        val b = db.books().get(id) ?: return@withContext false
        if (b.sourceUrl == null) return@withContext false
        dropFiles(id)
        db.books().setPinned(id, false)
        true
    }

    private fun dropFiles(id: String) {
        synchronized(packageCache) { packageCache.remove(id) }
        synchronized(comicCache) { comicCache.remove(id) }
        if (originalOf(id)?.name == com.oasisreader.app.comic.PdfPages.FILE) com.oasisreader.app.comic.PdfPages.close()
        contentDir(id).deleteRecursively()
        ORIGINALS.forEach { File(dir(id), it).delete() }
    }

    private fun newId(): String =
        java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e9).toLong(), 36)

    /**
     * Extract every entry, but never outside [into] (zip-slip) and within sane limits
     * (entry count / total size) so a broken or hostile file cannot fill the phone.
     */
    fun unzipSafely(zip: File, into: File, maxBytes: Long = MAX_TOTAL_BYTES) {
        into.mkdirs()
        val base = into.canonicalPath + File.separator
        var total = 0L
        var count = 0
        try {
            ZipFile(zip).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (++count > MAX_ENTRIES) throw IOException("파일 개수가 너무 많습니다")
                    val name = e.name.replace('\\', '/')
                    if (e.isDirectory || name.endsWith("/")) continue
                    if (name.startsWith("/") || name.split('/').any { it == ".." }) continue
                    val out = File(into, name)
                    if (!out.canonicalPath.startsWith(base)) continue
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) throw IOException("압축을 푼 용량이 너무 큽니다")
                                o.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw IOException("EPUB, CBZ/ZIP, TXT, PDF 파일이 아니거나 손상되었습니다")
        }
    }

    /** Scale the cover to ~600px tall, JPEG. */
    private fun makeThumb(src: File, dest: File): Boolean {
        if (!src.isFile) return false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= THUMB_H) sample *= 2
            val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return false
            val scaled = if (bmp.height > THUMB_H) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * THUMB_H.toFloat() / bmp.height).toInt().coerceAtLeast(1), THUMB_H, true)
            } else bmp
            dest.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Webtoon pages are very tall: take the top part of the first page in cover proportions. */
    private fun makeComicThumb(src: File, dest: File): Boolean {
        return try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, b)
            val w = b.outWidth
            val h = b.outHeight
            if (w <= 0 || h <= 0) return false
            val cropH = minOf(h, (w / 0.68f).toInt())
            val decoder = if (android.os.Build.VERSION.SDK_INT >= 31) android.graphics.BitmapRegionDecoder.newInstance(src.path)
                          else @Suppress("DEPRECATION") android.graphics.BitmapRegionDecoder.newInstance(src.path, false)
            var sample = 1
            while (cropH / (sample * 2) >= THUMB_H) sample *= 2
            val bmp = decoder.decodeRegion(android.graphics.Rect(0, 0, w, cropH), BitmapFactory.Options().apply { inSampleSize = sample })
            decoder.recycle()
            if (bmp == null) return makeThumb(src, dest)
            dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            true
        } catch (e: Exception) {
            makeThumb(src, dest)   // formats the region decoder cannot read (e.g. gif)
        }
    }

    companion object {
        const val FORMAT_EPUB = "epub"
        const val FORMAT_COMIC = "cbz"
        const val FORMAT_TXT = "txt"      // read with the EPUB reader (converted at import)
        const val FORMAT_PDF = "pdf"      // read with the comic viewer (pages drawn from book.pdf)
        val ORIGINALS = listOf("book.epub", "book.cbz", "book.txt", "book.pdf")
        const val ASSET_HOST = "appassets.androidplatform.net"
        private const val MAX_ENTRIES = 20_000
        private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
        private const val THUMB_H = 600

        @Volatile private var instance: BookStore? = null
        fun get(context: Context): BookStore = instance ?: synchronized(this) {
            instance ?: BookStore(context.applicationContext).also { instance = it }
        }
    }
}
