package com.oasisreader.app

import com.oasisreader.app.audio.MediaCrypt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class MediaCryptTest {
    private val key = Random(1).nextBytes(32)
    private val nonce = Random(2).nextBytes(8)
    private val plain = Random(3).nextBytes(200_003)

    private fun encrypted(): ByteArray = ByteArrayOutputStream().also {
        MediaCrypt.encrypt(key, nonce, ByteArrayInputStream(plain), it)
    }.toByteArray()

    @Test fun headerAndLength() {
        val e = encrypted()
        assertEquals(plain.size + MediaCrypt.HEADER, e.size)
        assertArrayEquals(nonce, MediaCrypt.nonceOf(e.copyOfRange(0, MediaCrypt.HEADER)))
        assertNull(MediaCrypt.nonceOf(plain.copyOfRange(0, MediaCrypt.HEADER)))
    }

    /** The player seeks anywhere, block-aligned or not. */
    @Test fun decryptFromAnyPosition() {
        val e = encrypted()
        for (pos in listOf(0L, 1L, 15L, 16L, 17L, 4095L, 65_537L, 199_999L)) {
            val c = MediaCrypt.cipherAt(key, nonce, pos)
            val chunk = e.copyOfRange(MediaCrypt.HEADER + pos.toInt(), e.size)
            assertArrayEquals("at $pos", plain.copyOfRange(pos.toInt(), plain.size), c.update(chunk))
        }
    }

    /** A download that goes on from a part already written gives the same file. */
    @Test fun resumeGivesSameBytes() {
        val cut = 70_001
        val out = ByteArrayOutputStream()
        MediaCrypt.encrypt(key, nonce, ByteArrayInputStream(plain, 0, cut), out)
        MediaCrypt.encrypt(key, nonce, ByteArrayInputStream(plain, cut, plain.size - cut), out, position = cut.toLong())
        assertArrayEquals(encrypted(), out.toByteArray())
    }
}
