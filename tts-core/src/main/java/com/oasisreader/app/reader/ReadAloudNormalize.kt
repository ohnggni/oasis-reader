package com.oasisreader.app.reader

/**
 * 소리 내 읽기 전처리: numbers and signs in a Korean sentence are written out in Hangul before the
 * sentence goes to the engine, so that engines without their own number reading (the AI voices:
 * "1/2" was read "일 슬래시 이", "12,500" "십이천오백", "-3℃" "다시삼도씨") read them right.
 * Engines that already read numbers well (Samsung, Edge) read the Hangul the same.
 *
 * One sentence in, one sentence out: the sentence list (and so the marked sentence on the page)
 * never changes. Only sentences with Hangul in them are touched, and what is unclear is left as
 * written (reading "1:2" or "11/15" wrong is worse than leaving it to the engine).
 *
 * Rules run in order, each on what the ones before left; a rule's output has no digits, so a
 * later rule never reads a number twice. To add a rule, put it in [RULES] at the right place
 * and add a case to ReadAloudNormalizeTest.
 */
object ReadAloudNormalize {

    // ── numbers to words ──

    private val DIGIT = arrayOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val PLACE = arrayOf("", "십", "백", "천")
    private val GROUP = arrayOf("", "만", "억", "조", "경")
    private val TENS = arrayOf("", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")
    private val ONES = arrayOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")

    /** 한자어 reading of a whole number: 12500 → "만 이천오백", 1000000 → "백만", 0 → "영". */
    fun sino(n: Long): String {
        if (n == 0L) return "영"
        val out = ArrayList<String>()
        var rest = n
        var g = 0
        while (rest > 0 && g < GROUP.size) {
            val part = (rest % 10000).toInt()
            if (part > 0) out.add(0, if (g == 1 && part == 1) "만" else four(part) + GROUP[g])
            rest /= 10000; g++
        }
        return out.joinToString(" ")
    }

    private fun four(n: Int): String {
        val sb = StringBuilder()
        var p = 3
        var d = 1000
        while (p >= 0) {
            val x = n / d % 10
            if (x > 0) sb.append(if (x == 1 && p > 0) "" else DIGIT[x]).append(PLACE[p])
            p--; d /= 10
        }
        return sb.toString()
    }

    /** 고유어 reading before a counter, 1..99: 3 → "세", 20 → "스무", 42 → "마흔두". */
    fun native(n: Int): String {
        require(n in 1..99)
        if (n == 20) return "스무"
        return TENS[n / 10] + ONES[n % 10]
    }

    /** Digit by digit, 0 as 공 (phone and other long numbers). */
    fun digits(s: String): String = s.filter { it.isDigit() }.map { if (it == '0') "공" else DIGIT[it - '0'] }.joinToString("")

    /** A written number ("12,500", "3.14", "0.5") in 한자어: "만 이천오백", "삼점일사", "영점오". */
    fun number(written: String): String {
        val s = written.replace(",", "")
        val int = s.substringBefore('.')
        val frac = if ('.' in s) s.substringAfter('.') else ""
        val head = if (int.length > 16) digits(int) else sino(int.toLong())
        return if (frac.isEmpty()) head else head + "점" + frac.map { DIGIT[it - '0'] }.joinToString("")
    }

    private fun whole(written: String): Long? = written.replace(",", "").takeIf { '.' !in it && it.length <= 16 }?.toLong()

    /** With a 고유어 counter: 1..99 in 고유어, else 한자어 (백 명, 영 개, 일점오 개). */
    private fun counted(written: String): String {
        val n = whole(written)
        return if (n != null && n in 1..99) native(n.toInt()) else number(written)
    }

    // ── patterns ──

    /** A number as written: 12,500 · 3.14 · 2026. */
    private const val NUM = """(?:\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)"""
    /** Not glued to letters or other digits before / after (A4, 1.2.3, 1,2 stay as written). */
    private const val PRE = """(?<![\dA-Za-z.,_])"""
    private const val POST = """(?![\d]|[.,]\d)"""

    /** Counters read in 고유어 (세 개, 다섯 명). 개월·개국 are 한자어 (삼 개월). */
    private val COUNTERS = listOf(
        "개(?!월|국|년|소|비)", "명", "살", "시간", "권", "병", "마리", "벌", "켤레", "그릇", "송이", "잔", "척",
        "채", "곳", "군데", "가지", "사람", "줄", "통", "알", "쌍", "갑", "접시", "개비", "자루", "포기", "다발",
        "바퀴", "걸음", "달(?!러)", "끼", "모금", "숟가락", "방울", "번째",
    )
    private val COUNTER = COUNTERS.sortedByDescending { it.length }.joinToString("|")

    /** Unit signs → words, longest first. */
    private val UNITS = listOf(
        "km/h" to "킬로미터", "km²" to "제곱킬로미터", "㎢" to "제곱킬로미터", "m²" to "제곱미터", "㎡" to "제곱미터", "m³" to "세제곱미터", "㎥" to "세제곱미터",
        "cm²" to "제곱센티미터", "㎠" to "제곱센티미터",
        "km" to "킬로미터", "㎞" to "킬로미터", "cm" to "센티미터", "㎝" to "센티미터", "mm" to "밀리미터", "㎜" to "밀리미터",
        "m" to "미터", "kg" to "킬로그램", "㎏" to "킬로그램", "mg" to "밀리그램", "㎎" to "밀리그램", "g" to "그램",
        "mL" to "밀리리터", "ml" to "밀리리터", "㎖" to "밀리리터", "L" to "리터", "ℓ" to "리터", "cc" to "시시",
        "TB" to "테라바이트", "GB" to "기가바이트", "MB" to "메가바이트", "KB" to "킬로바이트",
        "GHz" to "기가헤르츠", "MHz" to "메가헤르츠", "kHz" to "킬로헤르츠", "Hz" to "헤르츠",
        "kWh" to "킬로와트시", "kW" to "킬로와트", "mAh" to "밀리암페어시", "W" to "와트", "V" to "볼트",
    )
    private val UNIT_WORD = UNITS.toMap()
    private val UNIT = UNITS.map { it.first }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val OPS = mapOf('+' to "더하기", '-' to "빼기", '−' to "빼기", '×' to "곱하기", '*' to "곱하기", '÷' to "나누기", '/' to "나누기")

    /** 은/는 after a word: by the last syllable's final consonant. */
    private fun topic(word: String): String {
        val c = word.lastOrNull { it in '가'..'힣' } ?: return "는"
        return if ((c - '가') % 28 != 0) "은" else "는"
    }

    private class Rule(val re: Regex, val to: (MatchResult) -> String)
    private fun rule(p: String, to: (MatchResult) -> String) = Rule(Regex(p), to)

    private val RULES: List<Rule> = listOf(
        // 2024.11.18, 2024-11-18 → 이천이십사년 십일월 십팔일
        rule("""(?<![\d.\-])((?:19|20)\d{2})([.\-])\s?(0?[1-9]|1[0-2])\2\s?(0?[1-9]|[12]\d|3[01])\.?(?![\d.\-])""") { m ->
            val mo = m.groupValues[3].toInt()
            sino(m.groupValues[1].toLong()) + "년 " + (if (mo == 6) "유" else if (mo == 10) "시" else sino(mo.toLong())) + "월 " + sino(m.groupValues[4].toLong()) + "일"
        },
        // 6.25 전쟁, 3·1 운동, 5.18 → 육이오, 삼일, 오일팔 (read digit by digit)
        rule("""(?<![\d.])([1-9])[.·]\s?(\d{1,2})(?=\s?(?:전쟁|사변|동란|운동|혁명|민주|광복|절|항쟁|의거|선언|사태|참사))""") { m ->
            (m.groupValues[1] + m.groupValues[2]).map { DIGIT[it - '0'] }.joinToString("")
        },
        // 010-1234-5678 (also 02-123-4567): digit by digit, 0 as 공
        rule("""(?<![\d-])(0\d{1,2})-(\d{3,4})-(\d{4})(?![\d-])""") { m -> m.groupValues.drop(1).joinToString(" ") { digits(it) } },
        // 2+3=5, 6÷2=3: a formula with "=" (the answer gets 은/는)
        rule("""$PRE($NUM(?:\s*[+\-−×*÷/]\s*$NUM)+)\s*=\s*($NUM)$POST""") { m ->
            val parts = Regex("""$NUM|[+\-−×*÷/]""").findAll(m.groupValues[1]).map { it.value }
                .joinToString(" ") { OPS[it.single()] ?: number(it) }
            parts + topic(parts) + " " + number(m.groupValues[2])
        },
        // 2+3, 4×5 without "=" (not - and /: ranges, dates, fractions)
        rule("""$PRE($NUM)\s*([+×÷])\s*($NUM)$POST""") { m -> number(m.groupValues[1]) + " " + OPS[m.groupValues[2].single()] + " " + number(m.groupValues[3]) },
        // 15:27, 9:05:30 → 열다섯 시 이십칠 분 (hour in 고유어)
        rule("""(?<![\d:])([01]\d|2[0-4]|\d(?=:\d\d(?::\d\d)?(?:에|부터|까지|쯤|경|께|\s?[~∼〜\-–]|\s?(?:시작|출발|도착|개장|마감|오픈)))):([0-5]\d)(?::([0-5]\d))?(?![\d:])""") { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val sec = m.groupValues[3].toIntOrNull() ?: 0
            buildString {
                append(if (h == 0) "영" else native(h)).append(" 시")
                if (min > 0) append(" ").append(sino(min.toLong())).append(" 분")
                if (sec > 0) append(" ").append(sino(sec.toLong())).append(" 초")
            }
        },
        // 3:2, 10:0 (score, ratio) → 삼 대 이; 3:16 (a verse?) is left
        rule("""$PRE(\d{1,3})\s?:\s?(\d)(?![\d:])""") { m -> sino(m.groupValues[1].toLong()) + " 대 " + sino(m.groupValues[2].toLong()) },
        // 1/2, 3/4 → 이분의 일 (only small denominators: 11/15 may be a date)
        rule("""(?<![\d/.,])(\d{1,3})/(\d{1,4})(?![\d/])""") { m ->
            val den = m.groupValues[2].toInt()
            if (den in 2..10 || den == 100 || den == 1000) sino(den.toLong()) + "분의 " + sino(m.groupValues[1].toLong()) else m.value
        },
        // 3~5개 → 세에서 (다섯 개 follows), 3~5km → 삼에서, 9~11시 → 아홉에서
        rule("""$PRE($NUM)\s?[~∼〜]\s?(?=$NUM\s?(?:$COUNTER|시(?!리|즌|청|장|민|티|트|스|험|집|작|인|계|각|점|절|선|대|도|기|가)))""") { m -> counted(m.groupValues[1]) + "에서 " },
        rule("""$PRE($NUM)\s?[~∼〜]\s?(?=$NUM)""") { m -> number(m.groupValues[1]) + "에서 " },
        // -3℃ → 영하 삼 도, 98.6°F → 화씨 구십팔점육 도
        rule("""(?<![\dA-Za-z])([-−]?)($NUM)\s?(℃|°C|℉|°F)""") { m ->
            val f = m.groupValues[3].let { it == "℉" || it == "°F" }
            (if (f) "화씨 " else "") + (if (m.groupValues[1].isNotEmpty()) "영하 " else "") + number(m.groupValues[2]) + " 도"
        },
        // 50%, 0.5% → 오십 퍼센트, 3%p → 삼 퍼센트포인트
        rule("""$PRE($NUM)\s?[%％](p|포인트)?""") { m -> number(m.groupValues[1]) + " 퍼센트" + (if (m.groupValues[2].isNotEmpty()) "포인트" else "") },
        // 1990-2000 (two years) → 천구백구십에서 이천
        rule("""(?<![\d-])(\d{4})\s?[-–]\s?(\d{4})(?![\d-])""") { m -> sino(m.groupValues[1].toLong()) + "에서 " + sino(m.groupValues[2].toLong()) },
        // 5km, 85㎡ → 오 킬로미터, 팔십오 제곱미터
        rule("""$PRE($NUM)\s?($UNIT)(?![A-Za-z²³/])""") { m -> number(m.groupValues[1]) + " " + UNIT_WORD.getValue(m.groupValues[2]) },
        // 제1권, 제3장: always 한자어
        rule("""제\s?($NUM)$POST""") { m -> "제" + number(m.groupValues[1]) },
        // 1번째 → 첫 번째, 1번 더 / 1번만 / 2번씩 → 한 번 (else 번 is a number: 2번 창구 → 이번)
        rule("""$PRE(\d{1,2})\s?번(?=째)""") { m -> (if (m.groupValues[1] == "1") "첫" else native(m.groupValues[1].toInt().coerceIn(1, 99))) + " 번" },
        rule("""$PRE(\d{1,2})\s?번(?=\s?(?:더|만|도|씩|쯤|정도|이나|밖에|가량))""") { m ->
            val n = m.groupValues[1].toInt()
            if (n in 1..99) native(n) + " 번" else m.value
        },
        // 3시 → 세 시 (hour of the clock and 시간 in 고유어)
        rule("""$PRE([01]?\d|2[0-4])\s?시(?!리|즌|청|장|민|티|트|스|험|집|작|인|계|각|점|절|선|대|도|기|가)""") { m ->
            val h = m.groupValues[1].toInt()
            (if (h == 0) "영" else native(h)) + " 시"
        },
        // 3개, 42살, 1,000,000명 → 세 개, 마흔두 살, 백만 명
        rule("""$PRE($NUM)\s?($COUNTER)""") { m -> counted(m.groupValues[1]) + " " + m.groupValues[2] },
        // -3 (a sign, not a dash between numbers) → 마이너스 삼
        rule("""(?<=^|[\s(\[“"'‘])[-−](?=\d)""") { "마이너스 " },
        // 6월 → 유월, 10월 → 시월
        rule("""$PRE(0?6|10)\s?월""") { m -> if (m.groupValues[1].endsWith("6")) "유월" else "시월" },
        // 7 digits or more without commas: digit by digit (codes, account and ID numbers)
        rule("""$PRE(\d{7,})(?![\d.])""") { m -> digits(m.groupValues[1]) },
        // anything left: 한자어 (2026년 → 이천이십육년, 3.14 → 삼점일사, 3층 → 삼층)
        rule("""$PRE($NUM)$POST""") { m -> number(m.groupValues[1]) },
    )

    private val HANGUL = Regex("[가-힣]")
    private val HAS_DIGIT = Regex("[0-9]")

    // ── signs (86) ──

    private const val GANADA = "가나다라마바사아자차카타파하"
    /** ① ⑴ ⒈ Ⅰ ⅰ ㉠ ㉮ ※ ○○: signs the engines skip or read as their shape. */
    private val SIGNS = Regex("[\\u2460-\\u249B\\u2160-\\u217B\\u3260-\\u327B※○◯]")
    /** A list number at the start of a line (⑥ 감정의 자각과 행동): read with a short pause after it. */
    private val LEAD_NUMBER = Regex("^(\\s*)([\\u2460-\\u249B\\u2160-\\u217B\\u3260-\\u327B])\\s+(?=\\S)")

    /** The number or letter a sign stands for: ⑥ → 육, ⑿ → 십이, Ⅲ → 삼, ㉡ → 나; null if not one. */
    fun signWord(c: Char): String? = when (c) {
        in '\u2460'..'\u2473' -> sino((c - '\u2460' + 1).toLong())       // ① … ⑳
        in '\u2474'..'\u2487' -> sino((c - '\u2474' + 1).toLong())       // ⑴ … ⒇
        in '\u2488'..'\u249B' -> sino((c - '\u2488' + 1).toLong())       // ⒈ … ⒛
        in '\u2160'..'\u216B' -> sino((c - '\u2160' + 1).toLong())       // Ⅰ … Ⅻ
        in '\u2170'..'\u217B' -> sino((c - '\u2170' + 1).toLong())       // ⅰ … ⅻ
        in '\u3260'..'\u326D' -> GANADA[c - '\u3260'].toString()          // ㉠ … ㉭
        in '\u326E'..'\u327B' -> GANADA[c - '\u326E'].toString()          // ㉮ … ㉻
        else -> null
    }

    /**
     * Signs in words: ①~⑳ ⑴ ⒈ Ⅰ ⅰ → 일 이 …, ㉠ ㉮ → 가 나 …, ※ not read, ○○ (a name or thing left
     * out) → 모모(某某), one ○ inside a name (김○수) → 모. A list number at the line start gets a comma.
     */
    fun signs(sentence: String): String {
        if (!SIGNS.containsMatchIn(sentence)) return sentence
        var s = LEAD_NUMBER.replace(sentence) { m -> m.groupValues[1] + (signWord(m.groupValues[2][0]) ?: m.groupValues[2]) + ", " }
        s = s.replace(Regex("[○◯]{2,}"), "모모")
            .replace(Regex("(?<=[가-힣])[○◯](?=[가-힣])"), "모")
            .replace(Regex("\\s*※\\s*"), " ")
        val out = StringBuilder(s.length)
        for (c in s) out.append(signWord(c) ?: c)
        return out.toString().replace(Regex(" {2,}"), " ").trim()
    }

    /** A line that is only a list or chapter number (⑥, Ⅲ — a heading's number line). */
    private fun signOnly(s: String) = SIGN_ONLY.matches(s.trim())
    private val SIGN_ONLY = Regex("[\\u2460-\\u249B\\u2160-\\u217B\\u3260-\\u327B][.\\u2063]*")

    /** The sentence as it should be spoken. Sentences without Hangul (other languages) are kept as they are. */
    fun apply(sentence: String): String {
        if (signOnly(sentence)) return signs(sentence)
        if (!HANGUL.containsMatchIn(sentence)) return sentence
        return rules(sentence)
    }

    // ── words joined, words glossed (100, 진석님 2026-11-24) ──

    private const val HANJA = "\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF"
    /** 인문학(人文學) · 소쉬르(Saussure): a gloss in another script right after a Korean word. */
    private val GLOSS_PAREN = Regex("(?<=[가-힣])\\s?[(（](?:[A-Za-z][A-Za-z\\s.'&-]*|[$HANJA][$HANJA\\s·,]*)[)）]")
    /**
     * 소쉬르Saussure · 공空: the same, with no brackets. The Latin may run on over several words —
     * 조지프 캠벨Joseph Campbell은, 리틀 포레스트Little Forest」, 타트 트밤 아시Tat tvam asi’ (2.10.x):
     * every word after it that is Latin only goes with it, up to the first Korean or sign.
     */
    private val GLOSS_LATIN = Regex("(?<=[가-힣])[A-Za-z][A-Za-z.'-]*(?:\\s+[A-Za-z][A-Za-z.'-]*)*")
    private val GLOSS_HANJA = Regex("(?<=[가-힣])[$HANJA]+")
    /** 언어·언어학·문학: a list — a comma's pause. Numbers (3·1 운동) are left to the number rules. */
    private val MID_DOT = Regex("(?<=[가-힣A-Za-z)’'\"])\\s?[·・ㆍ]\\s?(?=[가-힣A-Za-z(‘'\"])")
    /**
     * 말했다—그러나 · 그것은 —— 바로: a long dash (— ―, one or several) between words — a comma's
     * pause. The en dash (–, number ranges) is not one of them. A dictionary word (private use
     * letter while the rules run) counts as a word.
     */
    private val LONG_DASH = Regex(
        "(?<=[\\p{L}\\p{N}\\p{Co})\\]）’”'\"」』〉》%…])\\s*[—―](?:\\s*[—―])*\\s*(?=[\\p{L}\\p{N}\\p{Co}(\\[（‘“'\"「『〈《…])",
    )
    /** 주어+목적어: read 플러스 (numbers: 더하기, in the number rules). */
    private val PLUS = Regex("(?<=[가-힣A-Za-z’'\"])\\s?\\+\\s?(?=[가-힣A-Za-z‘'\"])")

    /**
     * A gloss is said once: a Latin or Hanja word right after a Korean one, in brackets or not,
     * only explains it (소쉬르Saussure는 → 소쉬르는, 공空 → 공, 인문학(人文學) → 인문학).
     * Words joined by · are a list (a comma's pause), by + are read 플러스; a long dash between
     * words is a comma's pause too (107).
     */
    fun joins(sentence: String): String {
        var s = sentence
        s = GLOSS_PAREN.replace(s, "")
        s = GLOSS_LATIN.replace(s, "")
        s = GLOSS_HANJA.replace(s, "")
        s = MID_DOT.replace(s, ", ")
        s = LONG_DASH.replace(s, ", ")
        s = PLUS.replace(s, " 플러스 ")
        return s
    }

    private fun rules(sentence: String): String {
        val signed = signs(joins(sentence))
        if (!HAS_DIGIT.containsMatchIn(signed)) return signed
        var s = signed
        for (r in RULES) s = r.re.replace(s) { m -> runCatching { r.to(m) }.getOrDefault(m.value) }
        return s
    }

    // ── 발음 사전 ──

    private fun kind(c: Char): Int = when {
        c in 'A'..'Z' || c in 'a'..'z' -> 1
        c.isDigit() -> 2
        c in '가'..'힣' || c in '\u3131'..'\u318E' -> 3
        else -> 0
    }

    /**
     * Pronunciation dictionary first ("표현 → 읽는 법"), then the number rules ([normalize]) on the
     * rest: what the dictionary put in is not read again. Plain text, longest first; a match is not
     * taken out of a longer word of the same script (GPT in GPTs, 홍길동 in 김홍길동), but Korean
     * particles may follow (홍길동은). Latin letters match in any case.
     */
    fun speak(sentence: String, dict: List<Pair<String, String>>, normalize: Boolean): String {
        if (dict.isEmpty() || (normalize && signOnly(sentence))) return if (normalize) apply(sentence) else sentence
        val words = dict.filter { it.first.isNotBlank() }.sortedByDescending { it.first.length }
        val out = StringBuilder()
        val readings = ArrayList<String>()
        var i = 0
        while (i < sentence.length) {
            var hit: Pair<String, String>? = null
            for (e in words) {
                val w = e.first
                if (!sentence.regionMatches(i, w, 0, w.length, ignoreCase = true)) continue
                val first = kind(w.first())
                if (first != 0 && i > 0 && kind(sentence[i - 1]) == first) continue
                val last = kind(w.last())
                val j = i + w.length
                if ((last == 1 || last == 2) && j < sentence.length && kind(sentence[j]) == last) continue
                hit = e; break
            }
            if (hit == null) { out.append(sentence[i]); i++; continue }
            if (readings.size >= 6000) { out.append(hit.second); i += hit.first.length; continue }
            out.append((0xE000 + readings.size).toChar())   // kept away from the number rules
            readings += hit.second
            i += hit.first.length
        }
        if (readings.isEmpty()) return if (normalize) apply(sentence) else sentence
        var s = out.toString()
        val hangul = HANGUL.containsMatchIn(s) || readings.any { HANGUL.containsMatchIn(it) }
        if (normalize && hangul) s = rules(s)
        val back = StringBuilder()
        for (c in s) back.append(if (c.code in 0xE000 until 0xE000 + readings.size) readings[c.code - 0xE000] else c)
        return back.toString()
    }
}
