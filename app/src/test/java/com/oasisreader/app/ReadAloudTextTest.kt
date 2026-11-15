package com.oasisreader.app

import com.oasisreader.app.reader.ReadAloudText
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudTextTest {
    @Test fun sentencesSkipNotesAndKeepBlocks() {
        val html = """<html><body><h1>제목</h1><p>첫 문장입니다. 3.5배 숫자도 있어요!<sup><a href="n.html#n2">2</a></sup> 끝?</p>
            <p>다음 문단은 "따옴표"로 끝납니다." 그리고<a epub:type="noteref" href="#n3">3</a> 이어집니다</p></body></html>"""
        assertEquals(
            listOf("제목", "첫 문장입니다.", "3.5배 숫자도 있어요!", "끝?", "다음 문단은 \"따옴표\"로 끝납니다.\"", "그리고 이어집니다"),
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

    @Test fun longSentenceIsCutAtComma() {
        val long = "그는 아침 일찍 일어나 창문을 열고 밖을 내다보았는데, 거리에는 이미 사람들이 바삐 오가고 있었고 그는 그 모습을 한참 동안 바라보다가, " +
            "문득 오늘이 무슨 날인지 떠올리고는 서둘러 옷을 챙겨 입은 뒤 현관을 나섰고 그대로 역까지 뛰어갔다."
        assertEquals(
            listOf(
                "그는 아침 일찍 일어나 창문을 열고 밖을 내다보았는데, 거리에는 이미 사람들이 바삐 오가고 있었고 그는 그 모습을 한참 동안 바라보다가,",
                "문득 오늘이 무슨 날인지 떠올리고는 서둘러 옷을 챙겨 입은 뒤 현관을 나섰고 그대로 역까지 뛰어갔다.",
            ),
            ReadAloudText.sentences("<p>$long</p>"),
        )
    }
}
