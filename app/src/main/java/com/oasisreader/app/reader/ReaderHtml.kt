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
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
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
 * NOTE: Kotlin raw string — the embedded CSS/JS must not contain the dollar sign.
 */
object ReaderHtml {

    val COLORS = listOf("#fff176", "#b9f6ca", "#ff8a80", "#82b1ff", "#e1bee7")

    private val json = Json { encodeDefaults = true }

    private fun hex(c: Long) = "#%06x".format(c and 0xFFFFFF)

    /** CSS variables for a page theme (inline on <html>, so they win over the defaults). */
    fun themeVars(t: ReaderTheme) =
        "--bg:${hex(t.bg)};--fg:${hex(t.fg)};--muted:${hex(t.muted)};--accent:${hex(t.accent)};--rule:${hex(t.rule)}"

    /** Same as a JS object literal for Reader.setTheme. */
    fun themeJson(t: ReaderTheme) =
        "{'--bg':'${hex(t.bg)}','--fg':'${hex(t.fg)}','--muted':'${hex(t.muted)}','--accent':'${hex(t.accent)}','--rule':'${hex(t.rule)}'}"

    private fun esc(s: String?): String = (s ?: "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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
        return """<!doctype html>
<html lang="ko" class="${if (config.paged) "paged" else ""} ${if (publisherStyle) "pub" else "plain"} ${if (dark) "dark" else ""}" style="${themeVars(theme)}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
$fontLink
<style>
:root {
  --fs: 18px; --lh: 1.8; --font: serif; --pad-top: 72px; --pad-bottom: 40px;
  --bg: #f6efe1; --fg: #2b2620; --muted: #7a6f60; --accent: #2f5d50; --rule: #e3d8c3;
  color-scheme: light;
}
html.dark { --bg: #1b2420; --fg: #d6ddd6; --muted: #8f9c94; --accent: #9cc8b4; --rule: #2e3a34; color-scheme: dark; }
body {
  word-break: normal;          /* Hangul may break between syllables */
  overflow-wrap: anywhere;     /* long URLs/latin never overflow */
  line-break: strict;          /* no closing punctuation at line start */
  -webkit-text-size-adjust: 100%;
  -webkit-tap-highlight-color: transparent;
}
/* defaults with zero id-specificity (:where), so the book's own CSS can override them */
#content { text-align: justify; }
:where(#content) :where(h1, h2, h3, h4) { line-height: 1.4; break-after: avoid; }
:where(#content) h1 { font-size: 1.5em; margin: 0.6em 0 0.9em; }
:where(#content) h2 { font-size: 1.3em; margin: 0.8em 0 0.7em; }
:where(#content) h3 { font-size: 1.15em; margin: 0.8em 0 0.5em; }
:where(#content) h4 { font-size: 1.05em; }
:where(html.plain #content) p { margin: 0 0 0.85em; }
:where(html.plain #content) :where(h1, h2, h3, h4) { text-align: left; }
:where(#content) :where(img, svg) { display: block; max-width: 100%; height: auto; margin: 0.6em auto; }
#content img, #content svg { -webkit-touch-callout: none; -webkit-user-select: none; user-select: none; }
:where(#content) table { max-width: 100%; border-collapse: collapse; }
:where(#content) pre { white-space: pre-wrap; }
</style>
$bookCss
<style>
/* reader overrides: always win over the book's CSS */
html, body { margin: 0 !important; padding: 0 !important; background: var(--bg) !important; }
body {
  color: var(--fg) !important;
  font-family: var(--font) !important;
  font-size: var(--fs) !important;
  line-height: var(--lh) !important;
  width: auto !important; max-width: none !important; columns: auto !important;
}
#content, #content * {
  font-family: inherit !important;
  color: inherit !important;
  background-color: transparent !important;
}
#content * { max-width: 100%; }
#content { background-image: none !important; }
/* dark pages: drop the book's background pictures too, they assume dark text */
html.dark #content * { background-image: none !important; }
#content p, #content li, #content dd, #content blockquote { line-height: inherit !important; }
#content a { color: var(--accent) !important; text-decoration: none !important; }
#content iframe, #content video, #content embed, #content object {
  display: block; width: 100% !important; height: auto !important; aspect-ratio: 16 / 9;
  border: 0; margin: 1em auto; background: #000 !important;
}
#content mark.hl { color: inherit !important; border-radius: 2px; padding: 0 1px; }
#content mark.hl.flash, #content mark.find.flash { animation: hlflash 1.1s ease-in-out 2; }
#content mark.find { color: inherit !important; border-radius: 2px; padding: 0 1px; }
@keyframes hlflash { 0%, 100% { box-shadow: 0 0 0 0 transparent; } 50% { box-shadow: 0 0 0 4px var(--accent); } }
#content .brk { word-break: break-all !important; }
/* text weight: thicken glyph outlines, so single-weight fonts (e.g. Gowun Dodum) work too */
html[data-w="1"] #content { -webkit-text-stroke: 0.02em currentColor; }
html[data-w="2"] #content { -webkit-text-stroke: 0.034em currentColor; }

/* scroll mode */
html:not(.paged) #book {
  max-width: 720px; margin: 0 auto; box-sizing: border-box;
  padding: var(--pad-top) var(--pad-r, 22px) calc(var(--pad-bottom) + 40px) var(--pad-l, 22px);
}
#chapnav { display: flex; justify-content: space-between; gap: 12px; margin: 2.5em 0 1em; font: 0.85em/1.4 sans-serif; }
#chapnav button {
  flex: 1; padding: 12px; border-radius: 10px; border: 1px solid var(--rule);
  background: transparent; color: var(--muted); font: inherit;
}
html.paged #chapnav { display: none; }

/* book mode */
html.paged, html.paged body { height: 100%; overflow: hidden; }
html.paged #book {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  box-sizing: border-box;
  column-fill: auto;
  overflow: hidden;
}
html.paged #content img, html.paged #content svg {
  max-height: var(--img-max) !important; width: auto; object-fit: contain; break-inside: avoid;
}
html.paged #content iframe, html.paged #content video, html.paged #content embed, html.paged #content object {
  max-height: var(--img-max); break-inside: avoid;
}

/* running head: part of the page (it turns with it); the app's bar covers it when shown */
#rhead {
  position: fixed; z-index: 5; top: 0; left: 0; right: 0; height: 30px; line-height: 30px;
  padding: 0 48px; box-sizing: border-box; background: var(--bg); color: var(--muted);
  font: 11px/30px sans-serif; text-align: center; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  opacity: .8; pointer-events: none;
}

/* highlight palette shown over a text selection */
#hlbar {
  position: fixed; z-index: 10; display: none; gap: 10px; padding: 8px 12px;
  border-radius: 22px; background: var(--fg); box-shadow: 0 4px 16px rgba(0,0,0,.25);
}
#hlbar button {
  width: 28px; height: 28px; border-radius: 50%; border: 2px solid var(--bg); padding: 0;
}
/* footnote popup */
#notepop {
  position: fixed; left: 12px; right: 12px; bottom: 72px; z-index: 12; display: none;
  max-height: 60%; min-height: 0; overflow: auto; padding: 14px 18px 8px;
  background: var(--bg); color: var(--fg); border: 1px solid var(--rule);
  box-shadow: 0 6px 24px rgba(0,0,0,.28); border-radius: 14px;
  font-size: calc(var(--fs) * 0.88); line-height: 1.6; text-align: left;
}
/* the book's own CSS often hides or shrinks note elements: undo that inside the popup */
#notepop .nbody, #notepop .nbody * {
  display: revert !important; visibility: visible !important; position: static !important;
  font-size: inherit !important; color: inherit !important; opacity: 1 !important;
  height: auto !important; max-height: none !important; overflow: visible !important;
  float: none !important; margin-left: 0 !important; text-indent: 0 !important;
}
#notepop .nbody p { margin: 0 0 .5em !important; }
#notepop .nbody img { max-width: 100%; height: auto; }
#notepop .nbar { display: flex; justify-content: flex-end; gap: 6px; margin-top: 6px; }
#notepop .nbar button { background: none; border: 0; color: var(--accent); font: 600 14px/32px sans-serif; padding: 0 10px; }
#hlbar button.look {
  width: auto; border-radius: 14px; padding: 0 10px; margin-left: 4px;
  background: var(--bg); color: var(--fg); font: 600 13px/24px sans-serif; border: 0;
}
</style>
</head>
<body>
<div id="book">
  <div id="content" class="${esc(doc.bodyClass)}">${doc.bodyHtml}</div>
  <div id="chapnav"><button id="cprev">◀ 이전 장</button><button id="cnext">다음 장 ▶</button></div>
</div>
<div id="hlbar"></div>
<div id="notepop"><div class="nbody"></div><div class="nbar"><button id="ngo">본문에서 보기</button><button id="nclose">닫기</button></div></div>
<div id="rhead"></div>
<script>
(function () {
  var CFG = $cfg;
  var html = document.documentElement;
  var book = document.getElementById('book');
  var content = document.getElementById('content');
  var bar = document.getElementById('hlbar');
  var paged = CFG.paged;
  var animate = CFG.animate;
  var turn = CFG.animate === false ? 'none' : (CFG.turn || 'slide');
  var padL = CFG.padLeft, padR = CFG.padRight;
  var pendingTurn = null, turnTimer = 0, turnY = 0.5;   /* turnY: where the turn was started (0 top .. 1 bottom) */
  var page = 0, total = 1, cols = 1;
  var padTop = CFG.padTop, padBottom = CFG.padBottom;
  var noop = function () {};
  var bridge = window.Oasis || { page: noop, progress: noop, tap: noop, addHighlight: noop, tapHighlight: noop, openImage: noop, edge: noop, link: noop, turn: noop, lookup: noop };
  var dark = html.classList.contains('dark');

  html.style.setProperty('--fs', CFG.fontPx + 'px');
  html.style.setProperty('--lh', String(CFG.lineHeight));
  html.style.setProperty('--font', CFG.fontFamily);
  html.setAttribute('data-w', String(CFG.weight || 0));

  document.getElementById('rhead').textContent = CFG.head || '';
  var cprev = document.getElementById('cprev'), cnext = document.getElementById('cnext');
  if (!CFG.hasPrev) cprev.style.visibility = 'hidden';
  if (!CFG.hasNext) cnext.textContent = '마지막 장입니다';
  cprev.addEventListener('click', function (e) { e.preventDefault(); if (CFG.hasPrev) bridge.edge(-1, 0.5); });
  cnext.addEventListener('click', function (e) { e.preventDefault(); if (CFG.hasNext) bridge.edge(1, 0.5); });

  /* images: never lazy (columns off-screen would never load) */
  var imgs = document.images;
  for (var i = 0; i < imgs.length; i++) { imgs[i].loading = 'eager'; imgs[i].decoding = 'async'; }

  var spread = CFG.spread;
  /* exact (fractional) viewport width: Android WebView widths like 373.33px would
     otherwise make every page turn drift a little to the right */
  function W() { return html.getBoundingClientRect().width || window.innerWidth; }

  function fraction() {
    if (paged) return total > 1 ? page / (total - 1) : 1;
    var max = document.documentElement.scrollHeight - window.innerHeight;
    return max > 0 ? Math.min(1, window.scrollY / max) : 1;
  }

  function report() {
    var f = fraction();
    if (paged) bridge.page(page + 1, total);
    bridge.progress(f, f >= 0.999);
  }

  function applyInsets() {
    html.style.setProperty('--pad-top', padTop + 'px');
    html.style.setProperty('--pad-bottom', padBottom + 'px');
    html.style.setProperty('--pad-l', padL + 'px');
    html.style.setProperty('--pad-r', padR + 'px');
  }

  /* element to open at: toc entry / link target */
  var anchorId = CFG.anchor || null;
  function anchorEl() {
    if (!anchorId) return null;
    var el = document.getElementById(anchorId);
    if (!el) { var named = document.getElementsByName(anchorId); el = named.length ? named[0] : null; }
    return el;
  }
  function goAnchor() {
    var el = anchorEl();
    if (!el) { anchorId = null; return false; }
    var r = el.getBoundingClientRect();
    if (paged) {
      go(Math.floor((r.left + book.scrollLeft + 1) / W()));
    } else {
      window.scrollTo(0, Math.max(0, window.scrollY + r.top - padTop - 12));
      report();
    }
    return true;
  }

  function layout(keepFraction) {
    var f = (keepFraction === undefined) ? fraction() : keepFraction;
    applyInsets();
    if (paged) {
      cols = spread ? 2 : 1;
      /* gutter must be left + right margin so one page step is exactly one screen */
      var L = padL + (cols === 2 ? 4 : 0), R = padR + (cols === 2 ? 4 : 0);
      var gap = L + R;
      book.style.padding = padTop + 'px ' + R + 'px ' + padBottom + 'px ' + L + 'px';
      book.style.columnGap = gap + 'px';
      book.style.columnWidth = ((W() - L - R - (cols - 1) * gap) / cols) + 'px';
      var h = book.clientHeight - padTop - padBottom - 24;
      html.style.setProperty('--img-max', h > 80 ? h + 'px' : 'none');
      book.scrollLeft = 0;
      total = Math.max(1, Math.round((book.scrollWidth - 2) / W() + 0.49));
      if (!focusMark() && !goAnchor()) go(Math.round(f * (total - 1)));
    } else {
      book.style.cssText = '';
      html.style.setProperty('--img-max', 'none');
      if (!focusMark() && !goAnchor()) {
        var max = document.documentElement.scrollHeight - window.innerHeight;
        window.scrollTo(0, Math.round(f * Math.max(0, max)));
        report();
      }
    }
  }

  /* jump to page p; animated only for user page turns */
  function go(p, animated) {
    p = Math.max(0, Math.min(total - 1, p));
    /* curl / cover: the app takes a picture of this page first, then calls commitTurn() */
    if (animated && (turn === 'curl' || turn === 'cover') && Math.abs(p - page) === 1 && window.Oasis && window.Oasis.turn) {
      if (pendingTurn !== null) return;
      pendingTurn = p;
      window.Oasis.turn(p > page ? 1 : -1, turnY);
      turnY = 0.5;
      turnTimer = setTimeout(commitTurn, 600);   /* never stuck if the app does not answer */
      return;
    }
    page = p;
    var x = page * W();
    if (animated && turn === 'slide' && Math.abs(book.scrollLeft - x) <= W() + 2) {
      slideTo(x);
    } else {
      stopSlide();
      book.scrollLeft = x;
    }
    report();
  }

  function commitTurn() {
    clearTimeout(turnTimer);
    if (pendingTurn === null) return;
    page = pendingTurn; pendingTurn = null;
    stopSlide();
    book.scrollLeft = page * W();
    report();
  }

  /* page-turn slide: own animation (WebView's smooth scroll is ~0.5s and not tunable) */
  var SLIDE_MS = 340, slideRaf = 0;
  function stopSlide() { if (slideRaf) { cancelAnimationFrame(slideRaf); slideRaf = 0; } }
  function slideTo(x) {
    stopSlide();
    var from = book.scrollLeft, dist = x - from, t0 = 0;
    if (Math.abs(dist) < 1) { book.scrollLeft = x; return; }
    function step(ts) {
      if (!t0) t0 = ts;
      var k = Math.min(1, (ts - t0) / SLIDE_MS);
      var e = 0.5 - Math.cos(Math.PI * k) / 2;   /* ease-in-out: even glide, not a snap */
      book.scrollLeft = from + dist * e;
      slideRaf = k < 1 ? requestAnimationFrame(step) : 0;
    }
    slideRaf = requestAnimationFrame(step);
  }

  /* opened from the highlights list: keep that highlight in view until the reader interacts */
  var focusId = CFG.focus || null, flashed = false;
  function focusMark() {
    if (!focusId) return false;
    var m = content.querySelector('mark[data-hl="' + focusId + '"]');
    if (!m) { focusId = null; return false; }
    var r = m.getBoundingClientRect();
    if (paged) {
      go(Math.floor((r.left + book.scrollLeft) / W()));
    } else {
      window.scrollTo(0, Math.max(0, window.scrollY + r.top - padTop - 48));
      report();
    }
    if (!flashed) {
      flashed = true;
      var all = content.querySelectorAll('mark[data-hl="' + focusId + '"]');
      for (var i = 0; i < all.length; i++) all[i].classList.add('flash');
    }
    return true;
  }

  /* ── highlights ─────────────────────────────────────────── */
  function hlColor(c) {
    var m = /^#([0-9a-f]{6})/i.exec(c || '');
    var hex = m ? m[1] : 'fff176';
    var r = parseInt(hex.substr(0, 2), 16), g = parseInt(hex.substr(2, 2), 16), b = parseInt(hex.substr(4, 2), 16);
    return 'rgba(' + r + ',' + g + ',' + b + ',' + (dark ? 0.42 : 0.85) + ')';
  }

  function textIndex() {
    var nodes = [], full = '';
    var w = document.createTreeWalker(content, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) { nodes.push({ node: n, start: full.length }); full += n.nodeValue; }
    return { nodes: nodes, full: full };
  }

  /* whitespace-collapsed copy of s with a map back to original offsets */
  function collapse(s) {
    var out = '', map = [], prevSpace = true;
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i), sp = /\s/.test(c);
      if (sp) { if (prevSpace) continue; out += ' '; map.push(i); prevSpace = true; }
      else { out += c; map.push(i); prevSpace = false; }
    }
    return { s: out, map: map };
  }

  function wrapRange(idx, start, end, id, color) {
    var nodes = idx.nodes;
    for (var k = nodes.length - 1; k >= 0; k--) {
      var t = nodes[k], ns = t.start, ne = t.start + t.node.nodeValue.length;
      if (ne <= start || ns >= end) continue;
      var a = Math.max(start, ns) - ns, b = Math.min(end, ne) - ns;
      var node = t.node;
      if (b < node.nodeValue.length) node.splitText(b);
      var mid = a > 0 ? node.splitText(a) : node;
      if (!mid.nodeValue.trim()) continue;
      var mark = document.createElement('mark');
      mark.className = 'hl';
      mark.setAttribute('data-hl', id);
      mark.style.setProperty('background-color', hlColor(color), 'important');
      mid.parentNode.insertBefore(mark, mid);
      mark.appendChild(mid);
    }
  }

  function applyHighlight(h) {
    if (!h || !h.text || h.text.trim().length < 2) return false;
    if (content.querySelector('mark[data-hl="' + h.id + '"]')) return true;
    var idx = textIndex();
    var F = collapse(idx.full);
    var T = collapse(h.text.trim()).s.trim();
    if (!T) return false;
    var tail = h.anchor ? collapse(h.anchor).s.trim().slice(-20) : '';
    var at = -1, from = 0, first = -1;
    while (true) {
      var p = F.s.indexOf(T, from);
      if (p < 0) break;
      if (first < 0) first = p;
      if (!tail) { at = p; break; }
      var upto = F.s.slice(0, p + T.length).trim();
      if (upto.slice(-tail.length) === tail) { at = p; break; }
      from = p + 1;
    }
    if (at < 0) at = first;
    if (at < 0) return false;
    var start = F.map[at], end = F.map[at + T.length - 1] + 1;
    wrapRange(idx, start, end, h.id, h.color);
    return true;
  }

  function removeHighlight(id) {
    var marks = content.querySelectorAll('mark[data-hl="' + id + '"]');
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i], parent = m.parentNode;
      while (m.firstChild) parent.insertBefore(m.firstChild, m);
      parent.removeChild(m);
      parent.normalize();
    }
  }

  /* palette over a selection */
  CFG.colors.forEach(function (c) {
    var btn = document.createElement('button');
    btn.style.background = c;
    btn.addEventListener('click', function (e) { e.preventDefault(); createFromSelection(c); });
    bar.appendChild(btn);
  });
  /* look the selection up in a dictionary / translator (the app shows the choices) */
  (function () {
    var btn = document.createElement('button');
    btn.className = 'look';
    btn.textContent = '찾아보기';
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      var s = currentSelection();
      if (!s) return;
      var t = s.text.trim();
      window.getSelection().removeAllRanges();
      bar.style.display = 'none';
      if (bridge.lookup) bridge.lookup(t);
    });
    bar.appendChild(btn);
  })();
  bar.style.display = 'none';

  function currentSelection() {
    var sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
    var r = sel.getRangeAt(0);
    if (!content.contains(r.commonAncestorContainer)) return null;
    var text = sel.toString();
    if (text.trim().length < 1) return null;
    return { range: r, text: text };
  }

  function showBar() {
    var s = currentSelection();
    if (!s) { bar.style.display = 'none'; return; }
    var rect = s.range.getBoundingClientRect();
    bar.style.display = 'flex';
    var bw = bar.offsetWidth, bh = bar.offsetHeight;
    var x = Math.max(8, Math.min(W() - bw - 8, rect.left + rect.width / 2 - bw / 2));
    var y = rect.bottom + 14;
    if (y + bh > window.innerHeight - padBottom) y = Math.max(padTop, rect.top - bh - 14);
    bar.style.left = x + 'px';
    bar.style.top = y + 'px';
  }

  function createFromSelection(color) {
    var s = currentSelection();
    if (!s) return;
    var pre = document.createRange();
    pre.setStart(content, 0);
    pre.setEnd(s.range.endContainer, s.range.endOffset);
    var anchor = pre.toString().slice(-50);
    var id = String(Date.now());
    if (s.text.trim().length < 2) return;
    var h = { id: id, text: s.text.trim(), color: color, anchor: anchor };
    window.getSelection().removeAllRanges();
    bar.style.display = 'none';
    if (applyHighlight(h)) bridge.addHighlight(h.id, h.text, h.color, h.anchor);
  }

  var selTimer = null;
  document.addEventListener('selectionchange', function () {
    clearTimeout(selTimer);
    selTimer = setTimeout(showBar, 250);
  });

  function atEnd() {
    if (paged) return page >= total - 1;
    return window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 4;
  }
  function atStart() {
    if (paged) return page <= 0;
    return window.scrollY <= 2;
  }

  window.Reader = {
    next: function () {
      if (atEnd()) { if (CFG.hasNext) bridge.edge(1, turnY); turnY = 0.5; return; }
      if (paged) go(page + 1, true); else window.scrollBy({ top: window.innerHeight * 0.9, behavior: 'smooth' });
    },
    prev: function () {
      if (atStart()) { if (CFG.hasPrev) bridge.edge(-1, turnY); turnY = 0.5; return; }
      if (paged) go(page - 1, true); else window.scrollBy({ top: -window.innerHeight * 0.9, behavior: 'smooth' });
    },
    setAnimate: function (on) { animate = !!on; turn = on ? 'slide' : 'none'; },
    setTurn: function (t) { turn = t; },
    jumpBy: function (k) { stopSlide(); page = Math.max(0, Math.min(total - 1, page + k)); book.scrollLeft = page * W(); report(); },
    setTheme: function (vars, d) {
      for (var k in vars) html.style.setProperty(k, vars[k]);
      html.classList.toggle('dark', !!d); dark = !!d;
    },
    commitTurn: function () { commitTurn(); },
    setWeight: function (w) { html.setAttribute('data-w', String(w || 0)); },
    setSpread: function (on) { if (spread === !!on) return; var f = fraction(); spread = !!on; layout(f); },
    setFontFamily: function (stack, href) {
      var f = fraction();
      if (href && !document.querySelector('link[data-font="' + href + '"]')) {
        var l = document.createElement('link');
        l.rel = 'stylesheet'; l.href = href; l.setAttribute('data-font', href);
        document.head.appendChild(l);
      }
      html.style.setProperty('--font', stack);
      layout(f);
      /* web fonts arrive asynchronously and change the flow */
      setTimeout(function () { layout(f); }, 350);
      setTimeout(function () { layout(f); }, 1200);
      if (document.fonts && document.fonts.ready) document.fonts.ready.then(function () { layout(f); });
    },
    setFont: function (px) { var f = fraction(); html.style.setProperty('--fs', px + 'px'); layout(f); },
    setLineHeight: function (lh) { var f = fraction(); html.style.setProperty('--lh', String(lh)); layout(f); },
    setMode: function (m) {
      var f = fraction();
      paged = (m === 'page');
      html.classList.toggle('paged', paged);
      layout(f);
    },
    setInsets: function (t, b, l, r) {
      var f = fraction(); padTop = t; padBottom = b;
      if (l !== undefined) { padL = l; padR = r; }
      layout(f);
    },
    goFraction: function (f) { layout(f); },
    goAnchor: function (id) { anchorId = id; if (!goAnchor()) report(); },
    /* a few words from the top of the page on screen (bookmark label) */
    pageText: function () {
      try {
        var r = document.caretRangeFromPoint(padL + 6, padTop + 14);
        var start = r && content.contains(r.startContainer) ? r.startContainer : null;
        var w = document.createTreeWalker(content, NodeFilter.SHOW_TEXT, null), n, out = '';
        if (start) { w.currentNode = start; out = (start.nodeType === 3 ? start.nodeValue.slice(r.startOffset) : ''); }
        while (out.replace(/\s+/g, ' ').trim().length < 70 && (n = w.nextNode())) out += n.nodeValue;
        return out.replace(/\s+/g, ' ').trim().slice(0, 70);
      } catch (e) { return ''; }
    },
    removeHighlight: function (id) { var f = fraction(); removeHighlight(id); if (paged) layout(f); }
  };

  /* links: same-chapter anchors jump here, everything else goes to the app */
  function followLink(abs) {
    var hash = abs.indexOf('#') >= 0 ? abs.slice(abs.indexOf('#') + 1) : '';
    if (abs.split('#')[0] === CFG.docUrl.split('#')[0]) {
      if (hash) window.Reader.goAnchor(decodeURIComponent(hash));
      return;
    }
    bridge.link(abs);
  }

  /* ── footnotes: a note reference opens a popup instead of jumping away ── */
  var pop = document.getElementById('notepop'), popBody = pop.querySelector('.nbody'), popHref = null;
  function popOpen() { return pop.style.display === 'block'; }
  function closePop() { pop.style.display = 'none'; popHref = null; }
  document.getElementById('nclose').addEventListener('click', function (e) { e.preventDefault(); closePop(); });
  document.getElementById('ngo').addEventListener('click', function (e) {
    e.preventDefault(); var h = popHref; closePop(); if (h) followLink(h);
  });
  function typeOf(el) { return ((el.getAttribute('epub:type') || '') + ' ' + (el.getAttribute('role') || '')).toLowerCase(); }
  function isNoteRef(a) {
    if (/noteref/.test(typeOf(a))) return true;
    var href = a.getAttribute('href') || '';
    if (href.indexOf('#') < 0) return false;
    var txt = (a.textContent || '').trim();
    if ((a.closest('sup') || a.querySelector('sup')) && txt.length <= 6) return true;
    return /^[\[\(（〔]?(\d{1,3}|[a-z]|[*†‡§¶]+|주\s?\d{0,3})[\]\)）〕]?$/i.test(txt);
  }
  /* the text of a note: the element with that id, or the paragraph / list item around an empty anchor */
  function noteHtml(doc, id) {
    var el = doc.getElementById(id) || doc.getElementsByName(id)[0];
    if (!el) return null;
    var box = el;
    var blocks = 'aside, li, p, dd, div, section, blockquote, td';
    if (!el.matches(blocks) || (el.textContent || '').trim().length < 2) box = el.closest(blocks) || el.parentNode || el;
    if ((box.textContent || '').trim().length < 2) return null;
    var c = box.cloneNode(true);
    c.querySelectorAll('script, style').forEach(function (x) { x.remove(); });
    c.querySelectorAll('a').forEach(function (x) {
      var t = typeOf(x), s = (x.textContent || '').trim();
      if (/backlink/.test(t) || /^[↩↑⤴︎]/.test(s) || /^(돌아가기|back)$/i.test(s)) x.remove();
      else x.removeAttribute('href');
    });
    c.querySelectorAll('[id], [class], [style]').forEach(function (x) {
      x.removeAttribute('id'); x.removeAttribute('class'); x.removeAttribute('style');
    });
    return c.innerHTML.trim();
  }
  function showNote(html, href) {
    popBody.innerHTML = html; popHref = href;
    /* sizes in px from the window: 52vh can resolve to 0 in a WebView laid out late,
       which squeezed the popup to its padding (one cut line, no buttons) */
    var vh = window.innerHeight || document.documentElement.clientHeight || 600;
    var bottom = Math.max(72, padBottom + 24);
    pop.style.bottom = bottom + 'px';
    pop.style.setProperty('max-height', Math.max(160, Math.min(vh * 0.6, vh - bottom - Math.max(72, padTop))) + 'px', 'important');
    pop.style.setProperty('height', 'auto', 'important');
    pop.style.display = 'block'; pop.scrollTop = 0;
  }
  function tryNote(a, abs) {
    var hash = abs.indexOf('#') >= 0 ? decodeURIComponent(abs.slice(abs.indexOf('#') + 1)) : '';
    if (!hash) return false;
    if (abs.split('#')[0] === CFG.docUrl.split('#')[0]) {
      var h = noteHtml(document, hash);
      if (!h) return false;
      showNote(h, abs); return true;
    }
    /* a note in another file of the book: read it without leaving the page */
    fetch(abs.split('#')[0]).then(function (r) { return r.text(); }).then(function (t) {
      var d = new DOMParser().parseFromString(t, 'text/html');
      var h = noteHtml(d, hash);
      if (h) showNote(h, abs); else followLink(abs);
    }).catch(function () { followLink(abs); });
    return true;
  }

  document.addEventListener('click', function (e) {
    if (popOpen() && pop.contains(e.target)) return;
    var a = e.target.closest && e.target.closest('a[href]');
    if (!a || !content.contains(a)) return;
    e.preventDefault();
    var abs = a.href || '';
    if (isNoteRef(a) && tryNote(a, abs)) return;
    followLink(abs);
  }, true);

  /* taps and swipes */
  var sx = 0, sy = 0, st = 0;
  var dragTurn = null;   /* a curl being dragged with the finger (the app draws it) */
  /* pictures open in the zoom viewer on a long press; a short tap on them pages / shows the bar like anywhere else */
  var pressTimer = null, pressOpened = false;
  function imageAt(target) {
    return target && target.closest && (target.closest('#content img') || target.closest('#content image'));
  }
  function openImage(img) {
    var src = img.currentSrc || img.src || '';
    if (!src && img.href && img.href.baseVal !== undefined) src = new URL(img.href.baseVal, document.baseURI).href;
    if (!src) src = new URL(img.getAttribute('xlink:href') || img.getAttribute('href') || '', document.baseURI).href;
    bridge.openImage(src);
  }
  document.addEventListener('contextmenu', function (e) { if (imageAt(e.target)) e.preventDefault(); });
  document.addEventListener('touchstart', function (e) {
    focusId = null; anchorId = null;
    var t = e.touches[0]; sx = t.clientX; sy = t.clientY; st = Date.now();
    pressOpened = false;
    clearTimeout(pressTimer);
    var img = e.touches.length === 1 ? imageAt(e.target) : null;
    if (img) pressTimer = setTimeout(function () {
      if (dragTurn !== null || currentSelection()) return;
      pressOpened = true;
      openImage(img);
    }, 500);
  }, { passive: true });
  document.addEventListener('touchmove', function (e) {
    var m = e.touches[0];
    if (Math.abs(m.clientX - sx) > 10 || Math.abs(m.clientY - sy) > 10) clearTimeout(pressTimer);
    if (popOpen()) return;
    if (!paged || turn !== 'curl' || !window.Oasis || !window.Oasis.dragStart) return;
    var t = e.touches[0];
    var dx = t.clientX - sx, dy = t.clientY - sy;
    if (!dragTurn) {
      if (Math.abs(dx) < 14 || Math.abs(dx) < Math.abs(dy) * 1.2 || currentSelection()) return;
      var dir = dx < 0 ? 1 : -1;
      var p = page + dir;
      if (p < 0 || p > total - 1 || pendingTurn !== null) return;   /* chapter edges: the usual swipe */
      dragTurn = dir;
      pendingTurn = p;
      window.Oasis.dragStart(dir, sy / window.innerHeight, W());
      turnTimer = setTimeout(commitTurn, 600);
    }
    window.Oasis.dragMove(t.clientX, t.clientY);
  }, { passive: true });
  document.addEventListener('touchend', function (e) {
    var t = e.changedTouches[0];
    clearTimeout(pressTimer);
    if (pressOpened) { pressOpened = false; return; }   /* the long press already opened the picture */
    if (popOpen()) {
      if (!pop.contains(e.target) && dragTurn === null) closePop();
      if (dragTurn === null) return;
    }
    if (dragTurn !== null) {
      dragTurn = null;
      window.Oasis.dragEnd(t.clientX - sx, (t.clientX - sx) / Math.max(1, Date.now() - st));
      return;
    }
    var dx = t.clientX - sx, dy = t.clientY - sy;
    var target = e.target;
    if (target.closest && target.closest('#hlbar')) return;
    if (target.closest && target.closest('#chapnav')) return;
    if (currentSelection()) return;                 /* selecting text: don't page */
    var isTap = Math.abs(dx) < 10 && Math.abs(dy) < 10 && Date.now() - st < 400;
    /* the strip with the chapter title at the top always brings up the bar */
    if (isTap && sy < Math.max(padTop, 44)) { bridge.tap(); return; }
    var mark = target.closest && target.closest('mark.hl');
    if (mark && isTap) { bridge.tapHighlight(mark.getAttribute('data-hl')); return; }
    if (target.closest && target.closest('a')) return;
    turnY = Math.max(0, Math.min(1, ((sy + t.clientY) / 2) / window.innerHeight));
    if (paged && Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5) {
      if (dx < 0) window.Reader.next(); else window.Reader.prev();
      return;
    }
    if (isTap) {
      var x = t.clientX / W();
      if (paged && x < 0.3) window.Reader.prev();
      else if (paged && x > 0.7) window.Reader.next();
      else bridge.tap();
    }
  });

  var scrollTimer = null;
  window.addEventListener('scroll', function () {
    if (paged) return;
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(report, 150);
  });

  var resizeTimer = null;
  window.addEventListener('resize', function () {
    var f = fraction();
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { layout(f); }, 120);
  });

  /* long unbroken tokens (URLs, emails) may break anywhere; otherwise justify
     stretches the previous line into a big gap */
  (function breakLongTokens() {
    var re = /[^\s]{24,}/g, w = document.createTreeWalker(content, NodeFilter.SHOW_TEXT, null), list = [], n;
    while ((n = w.nextNode())) {
      re.lastIndex = 0;
      if (n.parentNode && n.parentNode.closest && n.parentNode.closest('svg, pre, code')) continue;
      if (re.test(n.nodeValue)) list.push(n);
    }
    list.forEach(function (node) {
      var text = node.nodeValue, frag = document.createDocumentFragment(), last = 0, m;
      re.lastIndex = 0;
      while ((m = re.exec(text))) {
        if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
        var sp = document.createElement('span'); sp.className = 'brk'; sp.textContent = m[0];
        frag.appendChild(sp);
        last = m.index + m[0].length;
      }
      if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
      node.parentNode.replaceChild(frag, node);
    });
  })();

  /* existing highlights, first layout, re-layout as images and fonts load */
  CFG.highlights.forEach(applyHighlight);
  /* opened from search: mark the match, open at it (like a highlight from the list), clear it later */
  if (CFG.find) (function markFind() {
    var idx = textIndex();
    var F = collapse(idx.full), low = F.s.toLowerCase();
    var q = collapse(CFG.find).s.trim().toLowerCase();
    if (!q) return;
    var at = -1, from = 0;
    for (var n = 0; n <= CFG.findNth; n++) { at = low.indexOf(q, from); if (at < 0) break; from = at + 1; }
    if (at < 0) at = low.indexOf(q);
    if (at < 0) return;
    wrapRange(idx, F.map[at], F.map[at + q.length - 1] + 1, '__find', '#ffca28');
    var marks = content.querySelectorAll('mark[data-hl="__find"]');
    for (var i = 0; i < marks.length; i++) marks[i].className = 'find';
    focusId = '__find';
    setTimeout(function () {
      var f = fraction();
      var ms = content.querySelectorAll('mark[data-hl="__find"]');
      for (var j = 0; j < ms.length; j++) {
        var m = ms[j], par = m.parentNode;
        while (m.firstChild) par.insertBefore(m.firstChild, m);
        par.removeChild(m); par.normalize();
      }
      if (focusId === '__find') focusId = null;
      if (paged) layout(f);
    }, 8000);
  })();
  layout(CFG.start);
  for (var j = 0; j < imgs.length; j++) {
    if (!imgs[j].complete) imgs[j].addEventListener('load', function () { layout(); });
  }
  window.addEventListener('load', function () { layout(); });
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(function () { layout(); });
})();
</script>
</body>
</html>
"""
    }
}
