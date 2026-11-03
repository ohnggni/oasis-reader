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
}
