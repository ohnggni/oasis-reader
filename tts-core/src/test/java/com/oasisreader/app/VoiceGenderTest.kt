package com.oasisreader.app

import com.oasisreader.app.reader.VoiceGender
import com.oasisreader.app.reader.VoiceGender.FEMALE
import com.oasisreader.app.reader.VoiceGender.MALE
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceGenderTest {
    private val bf = "com.bookfusion.voice"
    private val google = "com.google.android.tts"

    @Test fun bookFusion() {
        assertEquals(MALE, VoiceGender.of(bf, "Alex"))
        assertEquals(FEMALE, VoiceGender.of(bf, "Sarah"))
        assertEquals(MALE, VoiceGender.of(bf, "supertonic-M2-ko"))
        assertEquals(FEMALE, VoiceGender.of(bf, "supertonic-F1-ko"))
        assertEquals(FEMALE, VoiceGender.of(bf, "ko-KR Lily"))
    }

    @Test fun google() {
        assertEquals(FEMALE, VoiceGender.of(google, "ko-kr-x-ism-local"))
        assertEquals(FEMALE, VoiceGender.of(google, "ko-kr-x-kob-network"))
        assertEquals(FEMALE, VoiceGender.of(google, "ko-KR-language"))
        assertEquals(MALE, VoiceGender.of(google, "ko-kr-x-koc-local"))
        assertEquals(MALE, VoiceGender.of(google, "ko-kr-x-kod-network"))
        assertEquals(null, VoiceGender.of(google, "en-us-x-sfg-local"))
    }

    @Test fun others() {
        assertEquals(FEMALE, VoiceGender.of("x", "en-gb-x-rjs#female_1-local"))
        assertEquals(MALE, VoiceGender.of("x", "en-gb-x-rjs#male_1-local"))
        assertEquals(null, VoiceGender.of("x", "Sam"))          // names count only for BookFusion
        assertEquals(null, VoiceGender.of("x", "ko-KR-SMTl03"))
        assertEquals(true, VoiceGender.shows(0, null))
        assertEquals(false, VoiceGender.shows(1, null))
        assertEquals(true, VoiceGender.shows(2, MALE))
    }

    @Test fun features() {
        // 89: an engine (OG TTS Bridge) can say it in the voice's features
        assertEquals(FEMALE, VoiceGender.of("io.sample.readerttsbridge", "rd-03", setOf("female")))
        assertEquals(MALE, VoiceGender.of("x", "wl-1", setOf("networkTimeoutMs", "Male")))
        assertEquals(MALE, VoiceGender.of(bf, "Sarah", setOf("male")))   // features win
    }
}
