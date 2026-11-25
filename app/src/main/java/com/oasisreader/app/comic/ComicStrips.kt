package com.oasisreader.app.comic

import com.oasisreader.app.data.ComicPage
import com.oasisreader.app.data.ComicPackage
import com.oasisreader.app.data.PdfPages
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** A horizontal strip of one page (webtoon pages can be 20 000 px tall: never decode them whole). */
internal data class Slice(val page: Int, val path: String, val w: Int, val top: Int, val bottom: Int) {
    val h get() = bottom - top
}

internal const val SLICE_SRC = 1600   // source pixels per strip

internal fun slicesOf(pkg: ComicPackage): List<Slice> = pkg.pages.flatMapIndexed { i, p ->
    val n = (p.h + SLICE_SRC - 1) / SLICE_SRC
    (0 until n).map { k -> Slice(i, p.path, p.w, k * SLICE_SRC, minOf(p.h, (k + 1) * SLICE_SRC)) }
}

internal object StripCache {
    val mem = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

/** A PDF "page" path points into book.pdf next to the content folder. */
internal fun pdfOf(file: File) = File(file.parentFile!!.parentFile, PdfPages.FILE)

/** Decode one strip at roughly [targetW] pixels wide. */
internal fun decodeStrip(file: File, s: Slice, targetW: Int): Bitmap? {
    if (PdfPages.isPdfPage(s.path)) return PdfPages.render(pdfOf(file), PdfPages.indexOf(s.path), targetW, s.top, s.bottom)
    var sample = 1
    while (s.w / (sample * 2) >= targetW) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return try {
        val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.path)
                      else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.path, false)
        try { decoder.decodeRegion(Rect(0, s.top, s.w, s.bottom), opts) } finally { decoder.recycle() }
    } catch (e: Exception) {
        // formats the region decoder cannot read (gif, …): decode whole, then cut
        val full = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val top = s.top / sample
        val h = (s.h / sample).coerceAtMost(full.height - top).coerceAtLeast(1)
        Bitmap.createBitmap(full, 0, top.coerceIn(0, full.height - 1), full.width, h)
    }
}

/**
 * A finished bitmap as a GPU (hardware) bitmap: uploaded here, on the worker thread, instead of
 * on the render thread the first time the page slides into view.
 */
internal fun toGpu(b: Bitmap): Bitmap =
    runCatching { b.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()?.also { if (it !== b) b.recycle() } ?: b

/**
 * One whole page, decoded to fit [boxW] x [boxH] pixels and shown with ContentScale.Fit.
 * [sharp]: the page is zoomed in — draw it again at higher resolution (the plain one stays until then).
 * [waitIdle]: wait with the work until page turning has stopped (pages prepared off screen).
 */
@Composable
internal fun PageImage(
    file: File, page: ComicPage, boxW: Int, boxH: Int, align: Alignment,
    sharp: Boolean = false, waitIdle: (suspend () -> Unit)? = null,
) {
    val base = "${file.path}#page#$boxW#$boxH"
    val key = if (sharp) "$base#sharp" else base
    val bmp by produceState(StripCache.mem.get(key) ?: StripCache.mem.get(base), key) {
        StripCache.mem.get(key)?.let { value = it; return@produceState }
        val pdf = PdfPages.isPdfPage(page.path)
        waitIdle?.invoke()
        val fresh = withContext(if (pdf) PdfPages.dispatcher else Dispatchers.IO) {
            runCatching {
                if (pdf) {
                    // 1x the size on screen; 2.5x when zoomed in
                    val fit = minOf(boxW.toFloat() / page.w, boxH.toFloat() / page.h) * (if (sharp) 2.5f else 1f)
                    PdfPages.render(pdfOf(file), PdfPages.indexOf(page.path), (page.w * fit).toInt())
                } else {
                    var sample = 1
                    if (!sharp) while (page.w / (sample * 2) >= boxW && page.h / (sample * 2) >= boxH) sample *= 2
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }?.let(::toGpu)
            }.getOrNull()
        }
        if (fresh != null) { StripCache.mem.put(key, fresh); value = fresh }
    }
    val b = bmp
    if (b == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
    } else {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            alignment = align,
        )
    }
}

@Composable
internal fun Strip(file: File, s: Slice, targetPx: Int) {
    val key = "${file.path}#${s.top}#$targetPx"
    val bmp by produceState(StripCache.mem.get(key), key) {
        if (value != null) return@produceState
        value = withContext(if (PdfPages.isPdfPage(s.path)) PdfPages.dispatcher else Dispatchers.IO) {
            runCatching { decodeStrip(file, s, targetPx)?.let(::toGpu) }.getOrNull()
        }
            ?.also { StripCache.mem.put(key, it) }
    }
    val ratio = s.w.toFloat() / s.h.coerceAtLeast(1)
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).background(if (bmp == null) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
