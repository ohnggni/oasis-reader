package com.oasisreader.app.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A highlight as the page script sees it. */
@Serializable
data class PageHighlight(val id: String, val text: String, val color: String, val anchor: String? = null)

@Serializable
data class ReaderConfig(
    val paged: Boolean,
    val fontPx: Int,
    val lineHeight: Float = 1.8f,
    val start: Float,                // fraction of the chapter to open at (1 = last page)
    val anchor: String? = null,      // element id to open at (toc / links), wins over start
    val padTop: Int,                 // CSS px (= dp)
    val padBottom: Int,
    val padLeft: Int = 24,           // page side margins (CSS px); spread pages get +4
    val padRight: Int = 24,
    val turn: String = "slide",      // slide | curl | cover | none (curl/cover are drawn by the app)
    val fontFamily: String,          // CSS font-family stack
    val highlights: List<PageHighlight>,
    val focus: String? = null,       // highlight id to open at
    val find: String? = null,        // search: mark and open at the findNth-th match of this text
    val findNth: Int = 0,
    val animate: Boolean = true,     // slide when turning pages
    val spread: Boolean = false,     // two facing pages (fold open, hinge vertical)
    val weight: Int = 0,             // 0 normal / 1 slightly bolder / 2 bolder
    val colors: List<String>,
    val docUrl: String,              // this chapter's URL (same-chapter links)
    val head: String = "",           // running head: the chapter title at the top of every page
    val headShow: Boolean = true,    // false: no running head in the page (off, or drawn by the app on the status line)
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val liftHeads: Boolean = true,   // 94: short lines smaller than the text at the text's size
)

/**
 * Builds the reader page for one EPUB chapter (spine item).
 *
 * Book mode uses CSS multi-column layout: the chapter flows into page-wide
 * columns (1 per page folded, 2 side by side unfolded) and we move by scrolling
 * the container exactly one viewport width. Turning past the last page (or before
 * the first) asks the app for the next/previous chapter (bridge.edge).
 *
 * Sizes that depend on the page box (image max height) are computed in JS:
 * Android WebView may load the page before it is laid out, and then 100vh
 * resolves to 0.
 *
 * Style layers: reader base → the book's own CSS (optional) → reader overrides
 * (!important: font family, colours, page margins).
 *
 * The CSS and script are files in resources/reader (base.css, overrides.css, page.js).
 */
object ReaderHtml {

    val COLORS = listOf("#fff176", "#b9f6ca", "#ff8a80", "#82b1ff", "#e1bee7")

    private val json = Json { encodeDefaults = true }

    private fun hex(c: Long) = "#%06x".format(c and 0xFFFFFF)

    /** The running head's letters: the muted colour, a little see-through (the line itself is solid, 105). */
    private fun headColor(c: Long) = "rgba(${c shr 16 and 0xFF},${c shr 8 and 0xFF},${c and 0xFF},.8)"

    /** CSS variables for a page theme (inline on <html>, so they win over the defaults). */
    fun themeVars(t: ReaderTheme) =
        "--bg:${hex(t.bg)};--fg:${hex(t.fg)};--muted:${hex(t.muted)};--head:${headColor(t.muted)};--accent:${hex(t.accent)};--rule:${hex(t.rule)}"

    /** Same as a JS object literal for Reader.setTheme. */
    fun themeJson(t: ReaderTheme) =
        "{'--bg':'${hex(t.bg)}','--fg':'${hex(t.fg)}','--muted':'${hex(t.muted)}','--head':'${headColor(t.muted)}','--accent':'${hex(t.accent)}','--rule':'${hex(t.rule)}'}"

    private fun esc(s: String?): String = (s ?: "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // 3.0: the page's CSS and script live in app/src/main/resources/reader/ (plain files: node --check
    // works on page.js, and the dollar sign is fine there). The page script's two inserts are
    // placeholders: __OG_TTS_DECL__ (TtsScript) first, then __OG_CFG__ (chapter config, may hold
    // book text — inserted last so nothing in it is ever replaced again).
    private fun res(name: String): String =
        ReaderHtml::class.java.getResourceAsStream("/reader/" + name)!!.use { it.readBytes().toString(Charsets.UTF_8) }
    private val BASE_CSS by lazy { res("base.css") }
    private val OVERRIDES_CSS by lazy { res("overrides.css") }
    private val PAGE_JS by lazy { res("page.js") }
    private fun pageJs(cfg: String, ttsDecl: String) =
        PAGE_JS.replace("__OG_TTS_DECL__", ttsDecl).replace("__OG_CFG__", cfg)

    fun build(
        doc: ChapterDoc,
        config: ReaderConfig,
        fontCssHref: String?,
        publisherStyle: Boolean,
        theme: ReaderTheme,            // page colours (not the WebView's own idea of light/dark)
    ): String {
        val dark = theme.dark
        // JSON goes inside <script>: neutralise "</" so text can never close the tag
        val cfg = json.encodeToString(ReaderConfig.serializer(), config).replace("</", "<\\/")
        val fontLink = fontCssHref?.let { "<link rel=\"stylesheet\" href=\"${esc(it)}\">" } ?: ""
        val bookCss = if (!publisherStyle) "" else
            doc.stylesheets.joinToString("\n") { "<link rel=\"stylesheet\" href=\"${esc(it)}\">" } +
                (if (doc.inlineCss.isNotBlank()) "\n<style>${doc.inlineCss}</style>" else "")
        val ttsDecl = TtsScript.declarations()   // TTS_END ... TTS_LEAD, made from TtsTuning
        return """<!doctype html>
<html lang="ko" class="${if (config.paged) "paged" else ""} ${if (publisherStyle) "pub" else "plain"} ${if (dark) "dark" else ""}" style="${themeVars(theme)}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
$fontLink
<style>
${BASE_CSS}</style>
$bookCss
<style>
${OVERRIDES_CSS}</style>
</head>
<body>
<div id="book">
  <div id="content" class="${esc(doc.bodyClass)}">${doc.bodyHtml}</div>
  <div id="chapnav"><button id="cprev">◀ 이전 장</button><button id="cnext">다음 장 ▶</button></div>
</div>
<div id="hlbar"></div>
<div id="hlstart"></div>
<div id="hlstrip"><div class="sm"></div><div class="sx"><div class="st"></div><div class="ss"></div></div><button id="hlcancel">취소</button></div>
<div id="notepop"><div class="nbody"></div><div class="nbar"><button id="ngo">본문에서 보기</button><button id="nclose">닫기</button></div></div>
<div id="rhead"></div>
<script>
${pageJs(cfg, ttsDecl)}</script>
</body>
</html>
"""
    }
}
