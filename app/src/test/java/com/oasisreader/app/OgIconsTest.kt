package com.oasisreader.app

import com.oasisreader.app.ui.OgIconPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OgIconsTest {
    @Test fun everyMappedEmojiHasAPath() {
        for ((emoji, name) in OgIconPaths.byEmoji) assertTrue("$emoji -> $name", OgIconPaths.paths.containsKey(name))
    }

    @Test fun variationSelectorIsIgnored() {
        assertEquals("settings", OgIconPaths.nameFor("⚙️"))
        assertEquals("settings", OgIconPaths.nameFor("⚙"))
        assertNull(OgIconPaths.nameFor("🌓"))
    }

    @Test fun leadingEmojiOfALabel() {
        assertEquals("shelf" to 2, OgIconPaths.leading("📚 컬렉션"))
        assertEquals("download" to 2, OgIconPaths.leading("⬇️ 받기"))
        assertEquals("download" to 1, OgIconPaths.leading("⬇ 받기"))
        assertEquals("play" to 1, OgIconPaths.leading("▶"))
        assertNull(OgIconPaths.leading("컬렉션 📚"))
        assertNull(OgIconPaths.leading(""))
    }

    @Test fun pathsUseOnlyPathCharacters() {
        val ok = Regex("[MLHVCAZmlhvcaz0-9 .\\-]+")
        for ((name, d) in OgIconPaths.paths) assertTrue(name, ok.matches(d) && d.startsWith("M"))
    }
}
