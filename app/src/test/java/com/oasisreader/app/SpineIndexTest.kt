package com.oasisreader.app.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class SpineIndexTest {
    private val pkg = EpubPackage(
        title = "t", spine = listOf(SpineItem("a", chars = 1), SpineItem("b", chars = 2), SpineItem("a", chars = 3), SpineItem("c", chars = 4)),
    )

    @Test fun firstPlaceOfAPath() {
        assertEquals(0, pkg.spineIndexOf("a"))      // listed twice: the first one
        assertEquals(1, pkg.spineIndexOf("b"))
        assertEquals(3, pkg.spineIndexOf("c"))
    }

    @Test fun unknownPath() {
        assertEquals(-1, pkg.spineIndexOf("z"))
        assertEquals(-1, EpubPackage(title = "e", spine = emptyList()).spineIndexOf("a"))
    }

    @Test fun aCopyLooksInItsOwnSpine() {
        assertEquals(0, pkg.spineIndexOf("a"))
        assertEquals(1, pkg.copy(spine = listOf(SpineItem("x"), SpineItem("a"))).spineIndexOf("a"))
    }
}
