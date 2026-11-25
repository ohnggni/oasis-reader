package com.oasisreader.app.reader

/**
 * Every tuning number of 소리 내 읽기 in one place (3.0). Values are the ones the 2.x code had;
 * the old names (ReadAloud.GAP_MS, ReadAloudText's limits, ...) point here. The page script gets
 * the sentence-splitting ones from here too (see TtsScript), so the app and the page cannot drift.
 */
object TtsTuning {

    // ── sentence splitting (ReadAloudText, and the page script) ──

    const val TEXT_END = ".!?。！？…"
    const val TEXT_CLOSE = ".!?。！？…\"'”’」』)]）〉》"
    const val TEXT_OPEN = "“‘「『（〈《(["
    /** Closing quotes and brackets: after one, Korean right on (’와, ”라고) means the sentence goes on. */
    const val TEXT_QUOTE_END = "\"'”’」』)]）〉》"
    const val TEXT_COMMA = ",，、"
    /** Over this many letters a sentence is cut at a comma (2.9: was 120, book sentences of 150 to 200 letters were cut mid-phrase). */
    const val TEXT_LONG = 250
    /** Over this, with no comma, a sentence is cut at a space too (text with no sentence ends, 텍본). */
    const val TEXT_HARD = 400
    const val TEXT_MIN = 30

    // ── pauses and limits (ReadAloud) ──

    /** 문장 사이: 없음, 짧게, 보통 (2.4의 350ms), 길게. */
    val GAP_MS = intArrayOf(0, 150, 350, 600)
    /** After a heading or a chapter number line (85): a step longer than after a sentence. */
    val HEAD_MS = intArrayOf(150, 350, 600, 900)
    const val GAP_NORMAL = 2
    const val CUE_GAP_MS = 8_000L
    /** Under the engines' input limit (4000). */
    const val MAX_LEN = 3000

    /** 빠르기 (87): 0.5 to 2.0 by 0.05, on a slider (was a list of 12, 2.6). */
    const val RATE_MIN = 0.5f
    const val RATE_MAX = 2f
    const val RATE_STEP = 0.05f
    val PITCHES = listOf(0.8f to "낮게", 1f to "보통", 1.2f to "높게")
    val SLEEP = listOf(15, 30, 45, 60)

    // ── chapter cue sound (ChapterCue) ──

    const val CUE_SAMPLE_RATE = 22050
    const val CUE_LEN_S = 0.64
    /** How long the sound lasts; reading goes on after it. */
    const val CUE_MS = 700L

    // ── Azure (AzureSpeaker, AzureVoice) ──

    const val AZURE_AHEAD = 2
    const val AZURE_MAX_FAILS = 3
    const val AZURE_CACHE_MAX = 200L * 1024 * 1024
}
