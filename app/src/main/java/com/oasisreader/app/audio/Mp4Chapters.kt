package com.oasisreader.app.audio

import java.nio.ByteBuffer

/** Reads parts of a file at any place (a phone file, a 폰 폴더 document, a 🗄️ 파일 서버 file). */
interface RandomReader {
    val size: Long   // -1: unknown
    fun read(offset: Long, length: Int): ByteArray
}

/**
 * 장 표시 for m4b / m4a (TODO 30): the chapter list inside an MP4 file, and its length.
 * Two kinds are read: Nero "chpl" (moov/udta/chpl) and the QuickTime chapter text track (a
 * "text" track: its samples are the titles). Only the moov box and the title samples are read,
 * never the sound.
 */
object Mp4Chapters {
    data class Result(val durationSec: Double, val chapters: List<Pair<Double, String>>)

    private const val MAX_MOOV = 24 * 1024 * 1024

    fun read(r: RandomReader): Result? {
        val moov = findMoov(r) ?: return null
        val box = ByteBuffer.wrap(moov)
        var duration = 0.0
        var chpl: List<Pair<Double, String>> = emptyList()
        val tracks = ArrayList<ByteBuffer>()
        children(box, 8, moov.size) { type, start, end ->
            when (type) {
                "mvhd" -> duration = mvhd(box, start)
                "udta" -> children(box, start, end) { t2, s2, _ -> if (t2 == "chpl") chpl = chpl(box, s2) }
                "trak" -> tracks += ByteBuffer.wrap(moov, start, end - start).slice()
            }
        }
        if (chpl.size > 1) return Result(duration, chpl)
        val text = tracks.firstNotNullOfOrNull { qtChapters(it, r) }
        return Result(duration, text.orEmpty())
    }

    /** The moov box, walking the top-level boxes (moov may come after the sound). */
    private fun findMoov(r: RandomReader): ByteArray? {
        var off = 0L
        repeat(64) {
            if (r.size in 0..(off + 8)) return null
            val h = ByteBuffer.wrap(r.read(off, 16))
            if (h.remaining() < 8) return null
            var size = h.int.toLong() and 0xffffffffL
            val type = fourcc(h)
            var head = 8
            if (size == 1L && h.remaining() >= 8) { size = h.long; head = 16 }
            if (size == 0L) size = if (r.size > 0) r.size - off else return null
            if (size < head) return null
            if (type == "moov") {
                if (size > MAX_MOOV) return null
                return r.read(off, size.toInt())
            }
            off += size
        }
        return null
    }

    private fun fourcc(b: ByteBuffer): String { val a = ByteArray(4); b.get(a); return String(a, Charsets.ISO_8859_1) }

    /** Calls [f](type, contentStart, end) for each child box in [from, to). */
    private fun children(b: ByteBuffer, from: Int, to: Int, f: (String, Int, Int) -> Unit) {
        var p = from
        while (p + 8 <= to) {
            b.position(p)
            var size = b.int.toLong() and 0xffffffffL
            val type = fourcc(b)
            var head = 8
            if (size == 1L) { size = b.long; head = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < head || p + size > to) return
            f(type, p + head, (p + size).toInt())
            p += size.toInt()
        }
    }

    private fun mvhd(b: ByteBuffer, s: Int): Double {
        b.position(s)
        val v = b.get().toInt(); b.position(s + 4)
        return if (v == 1) { b.position(s + 4 + 16); val ts = b.int; val d = b.long; if (ts > 0) d.toDouble() / ts else 0.0 }
        else { b.position(s + 4 + 8); val ts = b.int; val d = b.int.toLong() and 0xffffffffL; if (ts > 0) d.toDouble() / ts else 0.0 }
    }

    /** Nero chapters: start in 100 ns units, a title of up to 255 bytes. */
    private fun chpl(b: ByteBuffer, s: Int): List<Pair<Double, String>> = runCatching {
        b.position(s)
        val v = b.get().toInt(); b.position(s + 4)
        if (v != 0) b.int
        val n = b.get().toInt() and 0xff
        (0 until n).map {
            val start = b.long / 10_000_000.0
            val len = b.get().toInt() and 0xff
            val t = ByteArray(len); b.get(t)
            start to String(t, Charsets.UTF_8).trim()
        }
    }.getOrDefault(emptyList())

    /** A QuickTime "text" track's samples as (start, title). */
    private fun qtChapters(trak: ByteBuffer, r: RandomReader): List<Pair<Double, String>>? {
        var handler = ""; var ts = 0
        var stts: List<Pair<Long, Long>> = emptyList()   // (count, delta)
        var sizes: List<Int> = emptyList()
        var chunks: List<Long> = emptyList()
        var stsc: List<Triple<Int, Int, Int>> = emptyList()
        fun walk(from: Int, to: Int) {
            children(trak, from, to) { type, s, e ->
                when (type) {
                    "mdia", "minf", "stbl" -> walk(s, e)
                    "hdlr" -> { trak.position(s + 8); handler = fourcc(trak) }
                    "mdhd" -> { trak.position(s); val v = trak.get().toInt(); trak.position(s + 4 + if (v == 1) 16 else 8); ts = trak.int }
                    "stts" -> { trak.position(s + 4); val n = trak.int; stts = (0 until n).map { (trak.int.toLong() and 0xffffffffL) to (trak.int.toLong() and 0xffffffffL) } }
                    "stsz" -> { trak.position(s + 4); val one = trak.int; val n = trak.int; sizes = if (one != 0) List(n) { one } else List(n) { trak.int } }
                    "stco" -> { trak.position(s + 4); val n = trak.int; chunks = List(n) { trak.int.toLong() and 0xffffffffL } }
                    "co64" -> { trak.position(s + 4); val n = trak.int; chunks = List(n) { trak.long } }
                    "stsc" -> { trak.position(s + 4); val n = trak.int; stsc = List(n) { Triple(trak.int, trak.int, trak.int) } }
                }
            }
        }
        runCatching { walk(0, trak.limit()) }.onFailure { return null }
        if (handler != "text" && handler != "sbtl") return null
        if (ts <= 0 || sizes.isEmpty() || chunks.isEmpty() || sizes.size > 2000) return null
        // sample → file offset (chunks hold runs of samples, stsc says how many)
        val offsets = ArrayList<Long>()
        var sample = 0
        for (ci in chunks.indices) {
            val run = stsc.lastOrNull { it.first - 1 <= ci }?.second ?: 1
            var at = chunks[ci]
            repeat(run) { if (sample < sizes.size) { offsets += at; at += sizes[sample]; sample++ } }
        }
        if (offsets.size < sizes.size) return null
        val starts = ArrayList<Double>()
        var t = 0L
        stts.forEach { (count, delta) -> repeat(count.toInt()) { if (starts.size < sizes.size) { starts += t.toDouble() / ts; t += delta } } }
        // the titles usually sit together: one read when they are close
        val lo = offsets.min(); val hi = offsets.indices.maxOf { offsets[it] + sizes[it] }
        val block = if (hi - lo <= 1_048_576) r.read(lo, (hi - lo).toInt()) else null
        return sizes.indices.mapNotNull { i ->
            val raw = block?.copyOfRange((offsets[i] - lo).toInt(), (offsets[i] - lo + sizes[i]).toInt().coerceAtMost(block.size))
                ?: r.read(offsets[i], sizes[i])
            if (raw.size < 2) return@mapNotNull null
            val len = ((raw[0].toInt() and 0xff) shl 8) or (raw[1].toInt() and 0xff)
            val body = raw.copyOfRange(2, (2 + len).coerceAtMost(raw.size))
            val title = if (body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte()) String(body, 2, body.size - 2, Charsets.UTF_16BE)
                else String(body, Charsets.UTF_8)
            (starts.getOrElse(i) { 0.0 }) to title.trim()
        }.takeIf { it.size > 1 }
    }
}
