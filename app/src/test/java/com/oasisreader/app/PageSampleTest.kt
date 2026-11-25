package com.oasisreader.app.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSampleTest {
    @Test fun ordinaryScanIsReadWhole() {
        // a 2000x3000 page on a 1440x2900 screen: half of it would be smaller than shown
        assertEquals(1, pageSample(2000, 3000, 1440, 2900))
        assertEquals(1, pageSample(800, 1200, 1440, 2900))
    }

    @Test fun largeScanIsHalved() {
        assertEquals(2, pageSample(3000, 4500, 1440, 2900))
        assertEquals(4, pageSample(6000, 9000, 1440, 2900))
        // a double-page scan across the whole width
        assertEquals(2, pageSample(6400, 4400, 3120, 1440))
    }

    @Test fun unknownBoxReadsWhole() {
        assertEquals(1, pageSample(3000, 4500, 0, 0))
    }

    @Test fun neverSmallerThanShown() {
        for ((w, h) in listOf(700 to 1000, 3000 to 4500, 4000 to 2800, 1600 to 20000, 5000 to 5000)) {
            for ((bw, bh) in listOf(1440 to 2900, 1560 to 1440, 3120 to 1440, 720 to 1500)) {
                val s = pageSample(w, h, bw, bh)
                val fit = minOf(bw.toFloat() / w, bh.toFloat() / h)
                if (s > 1) assertTrue("$w x $h in $bw x $bh", 1f / s >= fit * 0.999f)
            }
        }
    }
}
