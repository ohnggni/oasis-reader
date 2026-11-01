package com.oasisreader.app.reader

import com.oasisreader.app.data.KoSync
import com.oasisreader.app.epub.EpubPackage
import com.oasisreader.app.epub.SpineItem
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTargetTest {
    // three chapters of 100, 300, 600 characters (1000 in all)
    private val pkg = EpubPackage(title = "t", spine = listOf(SpineItem("a", chars = 100), SpineItem("b", chars = 300), SpineItem("c", chars = 600)))

    @Test fun ogReaderUsesThePercentage() {
        val r = KoSync.Remote("/body/DocFragment[3]/body", 0.25f, "OG Reader (Tab)", "ogreader-x", 1)
        assertEquals(1, remoteTarget(pkg, r)!!.first)                  // 250 chars in: chapter b
        assertEquals(0.5f, remoteTarget(pkg, r)!!.second, 1e-4f)       // 150 of its 300
    }

    @Test fun koreaderXpointerGivesTheChapter() {
        val r = KoSync.Remote("/body/DocFragment[3]/body/p[12]/text().0", 0.7f, "Kobo", "abc", 1)
        val (i, f) = remoteTarget(pkg, r)!!
        assertEquals(2, i)                                             // DocFragment is 1-based
        assertEquals(0.5f, f, 1e-4f)                                   // 700 -> 300 into chapter c (600)
    }

    @Test fun seekAcrossTheBook() {
        assertEquals(0 to 0f, seekTarget(pkg, 0f))
        assertEquals(2, seekTarget(pkg, 0.99f).first)
    }
}
