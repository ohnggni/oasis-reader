package com.oasisreader.app.reader

import com.oasisreader.app.epub.EpubPackage

/** The notification's ✕ ends reading aloud in the reader that started it. */
internal var aloudClose: (() -> Unit)? = null

/** 🔊 소리 내 읽기로 열기 (82): the 서재 asks for the book to start reading aloud once it opens. */
object ReadAloudStart {
    var pending: String? = null
    fun take(id: String): Boolean = (pending == id).also { if (it) pending = null }
}

/** 장 넘김 (83): what is waiting to be given when the next chapter starts. */
internal class ChapterTurn { var title: String? = null; var cue = false }

/** The title to read going from chapter [from] to [next]: only when the 목차 title changes (one chapter split over files reads it once). */
internal fun turnTitle(p: EpubPackage, from: Int, next: Int): String? =
    chapterTitleFor(p, next).trim().takeIf { it.isNotEmpty() && it != chapterTitleFor(p, from).trim() }

/** Title of the toc entry that covers spine item [index] (the last one at or before it). */
internal fun chapterTitleFor(p: EpubPackage, index: Int): String =
    com.oasisreader.app.data.HighlightExport.chapterTitle(p, index)
