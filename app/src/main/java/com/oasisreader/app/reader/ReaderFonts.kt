package com.oasisreader.app.reader

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.FontManager
import com.oasisreader.app.data.FontOption
import com.oasisreader.app.epub.EpubPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** The font in use and the one being fetched. One instance, remembered by the reader. */
internal class FontState(prefs: ReaderPrefs) {
    var fontId by mutableStateOf(prefs.fontId)
    var fontBusy by mutableStateOf<String?>(null)
    var fontProgress by mutableFloatStateOf(0f)

    fun apply(f: FontOption, prefs: ReaderPrefs, href: String?, webView: WebView?) {
        fontId = f.id; prefs.fontId = f.id
        val stack = f.cssStack().replace("\\", "").replace("\"", "")
        webView?.evaluateJavascript("Reader.setFontFamily(" + JSONObject.quote(stack) + "," + JSONObject.quote(href.orEmpty()) + ")", null)
    }

    /** A font file chosen from the phone: copy it in and use it. */
    fun importPicked(
        uri: Uri, scope: CoroutineScope, app: Context, context: Context, fonts: FontManager, apply: (FontOption) -> Unit,
    ) {
        scope.launch {
            try {
                val f = fonts.importFont(app, uri)
                apply(f)
                Toast.makeText(context, "「${f.label}」 글꼴을 추가했습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                com.oasisreader.app.ui.Notice.show(context, "글꼴 추가 실패: ${e.message}")
            }
        }
    }

    /** Use [f], fetching it first when it is not on the phone yet. */
    fun pick(f: FontOption, scope: CoroutineScope, context: Context, fonts: FontManager, apply: (FontOption) -> Unit) {
        if (fontBusy != null) return
        if (fonts.isReady(f)) { apply(f); return }
        fontBusy = f.id; fontProgress = 0f
        scope.launch {
            try {
                fonts.download(f) { d, t -> fontProgress = d.toFloat() / t }
                apply(f)
            } catch (e: Exception) {
                com.oasisreader.app.ui.Notice.show(context, "글꼴 받기 실패: ${e.message}")
            } finally {
                fontBusy = null
            }
        }
    }
}

/** A link inside the book: another chapter (through [onChapter], after the caller notes where it came from) or the web. */
internal fun openBookLink(
    url: String, bookId: String, pkg: EpubPackage?, context: Context, onChapter: (index: Int, fragment: String?) -> Unit,
) {
    val u = Uri.parse(url)
    if (u.host == ASSET_HOST) {
        val prefix = "/books/$bookId/content/"
        val path = u.path ?: return
        if (!path.startsWith(prefix)) return
        val rel = path.removePrefix(prefix)
        val idx = pkg?.spineIndexOf(rel) ?: -1
        if (idx >= 0) onChapter(idx, u.fragment)
        return
    }
    if (u.scheme == "http" || u.scheme == "https" || u.scheme == "mailto") {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) }
    }
}

/** The picture file a page's image points at, only when it lies inside the book store. */
internal fun bookImageFile(src: String, store: BookStore): File? {
    val u = Uri.parse(src)
    if (u.host != ASSET_HOST) return null
    val path = u.path ?: return null
    if (!path.startsWith("/books/")) return null
    val f = File(store.root, path.removePrefix("/books/"))
    return f.takeIf { it.isFile && it.canonicalPath.startsWith(store.root.canonicalPath + File.separator) }
}
