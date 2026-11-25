package com.oasisreader.app.reader

import androidx.compose.foundation.layout.size
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.FontManager
import com.oasisreader.app.data.FontOption
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.epub.EpubPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the page is laid out from, read when the chapter has been loaded (the settings may change meanwhile). */
internal class PageInputs(
    val paged: Boolean, val fontPx: Int, val lineHeight: Int,
    val padTop: Int, val padBottom: Int, val padLeft: Int, val padRight: Int,
    val turn: com.oasisreader.app.ui.TurnStyle, val spread: Boolean, val weight: Int,
    val headShow: Boolean, val liftHeads: Boolean, val publisherStyle: Boolean,
    val theme: ReaderTheme, val bookTitle: String,
)

/** The page for chapter [t]: its file, the highlights in it and the reader's settings, as one html string. */
internal suspend fun buildChapterPage(
    store: BookStore, db: AppDatabase, fonts: FontManager, prefs: ReaderPrefs, bookId: String,
    p: EpubPackage, t: Target, fontHref: (FontOption) -> String?, inputs: () -> PageInputs,
): LoadState.Ready {
    val item = p.spine[t.index]
    val doc = withContext(Dispatchers.IO) { ChapterLoader.load(store, bookId, item.path) }
    val hls = db.highlights().forChapter(bookId, t.index).map {
        PageHighlight(it.id, it.text, it.color, it.anchor)
    }
    val font = prefs.font.takeIf { fonts.isReady(it) } ?: FontManager.byId(null)
    val i = inputs()
    val html = ReaderHtml.build(
        doc = doc,
        config = ReaderConfig(
            paged = i.paged,
            fontPx = i.fontPx,
            lineHeight = i.lineHeight / 10f,
            start = t.start,
            anchor = t.anchor,
            padTop = i.padTop,
            padBottom = i.padBottom,
            padLeft = i.padLeft,
            padRight = i.padRight,
            turn = i.turn.id,
            fontFamily = font.cssStack(),
            highlights = hls,
            focus = t.focus,
            find = t.find,
            findNth = t.findNth,
            animate = i.turn != com.oasisreader.app.ui.TurnStyle.NONE,
            spread = i.spread,
            weight = i.weight,
            colors = ReaderHtml.COLORS,
            docUrl = doc.url,
            head = chapterTitleFor(p, t.index).ifBlank { i.bookTitle },
            headShow = i.headShow,
            hasPrev = t.index > 0,
            hasNext = t.index < p.spine.size - 1,
            liftHeads = i.liftHeads,
        ),
        fontCssHref = fontHref(font),
        publisherStyle = i.publisherStyle,
        theme = i.theme,
    )
    return LoadState.Ready(html, doc.url, t.token)
}

/** Where to open: chapter + fraction, or an element id inside it. [token] forces a reload. */
internal data class Target(
    val index: Int,
    val start: Float,
    val anchor: String? = null,
    val focus: String? = null,
    val find: String? = null,
    val findNth: Int = 0,
    val token: Long = System.nanoTime(),
)

internal sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val html: String, val baseUrl: String, val token: Long) : LoadState
    data class Failed(val message: String) : LoadState
}
