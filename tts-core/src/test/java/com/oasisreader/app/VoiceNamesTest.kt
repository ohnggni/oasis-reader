package com.oasisreader.app

import com.oasisreader.app.reader.VoiceNames
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceNamesTest {
    /** Reader TTS Bridge (io.sample.readerttsbridge): names checked with getVoices() on a Galaxy. */
    @Test fun readableNamesShown() {
        for (n in listOf("RD_민재", "RD_진우", "RD_유정", "RD_이나")) assertEquals(n, VoiceNames.display(n))
        assertEquals("Alice", VoiceNames.display("Alice"))
        assertEquals("Narrator 2", VoiceNames.display("Narrator 2"))
    }

    /** Engines whose names are codes keep the "한국어 N" label. */
    @Test fun codesFallBack() {
        for (n in listOf(
            "ko-kr-x-ism-local", "ko-kr-x-ism-network", "ko-KR-SMTl03", "ko_KR", "ko-KR-language",
            "supertonic-M2-ko", "en-us-x-sfg#male_1-local", "ko-KR-Standard-A", "", "  ", "12345",
        )) assertEquals(n, null, VoiceNames.display(n))
        assertEquals(null, VoiceNames.display(null))
    }
}
