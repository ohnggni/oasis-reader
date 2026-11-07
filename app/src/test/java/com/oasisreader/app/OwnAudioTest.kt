package com.oasisreader.app

import com.oasisreader.app.audio.Mp4Chapters
import com.oasisreader.app.audio.OwnAudio
import com.oasisreader.app.audio.RandomReader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class OwnAudioTest {
    @Test fun trackTitlesShyni() {
        assertEquals(
            listOf("1회. 90일 밤의 우주_00.머리말", "10회. 90일 밤의 우주_04.Plus Episode_DAY83-90"),
            OwnAudio.trackTitles(listOf("[001] 1회. 90일 밤의 우주_00.머리말.m4a", "[010] 10회. 90일 밤의 우주_04.Plus Episode_DAY83-90.m4a")),
        )
        assertEquals(
            listOf("프롤로그_땅에 심은 인형", "기계론의 관점에서 출발하기"),
            OwnAudio.trackTitles(listOf("[001] 프롤로그_땅에 심은 인형.m4a", "[002] 기계론의 관점에서 출발하기.m4a")),
        )
    }

    @Test fun trackTitlesRipped() {
        assertEquals(
            listOf("001", "002", "091"),
            OwnAudio.trackTitles(listOf("Rising Strong (Unabridged) - 001.mp3", "Rising Strong (Unabridged) - 002.mp3", "Rising Strong (Unabridged) - 091.mp3")),
        )
        assertEquals(listOf("1장 시작", "2장 끝"), OwnAudio.trackTitles(listOf("1장 시작.mp3", "2장 끝.mp3")))
    }

    @Test fun nfoEncoding() {
        val latin = "Brené Brown".toByteArray(charset("windows-1252"))
        assertEquals("Brené Brown", OwnAudio.text(latin))
        val korean = "작가: 김명진".toByteArray(charset("x-windows-949"))
        assertEquals("작가: 김명진", OwnAudio.text(korean))
        assertEquals("plain", OwnAudio.text("plain".toByteArray()))
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); val d = DataOutputStream(o)
        d.writeInt(8 + body.size); d.write(type.toByteArray(Charsets.ISO_8859_1)); d.write(body)
        return o.toByteArray()
    }

    @Test fun neroChapters() {
        val mvhd = ByteArrayOutputStream().also { o ->
            val d = DataOutputStream(o)
            d.writeInt(0); d.writeInt(0); d.writeInt(0)   // version/flags, creation, modification
            d.writeInt(1000); d.writeInt(3_600_000)        // timescale, duration (1 h)
            d.write(ByteArray(80))
        }.toByteArray()
        val chpl = ByteArrayOutputStream().also { o ->
            val d = DataOutputStream(o)
            d.writeInt(0x01000000); d.writeInt(0)          // version 1, flags, reserved
            d.writeByte(2)
            listOf(0L to "Opening", 1_800L * 10_000_000 to "둘째 장").forEach { (t, s) ->
                d.writeLong(t); val b = s.toByteArray(); d.writeByte(b.size); d.write(b)
            }
        }.toByteArray()
        val file = box("ftyp", "M4B ".toByteArray()) + box("mdat", ByteArray(1000)) +
            box("moov", box("mvhd", mvhd) + box("udta", box("chpl", chpl)))
        val r = Mp4Chapters.read(object : RandomReader {
            override val size = file.size.toLong()
            override fun read(offset: Long, length: Int) = file.copyOfRange(offset.toInt(), minOf(file.size, offset.toInt() + length))
        })!!
        assertEquals(3600.0, r.durationSec, 0.01)
        assertEquals(listOf(0.0 to "Opening", 1800.0 to "둘째 장"), r.chapters)
    }
}
