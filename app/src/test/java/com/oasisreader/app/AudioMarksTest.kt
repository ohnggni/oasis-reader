package com.oasisreader.app

import com.oasisreader.app.audio.markLabel
import com.oasisreader.app.audio.markSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMarksTest {
    @Test fun spanGoesBackButNotBeforeTheStart() {
        assertEquals(70.0 to 30.0, markSpan(100.0, 30.0))
        assertEquals(0.0 to 12.0, markSpan(12.0, 30.0))
        assertEquals(100.0 to 0.0, markSpan(100.0, 0.0))
    }

    @Test fun labels() {
        assertEquals("3. 제2장 · 12:34", markLabel(2, "제2장", 754.0, 0.0))
        assertEquals("1. 머리말 · 1:02:04~1:02:34", markLabel(0, "머리말", 3724.0, 30.0))
        assertEquals("5. · 0:05", markLabel(4, "", 5.0, 0.4))
    }
}
