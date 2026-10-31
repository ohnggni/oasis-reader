package com.oasisreader.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

private object CoverCache {
    val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

private fun decodeSmall(bytes: ByteArray, maxH: Int = 600): Bitmap? {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
    if (b.outHeight <= 0) return null
    var s = 1
    while (b.outHeight / (s * 2) >= maxH) s *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
}

/** Book cover from a local thumbnail file, or a typographic placeholder. */
@Composable
fun BookCover(file: File?, title: String, modifier: Modifier = Modifier) {
    val key = file?.let { it.path + ":" + it.lastModified() }
    val bmp by produceState(key?.let { CoverCache.mem.get(it) }, key) {
        if (key == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { decodeSmall(file!!.readBytes()) }.getOrNull()
        }?.also { CoverCache.mem.put(key, it) }
    }
    CoverFrame(bmp, title, modifier)
}

/** Cover that has to be fetched (OPDS thumbnail). */
@Composable
fun RemoteCover(url: String?, title: String, load: suspend (String) -> ByteArray, modifier: Modifier = Modifier) {
    val bmp by produceState(url?.let { CoverCache.mem.get(it) }, url) {
        if (url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { DiskCovers.load(url, load) }?.also { CoverCache.mem.put(url, it) }
    }
    CoverFrame(bmp, title, modifier)
}

/**
 * Remote covers kept on disk as small JPEGs (cacheDir/covers), so a grid of
 * comic first pages (often 1-2 MB each) is fetched only once.
 */
object DiskCovers {
    @Volatile var dir: java.io.File? = null

    private val gate = kotlinx.coroutines.sync.Semaphore(4)   // don't flood the server with cover requests

    suspend fun load(url: String, fetch: suspend (String) -> ByteArray): Bitmap? {
        val d = dir
        val f = d?.let { java.io.File(it, sha1(url) + ".jpg") }
        if (f != null && f.isFile) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()?.let { return it }
        val bmp = gate.withPermit { runCatching { decodeSmall(fetch(url), 400) }.getOrNull() } ?: return null
        if (f != null) runCatching {
            d.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }
        return bmp
    }

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

@Composable
private fun CoverFrame(bmp: Bitmap?, title: String, modifier: Modifier) {
    val shape = RoundedCornerShape(4.dp)
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    } else {
        Box(
            modifier.clip(shape).background(placeholderColor(title)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

private val placeholderPalette = listOf(
    Color(0xFF2F5D50), Color(0xFF5B4A3A), Color(0xFF3E5470), Color(0xFF6B3E4E), Color(0xFF4F5B33), Color(0xFF6A5232),
)

private fun placeholderColor(title: String) = placeholderPalette[(title.hashCode() and 0x7fffffff) % placeholderPalette.size]
