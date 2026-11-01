package com.oasisreader.app

import com.oasisreader.app.data.KoSync
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class KoSyncTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Same samples as bookoasis/KOReader: 1 KB at 0, 1K, 4K, 16K, 64K, … while inside the file. */
    @Test fun partialMd5MatchesReference() {
        val data = ByteArray(300_000) { (it * 31 + 7).toByte() }
        val f = File.createTempFile("kosync", ".bin").apply { writeBytes(data); deleteOnExit() }
        val md5 = MessageDigest.getInstance("MD5")
        for (i in -1..10) {
            val off = if (i < 0) 0 else 1024 shl (2 * i)
            if (off >= data.size) break
            md5.update(data, off, minOf(1024, data.size - off))
        }
        assertEquals(hex(md5.digest()), KoSync.partialMd5(f))
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", KoSync.md5("password"))
    }
}
