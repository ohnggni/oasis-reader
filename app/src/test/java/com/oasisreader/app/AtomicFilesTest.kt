package com.oasisreader.app

import com.oasisreader.app.data.writeTextAtomic
import com.oasisreader.app.epub.EpubPackage
import com.oasisreader.app.epub.SpineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class AtomicFilesTest {
    @Test fun writesAndReplaces() {
        val dir = File(System.getProperty("java.io.tmpdir"), "og_atomic_" + System.nanoTime())
        val f = File(dir, "sub/q.json")
        f.writeTextAtomic("하나")
        assertEquals("하나", f.readText())
        f.writeTextAtomic("둘")
        assertEquals("둘", f.readText())
        assertFalse(File(f.parentFile, "q.json.tmp").exists())
        dir.deleteRecursively()
    }
}

class EpubProgressTest {
    private val pkg = EpubPackage(title = "t", spine = listOf(SpineItem("a", chars = 100), SpineItem("b", chars = 300), SpineItem("c", chars = 600)))

    @Test fun sums() {
        assertEquals(1000L, pkg.totalChars)
        assertEquals(0L, pkg.charsBefore(0))
        assertEquals(400L, pkg.charsBefore(2))
        assertEquals(1000L, pkg.charsBefore(9))
        assertEquals(0L, pkg.charsBefore(-1))
    }

    @Test fun overall() {
        assertEquals(0.25f, pkg.overall(1, 0.5f), 0.0001f)
        assertEquals(1f, pkg.overall(2, 1f), 0.0001f)
        assertEquals(0f, EpubPackage(title = "e", spine = emptyList()).overall(0, 0.5f), 0f)
        assertEquals(1L, EpubPackage(title = "e", spine = emptyList()).totalChars)
    }
}
