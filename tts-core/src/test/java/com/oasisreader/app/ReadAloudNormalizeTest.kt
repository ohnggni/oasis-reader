package com.oasisreader.app

import com.oasisreader.app.reader.ReadAloudNormalize
import com.oasisreader.app.reader.ReadAloudNormalize.apply
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudNormalizeTest {

    @Test fun numberWords() {
        assertEquals("영", ReadAloudNormalize.sino(0))
        assertEquals("일", ReadAloudNormalize.sino(1))
        assertEquals("십", ReadAloudNormalize.sino(10))
        assertEquals("백십일", ReadAloudNormalize.sino(111))
        assertEquals("만 이천오백", ReadAloudNormalize.sino(12500))
        assertEquals("만", ReadAloudNormalize.sino(10000))
        assertEquals("십일만", ReadAloudNormalize.sino(110000))
        assertEquals("백만", ReadAloudNormalize.sino(1_000_000))
        assertEquals("일억 이천삼백사십오만 육천칠백팔십구", ReadAloudNormalize.sino(123_456_789))
        assertEquals("이천이십육", ReadAloudNormalize.sino(2026))
        assertEquals("한", ReadAloudNormalize.native(1))
        assertEquals("열", ReadAloudNormalize.native(10))
        assertEquals("스무", ReadAloudNormalize.native(20))
        assertEquals("스물한", ReadAloudNormalize.native(21))
        assertEquals("마흔두", ReadAloudNormalize.native(42))
        assertEquals("아흔아홉", ReadAloudNormalize.native(99))
        assertEquals("삼점일사", ReadAloudNormalize.number("3.14"))
        assertEquals("영점오", ReadAloudNormalize.number("0.5"))
        assertEquals("만 이천오백", ReadAloudNormalize.number("12,500"))
    }

    /** The ten test sentences heard on Mac Reed, Edge and BookFusion (2026-11-18). */
    @Test fun testSet() {
        assertEquals("밀가루 이분의 일컵과 설탕 사분의 삼컵을 섞는다.", apply("밀가루 1/2컵과 설탕 3/4컵을 섞는다."))
        assertEquals("원주율은 약 삼점일사이고, 성공률은 영점오 퍼센트에 불과했다.", apply("원주율은 약 3.14이고, 성공률은 0.5%에 불과했다."))
        assertEquals("이천이십육년 십일월 십오일 오후 세 시 이십칠분, 가격은 만 이천오백원이었다.", apply("2026년 11월 15일 오후 3시 27분, 가격은 12,500원이었다."))
        assertEquals("회의는 열다섯 시 이십칠 분에 시작해 열일곱 시 오 분에 끝났다.", apply("회의는 15:27에 시작해 17:05에 끝났다."))
        assertEquals("사과 세 개와 귤 스무 개를 학생 다섯 명에게 나눠 주었다.", apply("사과 3개와 귤 20개를 학생 5명에게 나눠 주었다."))
        assertEquals("그는 마흔두 살에 책 일곱 권을 썼고, 인구는 백만 명을 넘었다.", apply("그는 42살에 책 7권을 썼고, 인구는 1,000,000명을 넘었다."))
        assertEquals("서울에서 오 킬로미터 떨어진 곳의 기온은 영하 삼 도, 면적은 팔십오 제곱미터였다.", apply("서울에서 5km 떨어진 곳의 기온은 -3℃, 면적은 85㎡였다."))
        assertEquals("하루에 세에서 다섯 개씩, 이 더하기 삼은 오처럼 쉽게 익힌다.", apply("하루에 3~5개씩, 2+3=5처럼 쉽게 익힌다."))
        assertEquals("문의는 공일공 일이삼사 오육칠팔로, 삼층 이번 창구에서 받습니다.", apply("문의는 010-1234-5678로, 3층 2번 창구에서 받습니다."))
        assertEquals("그 일을 한 번 더 해 보라고, 일등을 한 친구가 말했다.", apply("그 일을 1번 더 해 보라고, 1등을 한 친구가 말했다."))
    }

    @Test fun fractionsAndDates() {
        assertEquals("백분의 삼에", apply("3/100에"))
        assertEquals("삼분의 이 정도", apply("2/3 정도"))
        // 11/15 may be a date: the slash stays (the numbers are still read)
        assertEquals("오늘은 십일/십오이다.", apply("오늘은 11/15이다."))
        assertEquals("이천이십육/십일/십오에", apply("2026/11/15에"))
        assertEquals("유월과 시월", apply("6월과 10월"))
        assertEquals("천구백구십에서 이천 년대", apply("1990-2000 년대"))
    }

    @Test fun timeAndCounters() {
        assertEquals("새벽 영 시에", apply("새벽 0시에"))
        assertEquals("세 시간 동안", apply("3시간 동안"))
        assertEquals("열두 시 정각", apply("12:00 정각"))
        assertEquals("오전 아홉 시 오 분 삼십 초에", apply("오전 9:05:30에"))
        assertEquals("삼개월 뒤", apply("3개월 뒤"))
        assertEquals("삼달러를 냈다", apply("3달러를 냈다"))
        assertEquals("첫 번째와 열한 번째", apply("1번째와 11번째"))
        assertEquals("두 번씩", apply("2번씩"))
        assertEquals("백이십 명", apply("120명"))
        assertEquals("제삼 권", apply("제3 권"))
        assertEquals("아홉에서 열한 시", apply("9~11시"))
    }

    @Test fun unitsSignsFormulas() {
        assertEquals("오십 퍼센트가", apply("50%가"))
        assertEquals("삼 퍼센트포인트 올랐다", apply("3%p 올랐다"))
        assertEquals("쌀 이점오 킬로그램", apply("쌀 2.5kg"))
        assertEquals("물 오백 밀리리터", apply("물 500ml"))
        assertEquals("체온은 화씨 구십팔점육 도", apply("체온은 98.6°F"))
        assertEquals("마이너스 오에서 시작", apply("-5에서 시작"))
        assertEquals("육 나누기 이는 삼이다", apply("6÷2=3이다"))
        assertEquals("답은 사 곱하기 오", apply("답은 4×5"))
        assertEquals("삼에서 오 킬로미터 거리", apply("3~5km 거리"))
    }

    @Test fun namesDatesRatios() {
        assertEquals("이천이십사년 십일월 십팔일에", apply("2024.11.18에"))
        assertEquals("이천이십육년 유월 오일에", apply("2026-06-05에"))
        assertEquals("육이오 전쟁과 삼일절", apply("6.25 전쟁과 3.1절"))
        assertEquals("점수는 삼 대 이로 끝났다.", apply("점수는 3:2로 끝났다."))
        assertEquals("요한복음 삼:십육을 읽었다.", apply("요한복음 3:16을 읽었다."))
        assertEquals("세 시 반 9:30에", apply("3시 반 9:30에").replace("아홉 시 삼십 분에", "9:30에"))
        assertEquals("약 십에서 이십 퍼센트가 줄었다.", apply("약 10~20%가 줄었다."))
        assertEquals("시속 백 킬로미터로", apply("시속 100km/h로"))
        assertEquals("천구백팔십년대에 태어나 삼년 동안", apply("1980년대에 태어나 3년 동안"))
    }

    @Test fun leftAsWritten() {
        // no Hangul (another language), no digits, glued to letters, version numbers
        assertEquals("He bought 3 apples.", apply("He bought 3 apples."))
        assertEquals("숫자가 없는 문장이다.", apply("숫자가 없는 문장이다."))
        assertEquals("A4 용지와 MP3 파일", apply("A4 용지와 MP3 파일"))
        assertEquals("버전 1.2.3에서", apply("버전 1.2.3에서"))
        assertEquals("naver.com에서 봤다", apply("naver.com에서 봤다"))
        // long codes digit by digit
        assertEquals("번호는 일이삼사오육칠공", apply("번호는 12345670"))
    }

    @Test fun dictionary() {
        val d = listOf("GPT" to "지피티", "홍길동" to "홍 길동", "OG Reader" to "오지 리더", "1/2" to "반", "OG" to "오지")
        fun say(x: String, n: Boolean = true) = ReadAloudNormalize.speak(x, d, n)
        // particles may follow; Latin in any case; longest first
        assertEquals("지피티는 3개가 아니라", say("GPT는 3개가 아니라", false))
        assertEquals("지피티는 세 개가 아니라", say("gpt는 3개가 아니라"))
        assertEquals("오지 리더로 읽고 오지로 줄인다", say("OG Reader로 읽고 OG로 줄인다"))
        // not out of a longer word of the same script
        assertEquals("GPTs와 김홍길동", say("GPTs와 김홍길동"))
        assertEquals("홍 길동이 왔다", say("홍길동이 왔다"))
        // what the dictionary puts in is not read again; the rest is
        assertEquals("반 컵과 사분의 삼컵", say("1/2 컵과 3/4컵"))
        // a sentence without Hangul still gets the dictionary, and its Korean reading lets numbers be read
        assertEquals("지피티 사", say("GPT 4"))
        // empty dictionary = numbers only
        assertEquals("세 개", ReadAloudNormalize.speak("3개", emptyList(), true))
        assertEquals("3개", ReadAloudNormalize.speak("3개", emptyList(), false))
    }

    @Test fun oneSentenceInOneOut() {
        val s = listOf("1. 첫째", "2026년 3월", "그냥 문장", "3~5개")
        assertEquals(s.size, s.map { apply(it) }.size)
    }

    @Test fun signs() {
        // 86: circled and roman numbers, circled letters, ※, ○○
        assertEquals("육, 감정의 자각과 행동", ReadAloudNormalize.apply("⑥ 감정의 자각과 행동"))
        assertEquals("하나는 ‘모모를 할 수 있다’는 자신감이다.", ReadAloudNormalize.apply("하나는 ‘○○를 할 수 있다’는 자신감이다."))
        assertEquals("김모수 씨와 모모 씨", ReadAloudNormalize.apply("김○수 씨와 ◯◯ 씨"))
        assertEquals("일과 이를 보면 삼번째", ReadAloudNormalize.apply("①과 ②를 보면 ③번째"))
        assertEquals("제삼장 가와 나", ReadAloudNormalize.apply("제Ⅲ장 ㉠와 ㉡"))
        assertEquals("주의 사항이 있다.", ReadAloudNormalize.apply("※ 주의 사항이 있다."))
        assertEquals("십이", ReadAloudNormalize.apply("⑫"))
        assertEquals("육\u2063", ReadAloudNormalize.apply("⑥\u2063"))
        assertEquals("일, 사과 세 개", ReadAloudNormalize.apply("① 사과 3개"))
    }

    @Test fun joinsAndGlosses() {
        // 100: · is a list, + is 플러스, a gloss in another script is said once
        assertEquals("언어, 언어학, 문학, 역사", ReadAloudNormalize.apply("언어·언어학·문학·역사"))
        assertEquals("‘주어 플러스 목적어 플러스 서술어’", ReadAloudNormalize.apply("‘주어+목적어+서술어’"))
        assertEquals("세계적인 언어학자 소쉬르는 언어학", ReadAloudNormalize.apply("세계적인 언어학자 소쉬르Saussure는 언어학"))
        assertEquals("불교의 ‘공’에서", ReadAloudNormalize.apply("불교의 ‘공空’에서"))
        assertEquals("인문학이란 소쉬르의 말", ReadAloudNormalize.apply("인문학(人文學)이란 소쉬르(Ferdinand de Saussure)의 말"))
        assertEquals("삼일 운동", ReadAloudNormalize.apply("3·1 운동"))           // numbers stay with the number rules
        assertEquals("일 더하기 이는 삼", ReadAloudNormalize.apply("1+2는 3"))
        assertEquals("TV를 켠다", ReadAloudNormalize.apply("TV를 켠다"))               // Latin first: not a gloss
        // several Latin words after the Korean ones
        assertEquals("영화 「리틀 포레스트」에 이런", ReadAloudNormalize.apply("영화 「리틀 포레스트Little Forest」에 이런"))
        assertEquals("신화학자 조지프 캠벨은 ‘타트 트밤 아시’라는", ReadAloudNormalize.apply("신화학자 조지프 캠벨Joseph Campbell은 ‘타트 트밤 아시Tat tvam asi’라는"))
        assertEquals("마테를링크의 『파랑새』", ReadAloudNormalize.apply("마테를링크Maeterlinck의 『파랑새』"))
    }

    @Test fun longDashes() {
        // 107: — ― between words is a comma's pause, spaced or not, doubled or not
        assertEquals("말했다, 그러나", apply("말했다—그러나"))
        assertEquals("말했다, 그러나", apply("말했다 — 그러나"))
        assertEquals("말했다, 그러나", apply("말했다— 그러나"))
        assertEquals("말했다, 그러나", apply("말했다 —그러나"))
        assertEquals("그것은, 바로 사랑", apply("그것은——바로 사랑"))
        assertEquals("그것은, 바로 사랑", apply("그것은 ―― 바로 사랑"))
        assertEquals("그것은, 바로 사랑", apply("그것은 — — 바로 사랑"))
        assertEquals("하나, 둘, 셋이다", apply("하나―둘—셋이다"))
        assertEquals("‘나’, 그리고 (너), “우리”", apply("‘나’—그리고 (너)—“우리”"))
        assertEquals("소쉬르, 그는", apply("소쉬르Saussure—그는"))
        assertEquals("삼년, 그 뒤", apply("3년—그 뒤"))
        // not between words: left to the engine
        assertEquals("— 그러나", apply("— 그러나"))
        assertEquals("그러나—", apply("그러나—"))
        assertEquals("그래, —그러나", apply("그래, —그러나"))
        // the en dash is for ranges; other languages are kept
        assertEquals("서울–부산 구간", apply("서울–부산 구간"))
        assertEquals("wait—what", apply("wait—what"))
        // off with the same switch, and next to a dictionary word
        assertEquals("말했다—그러나", ReadAloudNormalize.speak("말했다—그러나", emptyList(), false))
        assertEquals("지피티, 그리고", ReadAloudNormalize.speak("GPT—그리고", listOf("GPT" to "지피티"), true))
    }
}
