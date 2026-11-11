package com.oasisreader.app

import com.oasisreader.app.audio.Mp4Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4HeadersTest {
    private fun box(type: String, body: ByteArray): ByteArray {
        val n = 8 + body.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun has(b: ByteArray, s: String) = String(b, Charsets.ISO_8859_1).contains(s)

    @Test fun psshInsideMoovBecomesFree() {
        val moov = box("moov", box("mvhd", ByteArray(100)) + box("trak", ByteArray(40)) + box("mvex", ByteArray(48)) + box("pssh", ByteArray(674)) + box("pssh", ByteArray(75)))
        // "pssh" in the sound data must stay as it is
        val file = box("ftyp", "mp42".toByteArray() + ByteArray(4)) + moov + box("moof", ByteArray(16)) + box("mdat", "xxpsshxx".toByteArray())
        val before = file.size
        assertEquals(2, Mp4Headers.dropProtectionHeaders(file))
        assertEquals(before, file.size)
        val text = String(file, Charsets.ISO_8859_1)
        assertEquals(text.indexOf("pssh"), text.indexOf("xxpsshxx") + 2)   // only the one in mdat is left
        assertTrue(has(file, "mvex"))
    }

    @Test fun otherFilesAreLeftAlone() {
        val mp3 = "ID3".toByteArray() + ByteArray(40) + "moov....pssh".toByteArray()
        val copy = mp3.copyOf()
        assertEquals(0, Mp4Headers.dropProtectionHeaders(mp3))
        assertTrue(mp3.contentEquals(copy))
        assertEquals(0, Mp4Headers.dropProtectionHeaders(ByteArray(4)))
    }

    @Test fun plainFileHasNothingToRename() {
        val file = box("ftyp", "M4A ".toByteArray() + ByteArray(4)) + box("moov", box("mvhd", ByteArray(100)) + box("trak", ByteArray(40)))
        assertEquals(0, Mp4Headers.dropProtectionHeaders(file))
        assertFalse(has(file, "free"))
    }

    @Test fun headCutInsideMoovDoesNotOverrun() {
        val moov = box("moov", box("mvhd", ByteArray(100)) + box("pssh", ByteArray(30)) + box("trak", ByteArray(4000)))
        val file = (box("ftyp", "mp42".toByteArray() + ByteArray(4)) + moov).copyOf(200)
        assertEquals(1, Mp4Headers.dropProtectionHeaders(file))
    }
}
