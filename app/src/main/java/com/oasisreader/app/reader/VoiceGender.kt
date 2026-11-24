package com.oasisreader.app.reader

/**
 * 여성 / 남성 of a phone voice (88). Android's Voice has no gender, so it comes from what is known
 * about each engine's voices; unknown voices show only under 전체. Azure voices carry their own.
 * - BookFusion Voice (Supertonic): men Alex · Daniel · James · Robert · Sam, women Emily · Jessica ·
 *   Lily · Olivia · Sarah (as its own app lists them), or code names with M1 / F1.
 * - Google: Korean ism · kob and ko-KR-language are women, koc · kod men.
 * - Any engine that says so in the voice's features ("female" / "male", 89 — how OG TTS Bridge
 *   tells it) or in its name.
 */
object VoiceGender {
    const val FEMALE = 1
    const val MALE = 2

    private const val BOOKFUSION = "com.bookfusion.voice"
    private val BF_MEN = setOf("alex", "daniel", "james", "robert", "sam")
    private val BF_WOMEN = setOf("emily", "jessica", "lily", "olivia", "sarah")
    private val CODE_M = Regex("(?:^|[-_ ])m\\d")
    private val CODE_F = Regex("(?:^|[-_ ])f\\d")
    private val WORD = Regex("[a-z]+")
    private val G_WOMEN = listOf("ko-kr-x-ism", "ko-kr-x-kob")
    private val G_MEN = listOf("ko-kr-x-koc", "ko-kr-x-kod")
    private val MALE_WORD = Regex("(?<![a-z])male")

    private val F_TAGS = setOf("female", "gender:female", "gender=female", "woman")
    private val M_TAGS = setOf("male", "gender:male", "gender=male", "man")

    /** [FEMALE], [MALE] or null (not known). [features] first: an engine that says it is right. */
    fun of(engine: String?, name: String, features: Set<String> = emptySet()): Int? {
        for (f in features) {
            val t = f.trim().lowercase()
            if (t in F_TAGS) return FEMALE
            if (t in M_TAGS) return MALE
        }
        val n = name.lowercase()
        if (engine == BOOKFUSION) {
            if (CODE_F.containsMatchIn(n)) return FEMALE
            if (CODE_M.containsMatchIn(n)) return MALE
            for (w in WORD.findAll(n)) {
                if (w.value in BF_WOMEN) return FEMALE
                if (w.value in BF_MEN) return MALE
            }
        }
        if (G_WOMEN.any { n.startsWith(it) } || n == "ko-kr-language") return FEMALE
        if (G_MEN.any { n.startsWith(it) }) return MALE
        if ("female" in n) return FEMALE
        if (MALE_WORD.containsMatchIn(n)) return MALE
        return null
    }

    /** Azure's "Female" / "Male". */
    fun ofAzure(g: String): Int? = when {
        g.equals("Female", true) -> FEMALE
        g.equals("Male", true) -> MALE
        else -> null
    }

    fun label(g: Int?): String? = when (g) { FEMALE -> "여성"; MALE -> "남성"; else -> null }

    /** Does a voice of gender [g] show under the tab [tab] (0 전체 · 1 여성 · 2 남성)? */
    fun shows(tab: Int, g: Int?): Boolean = tab == 0 || g == tab
}
