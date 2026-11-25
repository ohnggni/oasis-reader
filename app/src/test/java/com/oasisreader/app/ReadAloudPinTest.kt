package com.oasisreader.app

import com.oasisreader.app.reader.ReadAloudNormalize
import com.oasisreader.app.reader.ReadAloudText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 3.0 safety net: pins today's output of the sentence splitter and the number/sign rules, quirks
 * included, so that moving constants around (TtsTuning) cannot change what is spoken.
 * Expected values were taken from the code as it was before the 3.0 refactor.
 */
class ReadAloudPinTest {
    private val head = ReadAloudText.HEAD

    private fun sent(html: String) = ReadAloudText.sentences(html)

    // ── ReadAloudText: cutting (LONG 250, HARD 400, MIN 30) ──

    @Test fun cutAtLastCommaBeforeLong() {
        val words = (1..40).joinToString(" ") { "가나다라$it," }
        val got = sent("<p>$words 끝.</p>")
        assertEquals(2, got.size)
        assertEquals(true, got[0].endsWith("가나다라32,"))
        assertEquals(true, got[0].length <= 250)
        assertEquals("가나다라33, 가나다라34, 가나다라35, 가나다라36, 가나다라37, 가나다라38, 가나다라39, 가나다라40, 끝.", got[1])
    }

    @Test fun sentenceOfExactlyLongIsOne() {
        val s = "가".repeat(249) + "."   // 250 letters
        assertEquals(listOf(s), sent("<p>$s</p>"))
    }

    @Test fun cutAtCommaOnlyWhenSpaceFollows() {
        val a = "가".repeat(100) + ","
        val b = "나".repeat(160) + "."
        assertEquals(listOf(a, b), sent("<p>$a $b</p>"))
    }

    @Test fun seventyWordsWithoutCommaStayWhole() {
        val t = (1..70).joinToString(" ") { "단어$it" }
        assertEquals(listOf(t), sent("<p>$t</p>"))
    }

    @Test fun overHardWithNoSpaceIsCutAtLong() {
        val got = sent("<p>" + "가".repeat(500) + "</p>")
        assertEquals(listOf(250, 250), got.map { it.length })
    }

    @Test fun shortTailIsNotCutOff() {
        val s = "가".repeat(260) + ", 짧다."
        assertEquals(listOf(s), sent("<p>$s</p>"))
    }

    // ── ReadAloudText: ends, headings, quotes ──

    @Test fun everySentenceEndCharacter() {
        assertEquals(
            listOf("하나。", "둘！", "셋？", "넷…", "다섯.", "여섯.\"일곱\" 여덟"),
            sent("<p>하나。둘！셋？넷… 다섯. 여섯.\"일곱\" 여덟</p>"),
        )
    }

    @Test fun chapterNumberHeadings() {
        assertEquals(listOf("제 5 장$head", "본문이다."), sent("<h2>제 5 장</h2><p>본문이다.</p>"))
        assertEquals(listOf("Chapter 2$head", "The Road$head", "프롤로그$head"), sent("<h2>Chapter 2 The Road</h2><h3>프롤로그</h3>"))
        assertEquals(listOf("3.$head"), sent("<h2>3.</h2>"))
    }

    @Test fun quotesFollowedByKorean() {
        assertEquals(
            listOf("그는 “간다.”고 했다.", "“간다.”", "했다.", "‘응.’이라니."),
            sent("<p>그는 “간다.”고 했다. “간다.” 했다. ‘응.’이라니.</p>"),
        )
        // a closing bracket counts as a closing quote: Korean right on means it goes on
        assertEquals(listOf("(끝났다.)그리고 시작."), sent("<p>(끝났다.)그리고 시작.</p>"))
    }

    @Test fun invisibleSpacesEndSentences() {
        assertEquals(listOf("하나다.", "둘이다.", "셋이다."), sent("<p>하나다. 둘이다.﻿셋이다.</p>"))
    }

    @Test fun notesRubyAndSignsOnlyLines() {
        assertEquals(listOf("글 이어 계속abcd."), sent("<p>글<sup>12</sup> 이어<a href=\"x.xhtml#f1\">[3]</a> 계속<sup>abcd</sup>.</p>"))
        assertEquals(emptyList<String>(), sent("<p>... !!! ??</p><p>—</p>"))
        assertEquals(listOf("漢자 읽기."), sent("<p><ruby>漢<rt>한</rt></ruby>자 읽기.</p>"))
    }

    // ── ReadAloudNormalize ──

    private fun say(x: String) = ReadAloudNormalize.apply(x)

    @Test fun numbersAsTheyAreToday() {
        assertEquals("이분의 일컵과 사분의 삼컵", say("1/2컵과 3/4컵"))
        assertEquals("이천이십육년 십일월 십오일 세 시 이십칠분", say("2026년 11월 15일 3시 27분"))
        assertEquals("백만 명과 마흔두 살", say("1,000,000명과 42살"))
        assertEquals("영하 삼 도 그리고 화씨 구십팔점육 도", say("-3℃ 그리고 98.6°F"))
        assertEquals("이 더하기 삼은 오와 사 곱하기 오", say("2+3=5와 4×5"))
        assertEquals("A4와 1.2.3", say("A4와 1.2.3"))
        assertEquals("열두 시 정각, 삼 대 이", say("12:00 정각, 3:2"))
        assertEquals("제삼장 첫 번째 이번 창구", say("제3장 1번째 2번 창구"))
        assertEquals("삼일 운동과 육이오 전쟁", say("3·1 운동과 6.25 전쟁"))
        assertEquals("오일팔 민주", say("5.18 민주"))
        assertEquals("백이십삼만 사천오백육십칠 명", say("1234567명"))
        assertEquals("이천이십사년 십일월 십팔일에", say("2024.11.18에"))
        assertEquals("천구백구십에서 이천년", say("1990-2000년"))
        assertEquals("세에서 다섯 개", say("3~5개"))
        assertEquals("오십 퍼센트포인트 그리고 영점오 퍼센트", say("50%p 그리고 0.5%"))
        assertEquals("a/b 11/15 3/100", say("a/b 11/15 3/100"))
        // no Hangul: left alone, even a phone number
        assertEquals("010-1234-5678", say("010-1234-5678"))
    }

    @Test fun middleDotAndPlusAsTheyAreToday() {
        assertEquals("언어, 언어학, 문학", say("언어·언어학 · 문학"))
        assertEquals("Kim, Lee 외국인", say("Kim·Lee 외국인"))
        assertEquals("한, 미 FTA", say("한·미 FTA"))
        assertEquals("주어 플러스 목적어", say("주어 + 목적어"))
        assertEquals("a 플러스 b와 가 플러스 나", say("a+b와 가+나"))
        // a number after the plus: the formula rule only takes it when a number is on both sides
        assertEquals("일 더하기 이 그리고 가+삼", say("1+2 그리고 가+3"))
    }

    @Test fun glossesAsTheyAreToday() {
        assertEquals("공과 소쉬르", say("공空과 소쉬르Saussure"))
        assertEquals("인문학과", say("인문학(人文學)과 (Ferdinand de Saussure)"))
        assertEquals("조지프 캠벨은 말했다", say("조지프 캠벨Joseph Campbell은 말했다"))
    }

    @Test fun signsAsTheyAreToday() {
        assertEquals("삼, 사과", say("③ 사과"))
        assertEquals("육$head", say("⑥$head"))
        assertEquals("사", say("Ⅳ"))
        assertEquals("모모와 김모수", say("○○와 김○수"))
        assertEquals("주의", say("※주의"))
    }
}
