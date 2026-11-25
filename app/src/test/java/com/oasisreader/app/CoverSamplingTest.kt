package com.oasisreader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class CoverSamplingTest {
    @Test fun unknownSizeReadsAsBefore() {
        assertEquals(CoverRead(1, 1f, true), coverRead(748, 1100, 0, 0))
        assertEquals(CoverRead(2, 1f, true), coverRead(800, 1200, 0, 0))
        assertEquals(CoverRead(1, 1f, true), coverRead(400, 400, 0, 0, maxH = 400))
    }

    @Test fun smallPlacesReadSmaller() {
        // a list row (52x76 dp at 3.5): half of a 408x600 thumbnail is still enough
        val r = coverRead(408, 600, 182, 266)
        assertEquals(2, r.sample)
        assertEquals(1f, r.scale, 0f)
        assertTrue(!r.full)
    }

    @Test fun tallComicThumbnailIsShrunkToTheTile() {
        val r = coverRead(748, 1100, 410, 602)
        assertEquals(1, r.sample)
        assertEquals(410f / 748f, r.scale, 1e-4f)      // the side that needs more of it
        assertTrue(!r.full)
    }

    @Test fun nearTheShownSizeIsLeftAlone() {
        assertEquals(CoverRead(1, 1f, true), coverRead(408, 600, 400, 588))
        // shown larger than the picture
        assertEquals(CoverRead(1, 1f, true), coverRead(408, 600, 900, 1300))
    }

    @Test fun neverSmallerThanShown() {
        for (src in listOf(408 to 600, 748 to 1100, 1200 to 600, 300 to 1199, 2000 to 3000, 90 to 120)) {
            for (want in listOf(40 to 56, 172 to 270, 182 to 266, 410 to 602, 640 to 940)) {
                val usual = coverRead(src.first, src.second, 0, 0)
                val r = coverRead(src.first, src.second, want.first, want.second)
                val w = ceil(src.first / r.sample * r.scale.toDouble()).toInt()
                val h = ceil(src.second / r.sample * r.scale.toDouble()).toInt()
                // at least the shown size, unless the usual reading was already smaller
                assertTrue("$src $want", w >= minOf(want.first, src.first / usual.sample))
                assertTrue("$src $want", h >= minOf(want.second, src.second / usual.sample))
                assertTrue("$src $want", r.sample >= usual.sample)
            }
        }
    }
}
