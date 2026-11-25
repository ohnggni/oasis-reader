package com.oasisreader.app

import com.oasisreader.app.reader.TtsPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

/** The keys 2.x wrote into the read_aloud preferences. If one changes, that setting is lost on the phone. */
class TtsPrefsTest {
    @Test fun fileName() {
        assertEquals("read_aloud", TtsPrefs.NAME)
    }

    @Test fun keysAreTheOnesFrom2x() {
        val old = listOf(
            "rate", "pitch", "voice", "engine", "normalize", "chapter_cue", "resume_aloud", "hidden_engines", "bar_x", "bar_y",
            "azure_on", "azure_region", "azure_voice", "azure_month", "azure_chars", "ai_away",
            "rate_b1", "voice_b1", "own_b1", "aloud_b1",
            "gap_azure", "gap_default", "gap_com.google.android.tts",
        )
        assertEquals(old, TtsPrefs.allKeys("b1", "com.google.android.tts"))
    }

    @Test fun bookAndEngineKeys() {
        assertEquals("rate_9f2", TtsPrefs.rateOf("9f2"))
        assertEquals("voice_9f2", TtsPrefs.voiceOf("9f2"))
        assertEquals("own_9f2", TtsPrefs.ownOf("9f2"))
        assertEquals("aloud_9f2", TtsPrefs.aloudOf("9f2"))
        // Azure wins over the engine; no engine = default
        assertEquals("gap_azure", TtsPrefs.gap(true, "com.samsung.SMT"))
        assertEquals("gap_com.samsung.SMT", TtsPrefs.gap(false, "com.samsung.SMT"))
        assertEquals("gap_default", TtsPrefs.gap(false, null))
    }
}
