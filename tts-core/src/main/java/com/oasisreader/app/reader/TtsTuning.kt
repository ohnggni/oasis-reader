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

    private const val NUMBER_HEAD = "제\\s?[0-9]+\\s?[장부편권화절]|[0-9]+\\s?[장부편]|(?:part|chapter|book)\\s*[0-9IVXivx]+|[\\u2460-\\u2473]|[\\u2160-\\u216B]|"
    private const val NUMBER_TAIL = "|prologue|epilogue|프롤로그|에필로그"
    /**
     * A line that is only a chapter number (제3장, 3장, PART 1, ⑥, Ⅲ, 1., 프롤로그), ignoring case.
     * The same text is the page script's TTS_LABEL.
     */
    const val TEXT_LABEL = "(?:" + NUMBER_HEAD + "[0-9]+\\.?" + NUMBER_TAIL + ")"
    /** A chapter number at the start of a heading line; bare digits need a dot. The page script's TTS_LEAD. */
    const val TEXT_LEAD = "(?:" + NUMBER_HEAD + "[0-9]+\\." + NUMBER_TAIL + ")"

    // ── pauses and limits (ReadAloud) ──

    /** 문장 사이: 없음, 짧게, 보통 (2.4의 350ms), 길게. */
    val GAP_MS = intArrayOf(0, 150, 350, 600)
    /** After a heading or a chapter number line (85): a step longer than after a sentence. */
    val HEAD_MS = intArrayOf(150, 350, 600, 900)
    const val GAP_NORMAL = 2
    /** A line ending in one of these gets the full 문장 사이 pause. */
    const val GAP_END_MARKS = ".?!…。？！\"'”’」』)]"
    /** A line with no end mark (a 텍본 line): at most this, so lines don't run together nor drag. */
    const val GAP_NO_MARK_MS = 150
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
