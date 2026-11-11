package com.oasisreader.app.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Some fragmented m4a files carry protection headers (`pssh` boxes in `moov`) although their sound
 * is not encrypted. The player then treats the sound track as protected content it has no keys
 * for and skips it: the book "plays" in silence with no error (TODO 40, 휴먼카인드).
 *
 * The same file with those boxes renamed to `free` (a box every reader skips) plays normally, so
 * that is what [PlainHeaderDataSource] hands to the player: the first [HEAD] bytes of a file are
 * read up front and the `pssh` boxes inside `moov` are renamed there. Nothing else is touched, and
 * a file whose samples really are encrypted still does not play.
 */
object Mp4Headers {
    const val HEAD = 64 * 1024

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xff) shl 24) or ((b[p + 1].toLong() and 0xff) shl 16) or ((b[p + 2].toLong() and 0xff) shl 8) or (b[p + 3].toLong() and 0xff)

    private fun isType(b: ByteArray, p: Int, t: String): Boolean =
        b[p] == t[0].code.toByte() && b[p + 1] == t[1].code.toByte() && b[p + 2] == t[2].code.toByte() && b[p + 3] == t[3].code.toByte()

    /**
     * Renames the `pssh` boxes directly inside `moov` to `free`, in place. [head] is the start of a
     * file; anything that does not begin with `ftyp` is left alone. Returns how many were renamed.
     */
    fun dropProtectionHeaders(head: ByteArray): Int {
        if (head.size < 16 || !isType(head, 4, "ftyp")) return 0
        var renamed = 0
        var p = 0L
        while (p + 8 <= head.size) {
            val at = p.toInt()
            val size = u32(head, at)
            if (size < 8) break                                   // 64-bit or to-the-end sizes: stop
            if (isType(head, at + 4, "moof") || isType(head, at + 4, "mdat")) break
            if (isType(head, at + 4, "moov")) {
                val end = minOf(p + size, head.size.toLong())
                var q = p + 8
                while (q + 8 <= end) {
                    val c = q.toInt()
                    val cs = u32(head, c)
                    if (cs < 8) break
                    if (isType(head, c + 4, "pssh")) {
                        head[c + 4] = 'f'.code.toByte(); head[c + 5] = 'r'.code.toByte()
                        head[c + 6] = 'e'.code.toByte(); head[c + 7] = 'e'.code.toByte()
                        renamed++
                    }
                    q += cs
                }
            }
            p += size
        }
        return renamed
    }
}

/** Reads through [upstream], with the start of each file passed through [Mp4Headers.dropProtectionHeaders]. */
class PlainHeaderDataSource(private val upstream: DataSource) : DataSource {
    private var head: ByteArray? = null
    private var headPos = 0

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        head = null; headPos = 0
        val length = upstream.open(dataSpec)
        if (dataSpec.position == 0L) {
            // the headers sit at the very start; a read from the middle (a seek) has none
            val buf = ByteArray(Mp4Headers.HEAD)
            var n = 0
            while (n < buf.size) {
                val r = upstream.read(buf, n, buf.size - n)
                if (r == C.RESULT_END_OF_INPUT) break
                n += r
            }
            val h = if (n == buf.size) buf else buf.copyOf(n)
            Mp4Headers.dropProtectionHeaders(h)
            head = h
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val h = head
        if (h != null) {
            if (headPos < h.size) {
                val n = minOf(length, h.size - headPos)
                System.arraycopy(h, headPos, buffer, offset, n)
                headPos += n
                return n
            }
            head = null
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        head = null
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = PlainHeaderDataSource(upstream.createDataSource())
    }
}
