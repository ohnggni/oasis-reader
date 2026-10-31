package com.oasisreader.app.comic

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * PDF books ride on the comic viewer: comic.json lists one "page" per PDF page (path "pdf:<n>",
 * size in PDF points) and the pixels are drawn on demand from book.pdf with Android's PdfRenderer.
 * One renderer stays open (it can only render one page at a time, hence the lock).
 */
object PdfPages {
    /**
     * PdfRenderer decodes a scanned page at full resolution (it cannot subsample like
     * BitmapFactory), which takes long and eats CPU: do it on one low-priority thread so
     * page turning and drawing always win.
     */
    val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "pdf-render")
    }.asCoroutineDispatcher()

    const val PREFIX = "pdf:"
    const val FILE = "book.pdf"
    private const val MAX_PIXELS = 12_000_000

    fun isPdf(f: File): Boolean = runCatching {
        f.inputStream().use { s -> val b = ByteArray(5); s.read(b) == 5 && String(b, Charsets.ISO_8859_1) == "%PDF-" }
    }.getOrDefault(false)

    fun isPdfPage(path: String) = path.startsWith(PREFIX)
    fun indexOf(path: String) = path.removePrefix(PREFIX).toInt()

    private var openPath: String? = null
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    private fun open(f: File): PdfRenderer {
        if (openPath == f.path) renderer?.let { return it }
        close()
        val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = try {
            PdfRenderer(d)
        } catch (e: SecurityException) {
            d.close(); throw IOException("암호가 걸린 PDF는 열 수 없습니다")
        } catch (e: Exception) {
            d.close(); throw IOException("PDF를 열 수 없습니다 (${e.message ?: "손상된 파일"})")
        }
        fd = d; renderer = r; openPath = f.path
        return r
    }

    @Synchronized fun close() {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        renderer = null; fd = null; openPath = null
    }

    /** Page sizes in PDF points (import time). */
    @Synchronized fun pages(f: File): List<ComicPage> {
        val r = open(f)
        if (r.pageCount <= 0) throw IOException("쪽이 없는 PDF입니다")
        return (0 until r.pageCount).map { i ->
            r.openPage(i).use { p -> ComicPage(PREFIX + i, p.width.coerceAtLeast(1), p.height.coerceAtLeast(1)) }
        }
    }

    /** Page [index] drawn [width] pixels wide (white paper), or only rows [top, bottom) of it (in page points). */
    @Synchronized fun render(f: File, index: Int, width: Int, top: Int = 0, bottom: Int = -1): Bitmap {
        val r = open(f)
        r.openPage(index).use { p ->
            var w = width.coerceAtLeast(1)
            var scale = w.toFloat() / p.width
            val fromY = top.coerceAtLeast(0)
            val toY = if (bottom < 0) p.height else bottom.coerceAtMost(p.height)
            var h = ((toY - fromY) * scale).toInt().coerceAtLeast(1)
            if (w.toLong() * h > MAX_PIXELS) {
                val k = Math.sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h)).toFloat()
                w = (w * k).toInt().coerceAtLeast(1); h = (h * k).toInt().coerceAtLeast(1); scale *= k
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix().apply { postScale(scale, scale); postTranslate(0f, -fromY * scale) }
            p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }
}
