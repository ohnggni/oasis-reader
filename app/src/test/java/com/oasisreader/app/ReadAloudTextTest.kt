package com.oasisreader.app

import com.oasisreader.app.reader.ReadAloudText
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudTextTest {
    @Test fun sentencesSkipNotesAndKeepBlocks() {
        val html = """<html><body><h1>제목</h1><p>첫 문장입니다. 3.5배 숫자도 있어요!<sup><a href="n.html#n2">2</a></sup> 끝?</p>
            <p>다음 문단은 "따옴표"로 끝납니다." 그리고<a epub:type="noteref" href="#n3">3</a> 이어집니다</p></body></html>"""
        assertEquals(
            listOf("제목\u2063", "첫 문장입니다.", "3.5배 숫자도 있어요!", "끝?", "다음 문단은 \"따옴표\"로 끝납니다.\"", "그리고 이어집니다"),
            ReadAloudText.sentences(html),
        )
    }

    @Test fun textbookEpubBreaksAndNoSpaceSentences() {
        val html = "<body><p>“박진수!”<br/><br/>교실 앞문을 열고 들어온 담임 선생이 바로 진수를 호명했다.<br>담임은 손짓했다. 쭈뼛거리며 다가간 진수.</p>" +
            "<p>쳐다봤다.그러나 진수는 말없이 챙길 뿐이었다.“무슨 일이냐고?” 자식아.</p>" +
            "<p>첫 문장이다.\u200B둘째 문장이다.</p><p>3.5배 늘었고 naver.com에서 봤다.</p></body>"
        assertEquals(
            listOf(
                "“박진수!”", "교실 앞문을 열고 들어온 담임 선생이 바로 진수를 호명했다.", "담임은 손짓했다.", "쭈뼛거리며 다가간 진수.",
                "쳐다봤다.", "그러나 진수는 말없이 챙길 뿐이었다.", "“무슨 일이냐고?”", "자식아.",
                "첫 문장이다.", "둘째 문장이다.", "3.5배 늘었고 naver.com에서 봤다.",
            ),
            ReadAloudText.sentences(html),
        )
    }

    @Test fun bookLongSentenceStaysWhole() {
        // 2.9: a long book sentence (~140 letters) is read in one go, not cut mid-phrase
        val long = "그는 아침 일찍 일어나 창문을 열고 밖을 내다보았는데, 거리에는 이미 사람들이 바삐 오가고 있었고 그는 그 모습을 한참 동안 바라보다가, " +
            "문득 오늘이 무슨 날인지 떠올리고는 서둘러 옷을 챙겨 입은 뒤 현관을 나섰고 그대로 역까지 뛰어갔다."
        assertEquals(listOf(long), ReadAloudText.sentences("<p>$long</p>"))
    }

    @Test fun veryLongSentenceIsCutAtComma() {
        val a = "가".repeat(200) + ","
        val b = "나".repeat(150) + "."
        assertEquals(listOf(a, b), ReadAloudText.sentences("<p>$a $b</p>"))
    }

    @Test fun noCommaUnderHardStaysWhole() {
        val t = (1..60).joinToString(" ") { "단어$it" }   // ~290 letters, no comma, no end
        assertEquals(listOf(t), ReadAloudText.sentences("<p>$t</p>"))
    }

    @Test fun noCommaOverHardIsCutAtSpace() {
        val t = (1..90).joinToString(" ") { "단어$it" }   // ~440 letters, no comma, no end
        val got = ReadAloudText.sentences("<p>$t</p>")
        assertEquals(true, got.size >= 2)
        assertEquals(t, got.joinToString(" "))
    }

    @Test fun headingLinesJoinAndNumberLineStaysApart() {
        // 85: <br> inside a heading is one sentence; a number line alone stays apart; both end with HEAD
        val h = ReadAloudText.HEAD
        assertEquals(
            listOf("자기 신뢰의 행동을 쌓아간다$h", "본문입니다."),
            ReadAloudText.sentences("<h2>자기 신뢰의<br/>행동을 쌓아간다</h2><p>본문입니다.</p>"),
        )
        assertEquals(
            listOf("제3장$h", "멘토와의 만남$h", "본문."),
            ReadAloudText.sentences("<h1><span>제3장</span><br>멘토와의<br/>만남</h1><p>본문.</p>"),
        )
        assertEquals(listOf("PART 1$h", "시작$h"), ReadAloudText.sentences("<h1>PART 1<br>시작</h1>"))
        // a <br> outside a heading still ends the line (텍본)
        assertEquals(listOf("첫 줄", "둘째 줄"), ReadAloudText.sentences("<p>첫 줄<br>둘째 줄</p>"))
    }

    @Test fun labels() {
        listOf("제3장", "제 12 장", "3장", "2부", "Chapter 4", "PART IV", "⑥", "Ⅲ", "1.", "프롤로그").forEach {
            assertEquals(it, true, ReadAloudText.isLabel(it))
        }
        listOf("3장의 끝", "⑥ 감정의 자각과 행동", "자기 신뢰의").forEach { assertEquals(it, false, ReadAloudText.isLabel(it)) }
    }
}
