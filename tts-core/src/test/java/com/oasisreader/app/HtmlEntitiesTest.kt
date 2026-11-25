package com.oasisreader.app

import com.oasisreader.app.reader.HtmlEntities
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3.0: pins the entity decoding of 2.x (it was BookSearch.decode), quirks included. */
class HtmlEntitiesTest {
    private fun decode(s: String) = HtmlEntities.decode(s)

    @Test fun numericAndNamed() {
        assertEquals("가가😀", decode("&#44032;&#xAC00;&#x1F600;"))
        // &nbsp; becomes a no-break space (U+00A0), not a plain one
        assertEquals("\u00A0<>\"'…·&", decode("&nbsp;&lt;&gt;&quot;&apos;&hellip;&middot;&amp;"))
    }

    @Test fun quirksStayAsTheyAre() {
        // numbers first, &amp; last: an escaped numeric entity is decoded twice, an escaped name once
        assertEquals("<", decode("&#38;lt;"))
        assertEquals("&lt;", decode("&amp;lt;"))
        // unknown names, a capital X and numbers too long for an Int are left as written
        assertEquals("&copy; &#X41; &#99999999999;", decode("&copy; &#X41; &#99999999999;"))
    }
}
