package com.oasisreader.app.reader

/**
 * How a phone TTS voice is shown in the voice dialog. The engine's own Voice.getName() is shown
 * when it is a name a person can read ("RD_민재", "Alice"); only names that are internal codes
 * ("ko-kr-x-ism-local", "ko-KR-SMTl03", "supertonic-M2-ko") fall back to "한국어 3" style labels.
 * The voice is always chosen and saved by its name, never by its place in the list.
 */
object VoiceNames {
    /** Starts with a locale tag: ko-KR…, ko_kr…, en-us-x-… */
    private val LOCALE_HEAD = Regex("""^[a-z]{2,3}[-_][a-z]{2}(\b|[-_#])""", RegexOption.IGNORE_CASE)
    /** Ends with a language code: supertonic-M2-ko, voice_en */
    private val LANG_TAIL = Regex("""[-_][a-z]{2,3}([-_][a-z]{2})?$""", RegexOption.IGNORE_CASE)
    private val MARKERS = listOf("-x-", "#", "-local", "-network", "-embedded")

    /** The name to show, or null when [name] looks like an engine's internal code. */
    fun display(name: String?): String? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        // a name with letters beyond ASCII (Hangul, kana …) is meant for people
        if (n.any { it.isLetter() && it.code > 0x7F }) return n
        if (!n.any { it.isLetter() }) return null
        if (LOCALE_HEAD.containsMatchIn(n) || LANG_TAIL.containsMatchIn(n)) return null
        if (MARKERS.any { n.contains(it, ignoreCase = true) }) return null
        return n
    }
}
