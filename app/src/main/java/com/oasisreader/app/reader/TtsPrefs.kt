package com.oasisreader.app.reader

import android.content.Context

/**
 * The preference file of 소리 내 읽기 and every key in it (3.0). The strings are the ones the 2.x
 * code wrote on 진석님's phone: change one and that setting is lost, so TtsPrefsTest pins the list.
 * Per-book keys and the per-engine gap key are functions.
 */
object TtsPrefs {
    const val NAME = "read_aloud"

    fun of(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ── shared settings ──
    const val RATE = "rate"
    const val PITCH = "pitch"
    const val VOICE = "voice"
    const val ENGINE = "engine"
    const val NORMALIZE = "normalize"
    const val CHAPTER_CUE = "chapter_cue"
    const val RESUME_ALOUD = "resume_aloud"
    const val HIDDEN_ENGINES = "hidden_engines"
    /** Where the floating bar was left. */
    const val BAR_X = "bar_x"
    const val BAR_Y = "bar_y"

    // ── Azure voice (the key itself is not here: it is in SecureStore) ──
    const val AZURE_ON = "azure_on"
    const val AZURE_REGION = "azure_region"
    const val AZURE_VOICE = "azure_voice"
    const val AZURE_MONTH = "azure_month"
    const val AZURE_CHARS = "azure_chars"

    /** Set while away in the store or the engine app, read once on coming back. */
    const val AI_AWAY = "ai_away"

    // ── one book (이 책만 따로) ──
    fun rateOf(bookId: String) = "rate_$bookId"
    fun voiceOf(bookId: String) = "voice_$bookId"
    fun ownOf(bookId: String) = "own_$bookId"
    fun aloudOf(bookId: String) = "aloud_$bookId"

    /** Sentence gap level, kept per engine: gap_azure, gap_default, or gap_ plus the engine package. */
    fun gap(usesAzure: Boolean, engine: String?) = "gap_" + (if (usesAzure) "azure" else engine ?: "default")

    /** Every key this object can make, for one book and one engine (the test compares it with 2.x). */
    fun allKeys(bookId: String, engine: String): List<String> = listOf(
        RATE, PITCH, VOICE, ENGINE, NORMALIZE, CHAPTER_CUE, RESUME_ALOUD, HIDDEN_ENGINES, BAR_X, BAR_Y,
        AZURE_ON, AZURE_REGION, AZURE_VOICE, AZURE_MONTH, AZURE_CHARS, AI_AWAY,
        rateOf(bookId), voiceOf(bookId), ownOf(bookId), aloudOf(bookId),
        gap(true, engine), gap(false, null), gap(false, engine),
    )
}
