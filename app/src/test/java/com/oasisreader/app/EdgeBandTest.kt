package com.oasisreader.app

import com.oasisreader.app.ui.edgeBand
import org.junit.Assert.assertEquals
import org.junit.Test

/** 104: where the back gesture is off while reading full screen. */
class EdgeBandTest {
    @Test fun tallPhoneTakesTheLimitAroundTheThumb() {
        // 800dp at 3x: 200dp = 600px, centred at 70%
        assertEquals(1380 to 1980, edgeBand(2400, 600))
    }

    @Test fun shortScreenKeeps55To85Percent() {
        assertEquals(594 to 918, edgeBand(1080, 600))
    }

    @Test fun staysOnTheScreen() {
        assertEquals(0 to 0, edgeBand(0, 600))
    }
}
