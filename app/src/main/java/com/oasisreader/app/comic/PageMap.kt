package com.oasisreader.app.comic

import com.oasisreader.app.data.ComicPage

/** 두 쪽 스캔 나누기 (108): an image this wide for its height (w/h ≥ 1.2) is two book pages. */
internal fun isTwoPageScan(w: Int, h: Int): Boolean = w > 1 && w * 10L >= h * 12L

/**
 * A page as shown on screen: the source image [source] whole ([parts] = 1), or one half of it.
 * [left] and [w]: the columns of the source that are shown (image pixels, PDF points).
 * [part]: which of the [parts] it is, in reading order.
 */
internal data class ScreenPage(
    val source: Int, val path: String, val left: Int, val w: Int, val h: Int, val part: Int = 0, val parts: Int = 1,
)

/**
 * Screen page numbers ↔ the book's own image numbers — the one place they are converted (108).
 * What is shown (turns, slider, page numbers) counts screen pages; everything that is saved
 * (progress, place, bookmarks, sync) keeps the image numbers it always had, with the half as
 * the "fraction inside the page" (0 or 0.5). Without [split] the two numberings are the same.
 * [rtl]: the right half is read first.
 */
internal class PageMap(pages: List<ComicPage>, split: Boolean, rtl: Boolean) {
    val screen: List<ScreenPage> = pages.flatMapIndexed { i, p ->
        if (!split || !isTwoPageScan(p.w, p.h)) listOf(ScreenPage(i, p.path, 0, p.w, p.h))
        else {
            val mid = p.w / 2
            val halves = listOf(0 to mid, mid to p.w - mid)
            (if (rtl) halves.reversed() else halves).mapIndexed { k, (left, w) -> ScreenPage(i, p.path, left, w, p.h, k, 2) }
        }
    }
    private val first = IntArray(pages.size).also { arr ->
        for (v in screen.indices.reversed()) arr[screen[v].source] = v
    }

    /** Screen pages in all. */
    val count: Int get() = screen.size

    private fun at(view: Int) = screen[view.coerceIn(0, screen.lastIndex)]

    /** The image screen page [view] is on. */
    fun sourceOf(view: Int): Int = if (screen.isEmpty()) 0 else at(view).source

    /** The place inside its image at which screen page [view] starts: 0, or 0.5 for a second half. */
    fun fractionOf(view: Int): Float = if (screen.isEmpty()) 0f else at(view).let { it.part.toFloat() / it.parts }

    /** The screen page to open for a saved place: image [source], [fraction] of the way through it. */
    fun viewAt(source: Int, fraction: Float = 0f): Int {
        if (screen.isEmpty()) return 0
        val v = first[source.coerceIn(0, first.lastIndex)]
        val parts = screen[v].parts
        return v + (fraction * parts).toInt().coerceIn(0, parts - 1)
    }

    /**
     * The last image read to its end when screen page [view] is the last one seen: the image
     * itself, or the one before while only its first half has been shown (never below 0).
     */
    fun readThrough(view: Int): Int {
        if (screen.isEmpty()) return 0
        val p = at(view)
        return if (p.part == p.parts - 1) p.source else (p.source - 1).coerceAtLeast(0)
    }
}
