package com.oasisreader.app

import com.oasisreader.app.ui.Lookup
import org.junit.Assert.assertEquals
import org.junit.Test

class LookupTest {
    @Test fun naverPicksTheDictionaryByScript() {
        assertEquals("네이버 국어사전", Lookup.naverUrl("무지").first)
        assertEquals("네이버 영어사전", Lookup.naverUrl("ignorance").first)
        assertEquals("네이버 일본어사전", Lookup.naverUrl("ひらがな").first)
        assertEquals("네이버 중국어사전", Lookup.naverUrl("学习").first)
    }
}
