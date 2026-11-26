(function () {
  var CFG = __OG_CFG__;
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
  if (CFG.headShow === false) document.getElementById('rhead').style.display = 'none';
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

  /* palette over a selection; with a start kept ("여기서 시작") it reads "N쪽부터 ● ● ● ● ● ×" */
  var fromLbl = document.createElement('span');
  fromLbl.className = 'from';
  fromLbl.style.display = 'none';
  bar.appendChild(fromLbl);
  var colorBtns = [];
  CFG.colors.forEach(function (c) {
    var btn = document.createElement('button');
    colorBtns.push(btn);
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
      var s = currentSelection() || noteSelection();
      if (!s) return;
      var t = s.text.trim();
      window.getSelection().removeAllRanges();
      bar.style.display = 'none';
      if (bridge.lookup) bridge.lookup(t);
    });
    bar.appendChild(btn);
  })();
  var startBtn = document.createElement('button');
  startBtn.className = 'start';
  startBtn.textContent = '여기서 시작';
  startBtn.addEventListener('click', function (e) { e.preventDefault(); startHere(); });
  bar.appendChild(startBtn);
  var cancelBtn = document.createElement('button');
  cancelBtn.className = 'cancel';
  cancelBtn.textContent = '✕';
  cancelBtn.setAttribute('aria-label', '취소');
  cancelBtn.style.display = 'none';
  cancelBtn.addEventListener('click', function (e) {
    e.preventDefault();
    window.getSelection().removeAllRanges();
    bar.style.display = 'none';
    clearStart();
  });
  bar.appendChild(cancelBtn);
  /* inside a footnote: copy and, for an address, open */
  var copyBtn = document.createElement('button');
  copyBtn.className = 'look';
  copyBtn.textContent = '복사';
  copyBtn.style.display = 'none';
  copyBtn.addEventListener('click', function (e) {
    e.preventDefault();
    var s = noteSelection() || currentSelection();
    if (!s) return;
    var t = s.text.trim();
    window.getSelection().removeAllRanges();
    bar.style.display = 'none';
    if (bridge.copy) bridge.copy(t);
    else if (navigator.clipboard) navigator.clipboard.writeText(t).catch(noop);
  });
  bar.appendChild(copyBtn);
  var openBtn = document.createElement('button');
  openBtn.className = 'start';
  openBtn.textContent = '열기';
  openBtn.style.display = 'none';
  openBtn.addEventListener('click', function (e) {
    e.preventDefault();
    var s = noteSelection() || currentSelection();
    var u = s ? looseUrl(s.text) : null;
    window.getSelection().removeAllRanges();
    bar.style.display = 'none';
    if (u) bridge.link(u);
  });
  bar.appendChild(openBtn);
  var lookBtn = bar.querySelector('button.look');
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

  /* a selection inside the footnote popup (no highlight there: the note lives elsewhere in the book) */
  function noteSelection() {
    if (!popOpen()) return null;
    var sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
    var r = sel.getRangeAt(0);
    if (!popBody.contains(r.commonAncestorContainer)) return null;
    var text = sel.toString();
    if (!text.trim()) return null;
    return { range: r, text: text };
  }

  /* ── addresses in notes. Strict (drawn as links without selecting): http(s)://…, www.…,
     doi:10.…, or name.known-ending followed by a path (doi.org/…, bit.ly/…, x.co.kr/…).
     Loose (the 열기 button on a selection the reader picked): any word.word without spaces. ── */
  var URL_TLD = '(?:com|net|org|edu|gov|mil|int|info|biz|io|co|me|ly|ai|app|dev|tv|us|uk|ca|au|eu|de|fr|jp|cn|kr|ac\\.kr|go\\.kr|or\\.kr|co\\.kr|ne\\.kr|re\\.kr|co\\.jp|ac\\.jp|co\\.uk|ac\\.uk)';
  var URL_CH = "[A-Za-z0-9\\-._~:/?#\\[\\]@!&'()*+,;=%]";
  var URL_RE = new RegExp('(^|[^A-Za-z0-9.@/_-])(' +
    'https?://' + URL_CH + '+' +
    '|www\\.' + URL_CH + '+' +
    '|doi:\\s?10\\.[0-9]{4,9}/' + URL_CH + '+' +
    '|(?:[A-Za-z0-9-]+\\.)+' + URL_TLD + '/' + URL_CH + '*' +
    ')', 'gi');
  function trimUrl(u) { return u.replace(/[.,;:!?'")\]]+$/, ''); }
  function hrefOf(u) {
    if (/^https?:\/\//i.test(u)) return u;
    if (/^doi:/i.test(u)) return 'https://doi.org/' + u.replace(/^doi:\s?/i, '');
    return 'https://' + u;
  }
  function looseUrl(t) {
    t = trimUrl((t || '').trim());
    if (!t || /\s/.test(t) || t.length > 2000) return null;
    if (/^doi:\s?10\./i.test(t)) return hrefOf(t);
    if (/^https?:\/\/[^/]+\.[^/]+/i.test(t)) return t;
    var host = t.split(/[/?#]/)[0];
    if (host === t && /\.(jpe?g|png|gif|webp|svg|pdf|epub|txt|md|x?html?|css|js|zip)$/i.test(host)) return null;   /* a file name */
    if (/^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$/.test(host)) return 'https://' + t;
    return null;
  }
  function linkify(root) {
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null), n, list = [];
    while ((n = w.nextNode())) { if (!n.parentNode.closest('a')) list.push(n); }
    list.forEach(function (node) {
      var t = node.nodeValue, m, last = 0, frag = null;
      URL_RE.lastIndex = 0;
      while ((m = URL_RE.exec(t))) {
        var u = trimUrl(m[2]);
        if (!u) continue;
        var at = m.index + m[1].length;
        if (!frag) frag = document.createDocumentFragment();
        frag.appendChild(document.createTextNode(t.slice(last, at)));
        var a = document.createElement('a');
        a.setAttribute('data-url', hrefOf(u));
        a.textContent = u;
        frag.appendChild(a);
        last = at + u.length;
        URL_RE.lastIndex = last;
      }
      if (!frag) return;
      frag.appendChild(document.createTextNode(t.slice(last)));
      node.parentNode.replaceChild(frag, node);
    });
  }

  function showBar() {
    var s = currentSelection();
    var note = s ? null : noteSelection();
    if (!s && !note) { bar.style.display = 'none'; if (hlStart) refreshStrip(); return; }
    if (note) s = note;
    var ending = !note && !!hlStart;
    for (var ci = 0; ci < colorBtns.length; ci++) colorBtns[ci].style.display = note ? 'none' : '';
    fromLbl.style.display = ending ? '' : 'none';
    cancelBtn.style.display = ending ? '' : 'none';
    lookBtn.style.display = ending ? 'none' : '';
    startBtn.style.display = (note || ending) ? 'none' : '';
    copyBtn.style.display = note ? '' : 'none';
    openBtn.style.display = note && looseUrl(note.text) ? '' : 'none';
    if (ending) { fromLbl.textContent = startLabel() + '부터'; setStrip(false); }
    /* above the first line of the selection: a selection is usually dragged downward, so the
       bar stays out of the way of the end handle (below only when there is no room above) */
    var rects = s.range.getClientRects();
    var rect = rects.length ? rects[0] : s.range.getBoundingClientRect();
    var all = s.range.getBoundingClientRect();
    bar.style.display = 'flex';
    var bw = bar.offsetWidth, bh = bar.offsetHeight;
    var x = Math.max(8, Math.min(W() - bw - 8, rect.left + rect.width / 2 - bw / 2));
    var y = rect.top - bh - 16;
    if (y < 8) y = Math.min(window.innerHeight - bh - 8, all.bottom + 30);
    bar.style.left = x + 'px';
    bar.style.top = y + 'px';
  }

  function createFromSelection(color) {
    var s = currentSelection();
    if (!s) return;
    var range = s.range, text = s.text, from = -1, to = -1;
    if (hlStart) {
      /* from the kept start to this selection (or from this selection to the kept one, if it is earlier) */
      range = document.createRange();
      if (hlStart.start.compareBoundaryPoints(Range.START_TO_START, s.range) <= 0) {
        range.setStart(hlStart.start.startContainer, hlStart.start.startOffset);
        range.setEnd(s.range.endContainer, s.range.endOffset);
      } else {
        range.setStart(s.range.startContainer, s.range.startOffset);
        range.setEnd(hlStart.end.startContainer, hlStart.end.startOffset);
      }
      if (range.compareBoundaryPoints(Range.END_TO_END, s.range) < 0) range.setEnd(s.range.endContainer, s.range.endOffset);
      if (range.compareBoundaryPoints(Range.END_TO_END, hlStart.end) < 0) range.setEnd(hlStart.end.startContainer, hlStart.end.startOffset);
      text = range.toString();
      from = hlStart.page; to = selPage(s.range);
    }
    var pre = document.createRange();
    pre.setStart(content, 0);
    pre.setEnd(range.endContainer, range.endOffset);
    var anchor = pre.toString().slice(-50);
    var id = String(Date.now());
    if (text.trim().length < 2) return;
    var h = { id: id, text: text.trim(), color: color, anchor: anchor };
    window.getSelection().removeAllRanges();
    bar.style.display = 'none';
    var across = hlStart && paged && from >= 0 && to >= 0 && from !== to;
    clearStart();
    if (applyHighlight(h)) {
      bridge.addHighlight(h.id, h.text, h.color, h.anchor);
      if (across) toast(Math.min(from, to) + 1 + '–' + (Math.max(from, to) + 1) + '쪽에 걸쳐 하이라이트를 남겼어요');
    }
  }

  /* ── a highlight across pages: "여기서 시작" keeps where the selection began and lets go of it,
     so taps, swipes and the volume keys turn pages as usual; the next selection is the end.
     A new chapter is a new page, so a start never outlives its chapter. ── */
  var hlStart = null;   /* { start, end: collapsed live Ranges, page, snippet } */
  var strip = document.getElementById('hlstrip'), startMark = document.getElementById('hlstart');
  var stripTimer = 0;
  document.getElementById('hlcancel').addEventListener('click', function (e) { e.preventDefault(); clearStart(); });

  function selPage(r) {
    if (!paged) return -1;
    var rs = r.getClientRects(), rc = rs.length ? rs[0] : r.getBoundingClientRect();
    return Math.max(0, Math.min(total - 1, Math.floor((rc.left + book.scrollLeft + 1) / W())));
  }
  /* the strip sits over the page number: the app hides its number while the strip is up */
  var stripUp = false;
  function setStrip(on) {
    strip.style.display = on ? 'flex' : 'none';
    if (stripUp !== !!on) { stripUp = !!on; if (bridge.cover) bridge.cover(stripUp); }
  }
  function startLabel() { return hlStart && hlStart.page >= 0 ? (hlStart.page + 1) + '쪽' : '시작점'; }

  function startHere() {
    var s = currentSelection();
    if (!s) return;
    var a = document.createRange(); a.setStart(s.range.startContainer, s.range.startOffset); a.collapse(true);
    var b = document.createRange(); b.setStart(s.range.endContainer, s.range.endOffset); b.collapse(true);
    var after = document.createRange();
    after.setStart(s.range.startContainer, s.range.startOffset);
    after.setEnd(content, content.childNodes.length);
    var snip = after.toString().replace(/\s+/g, ' ').trim();
    hlStart = { start: a, end: b, page: selPage(s.range), snippet: snip.length > 14 ? snip.slice(0, 14) + '…' : snip };
    window.getSelection().removeAllRanges();
    bar.style.display = 'none';
    clearTimeout(stripTimer);
    refreshStrip();
    requestAnimationFrame(tickStart);
  }
  function clearStart() {
    hlStart = null;
    startMark.style.display = 'none';
    if (!stripTimer) setStrip(false);
  }
  function refreshStrip() {
    if (!hlStart) return;
    var here = !paged || hlStart.page === page;
    strip.querySelector('.st').textContent = here ? '시작점을 잡았어요' : startLabel() + '에서 시작';
    strip.querySelector('.ss').textContent = (here ? '' : '“' + hlStart.snippet + '” · ') +
      (here ? '쪽을 넘겨 끝낼 글자를 길게 누르세요' : '끝낼 글자를 길게 누르세요');
    document.getElementById('hlcancel').style.display = '';
    strip.querySelector('.sm').style.display = '';
    if (bar.style.display !== 'flex') setStrip(true);
  }
  function toast(msg) {
    strip.querySelector('.st').textContent = msg;
    strip.querySelector('.ss').textContent = '';
    document.getElementById('hlcancel').style.display = 'none';
    strip.querySelector('.sm').style.display = 'none';
    setStrip(true);
    clearTimeout(stripTimer);
    stripTimer = setTimeout(function () { stripTimer = 0; if (hlStart) refreshStrip(); else setStrip(false); }, 2500);
  }
  /* the mark before the first kept letter follows the page while it turns */
  function startRect() {
    var r = hlStart.start, rs = r.getClientRects();
    if (rs.length && (rs[0].height || rs[0].width)) return rs[0];
    try {
      var n = r.startContainer;
      if (n.nodeType === 3 && r.startOffset < n.nodeValue.length) {
        var x = document.createRange(); x.setStart(n, r.startOffset); x.setEnd(n, r.startOffset + 1);
        rs = x.getClientRects();
        if (rs.length) return rs[0];
      }
    } catch (e) {}
    return null;
  }
  var lastStripPage = -2;
  function tickStart() {
    if (!hlStart) return;
    var rc = startRect();
    if (!rc || rc.right < 0 || rc.left > W() || rc.bottom < 0 || rc.top > window.innerHeight) {
      startMark.style.display = 'none';
    } else {
      startMark.style.display = 'block';
      startMark.style.left = (rc.left - 4) + 'px';
      startMark.style.top = rc.top + 'px';
      startMark.style.height = Math.max(12, rc.height) + 'px';
    }
    if (paged && page !== lastStripPage && !stripTimer) { lastStripPage = page; refreshStrip(); }
    requestAnimationFrame(tickStart);
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

  /* ── read aloud: the chapter as sentences (live Ranges, so highlights added later do not break them) ── */
  var tts = null;
  var TTS_BLOCK = 'p, li, h1, h2, h3, h4, h5, h6, blockquote, dd, dt, td, th, figcaption, caption, pre, div, section, article, aside, header, footer, table';
  __OG_TTS_DECL__
  /* a sentence also ends with no space before a Korean letter or an opening quote (텍본 EPUBs);
     invisible spaces count as spaces; a long sentence is cut again at a comma or a space */
  /* headings (85): <br> inside joins the lines; a first line that is only the chapter's number
     (제3장 · 3장 · PART 1 · ⑥ …, same as ReadAloudText.LABEL) stays apart; the heading's last
     sentence ends with TTS_HEAD (not spoken, a longer pause after it) */
  var TTS_HEAD = '\u2063', TTS_H = 'h1, h2, h3, h4, h5, h6';
  function ttsLabel(t) {
    t = t.replace(/\s+/g, ' ').trim();
    var m = t.match(TTS_LABEL);
    return !!m && m.index === 0 && m[0].length === t.length;
  }
  function ttsSpace(c) { return /\s/.test(c) || /[\u200B-\u200D\u2060\uFEFF]/.test(c); }
  function ttsAfter(c) { return ttsSpace(c) || TTS_OPEN.indexOf(c) >= 0 || /[\uAC00-\uD7A3\u3131-\u318E]/.test(c); }
  /* note references are not read: a note link (any form), or a small raised number / mark */
  var skipCache = new Map();
  function ttsSkip(el) {
    if (skipCache.has(el)) return skipCache.get(el);
    var skip = false;
    var a = el.closest('a[href]');
    if (a && (isNoteRef(a) || /^[\[\(]?[0-9]{1,3}[\]\)]?$/.test((a.textContent || '').trim()))) skip = true;
    if (!skip) {
      var up = el.closest('sup') || el;
      var txt = (up.textContent || '').trim();
      if (txt.length <= 4 && /^[\[\(]?[0-9*†‡§¶a-z]{1,3}[\]\)]?$/i.test(txt)) {
        if (up.tagName === 'SUP') skip = true;
        else if (up !== content && up.parentNode) {
          /* raised or shrunk inline marks (books that style a span instead of using <sup>) */
          var cs = getComputedStyle(up), va = cs.verticalAlign;
          var small = parseFloat(cs.fontSize) < parseFloat(getComputedStyle(up.parentNode).fontSize) * 0.85;
          var lone = (up.parentNode.textContent || '').trim().length > txt.length;
          if (lone && (va === 'super' || va === 'top' || va === 'text-top' || (small && cs.display.indexOf('inline') === 0))) skip = true;
        }
      }
    }
    skipCache.set(el, skip);
    return skip;
  }
  function ttsBuild() {
    skipCache = new Map();
    var out = [], seg = null, shown = new Map(), labelBlk = null;
    function visible(el) {
      if (shown.has(el)) return shown.get(el);
      var v = el.getClientRects().length > 0;
      shown.set(el, v); return v;
    }
    function flush() {
      if (!seg) return;
      var s = seg.text, a = 0, before = out.length;
      function push(x, y) {
        while (x < y && ttsSpace(s.charAt(x))) x++;
        while (y > x && ttsSpace(s.charAt(y - 1))) y--;
        if (y <= x) return;
        var t = s.slice(x, y).replace(/[\u200B-\u200D\u2060\uFEFF]/g, '').replace(/\s+/g, ' ').trim();
        if (!/[0-9A-Za-z\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7A3\u2160-\u217B\u2460-\u249B\u3260-\u327B]/.test(t)) return;
        var r = document.createRange();
        var A = at(x, false), B = at(y, true);
        r.setStart(A.node, A.off); r.setEnd(B.node, B.off);
        out.push({ text: t, range: r });
      }
      function at(o, end) {
        var ns = seg.nodes;
        for (var k = 0; k < ns.length; k++) {
          var n = ns[k], len = n.node.nodeValue.length;
          if (o < n.start + len || (end && o === n.start + len)) return { node: n.node, off: Math.max(0, o - n.start) };
        }
        var last = ns[ns.length - 1];
        return { node: last.node, off: last.node.nodeValue.length };
      }
      /* a long sentence: cut at the last comma before TTS_LONG letters; at a space only past
         TTS_HARD with no comma (text with no sentence ends) — 2.9 */
      function piece(x, y) {
        while (y - x > TTS_LONG) {
          var lim = x + TTS_LONG, cut = -1, k;
          for (k = lim; k > x + TTS_MIN; k--) if (TTS_COMMA.indexOf(s.charAt(k - 1)) >= 0 && ttsSpace(s.charAt(k))) { cut = k; break; }
          if (cut < 0) {
            if (y - x <= TTS_HARD) break;
            for (k = lim; k > x + TTS_MIN; k--) if (ttsSpace(s.charAt(k))) { cut = k; break; }
          }
          if (cut < 0) cut = lim;
          if (y - cut < TTS_MIN) break;
          push(x, cut); x = cut;
        }
        push(x, y);
      }
      /* 89: "4장 목표에 미칠 때" — the number apart, with a heading's pause */
      if (seg.head) {
        var x0 = 0;
        while (x0 < s.length && ttsSpace(s.charAt(x0))) x0++;
        var lm = s.slice(x0).match(TTS_LEAD);
        if (lm && lm.index === 0) {
          var n0 = out.length;
          push(x0, x0 + lm[0].length);
          if (out.length > n0) out[out.length - 1].text += TTS_HEAD;
          a = x0 + lm[0].length;
        }
      }
      for (var i = a; i < s.length; i++) {
        if (TTS_END.indexOf(s.charAt(i)) < 0) continue;
        var j = i + 1;
        while (j < s.length && TTS_CLOSE.indexOf(s.charAt(j)) >= 0) j++;
        /* ‘네가 바로 그것이다.’와 · “…했다.”라고: a quoted sentence the line goes on from is not an end (2.10.x) */
        var quoted = false;
        for (var q = i + 1; q < j; q++) if (TTS_QEND.indexOf(s.charAt(q)) >= 0) quoted = true;
        if (quoted && j < s.length && /[\uAC00-\uD7A3]/.test(s.charAt(j))) continue;
        if (j >= s.length || ttsAfter(s.charAt(j))) { piece(a, j); a = j; i = j - 1; }
      }
      piece(a, s.length);
      if (seg.head && out.length > before) out[out.length - 1].text += TTS_HEAD;
      seg = null;
    }
    var w = document.createTreeWalker(content, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null), n;
    while ((n = w.nextNode())) {
      /* <br> ends a sentence (텍본 EPUBs put whole chapters in one <p> split by <br>) */
      if (n.nodeType === 1) {
        if (n.tagName !== 'BR') continue;
        /* in a heading: join, unless what came before is only a chapter number (85) */
        if (seg && seg.head && n.closest(TTS_H) && !(seg.lines === 0 && ttsLabel(seg.text))) { seg.text += ' '; seg.lines++; }
        else { if (seg && seg.head && n.closest(TTS_H)) labelBlk = seg.block; flush(); }
        continue;
      }
      var par = n.parentNode;
      if (!par || !par.closest) continue;
      if (par.closest('rt, rp, script, style, noscript')) continue;
      if (ttsSkip(par)) continue;      /* note numbers */
      if (!visible(par)) continue;
      var blk = par.closest(TTS_BLOCK) || content;
      if (!seg || seg.block !== blk) { flush(); seg = { block: blk, text: '', nodes: [], head: !!par.closest(TTS_H), lines: labelBlk === blk ? 1 : 0 }; }
      seg.nodes.push({ node: n, start: seg.text.length });
      seg.text += n.nodeValue;
    }
    flush();
    return out;
  }
  /* boxes behind sentence i, inside #book so they turn with the page (it scrolls sideways) */
  var ttsLayer = null;
  function ttsPaint(i) {
    if (!ttsLayer || ttsLayer.parentNode !== book) {
      ttsLayer = document.createElement('div'); ttsLayer.id = 'ttsbg'; book.appendChild(ttsLayer);
    }
    ttsLayer.innerHTML = '';
    var rs = tts[i].range.getClientRects(), bb = book.getBoundingClientRect(), prev = null;
    for (var k = 0; k < rs.length; k++) {
      var r = rs[k];
      if (r.width < 1 || r.height < 1) continue;
      /* a rect inside the one before (nested inline nodes) would darken the spot */
      if (prev && r.left >= prev.left - 0.5 && r.right <= prev.right + 0.5 && r.top >= prev.top - 0.5 && r.bottom <= prev.bottom + 0.5) continue;
      prev = r;
      var d = document.createElement('div');
      d.style.left = (r.left - bb.left + book.scrollLeft) + 'px';
      d.style.top = (r.top - bb.top + book.scrollTop) + 'px';
      d.style.width = r.width + 'px';
      d.style.height = r.height + 'px';
      ttsLayer.appendChild(d);
    }
  }
  function ttsRect(i, last) {
    var rs = tts && tts[i] ? tts[i].range.getClientRects() : [];
    if (!rs.length) return null;
    return last ? rs[rs.length - 1] : rs[0];
  }
  function ttsShow(i) {
    var r = ttsRect(i, false);
    if (!r) return;
    if (paged) {
      /* the page the sentence starts on (same rule as links / highlights) */
      var p = Math.max(0, Math.min(total - 1, Math.floor((r.left + book.scrollLeft + 1) / W())));
      var want = pendingTurn !== null ? pendingTurn : page;
      if (p !== want) {
        focusId = null; anchorId = null;
        /* hidden (screen off / another app): no animation, nothing would draw it */
        if (pendingTurn !== null) commitTurn();
        go(p, document.visibilityState !== 'hidden' && Math.abs(p - page) === 1);
      }
    } else {
      /* 105: the running head covers the top of the screen — with a small top margin a sentence
         under it (or under its fade) is out of view too */
      var head = document.getElementById('rhead').offsetHeight;
      var top = Math.max(padTop, head ? head + 8 : 0);
      var bottom = window.innerHeight - padBottom - 24;
      /* the last sentence on the screen — it runs off the bottom, or the next one would — goes up
         as it starts, so that what is read is always followed by what comes next */
      var end = ttsRect(i, true), next = ttsRect(i + 1, false);
      var last = end.bottom > bottom || (next !== null && next.bottom > bottom);
      if (r.top < top || (last && r.top > top + 48)) {
        window.scrollTo({ top: Math.max(0, window.scrollY + r.top - top - 40), behavior: 'smooth' });
      }
    }
  }

  /* ── 106: the same place when the view changes ────────────── */
  var ttsNow = -1, ttsAt = null;   /* the sentence marked; its range stays after reading ends */
  function headHeight() { return document.getElementById('rhead').offsetHeight; }
  /* on this screen or after it / on this screen */
  /* scroll: clear of the top line (the page's own, or the app's status line, as tall) */
  function fromScreen(r) { return paged ? r.left >= 0 : r.top >= Math.max(headHeight(), 30) - 2; }
  function onScreen(r) {
    return fromScreen(r) && (paged ? r.left < W() - 1 : r.top < window.innerHeight - padBottom);
  }
  /* the first letter at or after i that is drawn: { i, r } (spaces at a line's end are not) */
  function letter(node, i) {
    var g = document.createRange(), s = node.nodeValue;
    for (; i < s.length; i++) {
      if (/\s/.test(s.charAt(i))) continue;
      g.setStart(node, i); g.setEnd(node, i + 1);
      var rs = g.getClientRects();
      if (rs.length && rs[0].width > 0) return { i: i, r: rs[0] };
    }
    return null;
  }
  /* the sentence being read if its start is on screen, else the first letter on screen */
  function placeRange() {
    if (ttsAt) { var now = ttsAt.getClientRects(); if (now.length && onScreen(now[0])) return ttsAt; }
    var w = document.createTreeWalker(content, NodeFilter.SHOW_TEXT, null), n;
    while ((n = w.nextNode())) {
      if (!/\S/.test(n.nodeValue)) continue;
      var all = document.createRange(); all.selectNodeContents(n);
      var rs = all.getClientRects();
      if (!rs.length || !fromScreen(rs[rs.length - 1])) continue;   /* ends before the screen */
      var lo = 0, hi = n.nodeValue.length - 1;
      while (lo < hi) {
        var mid = (lo + hi) >> 1, c = letter(n, mid);
        if (c && !fromScreen(c.r)) lo = mid + 1; else hi = mid;
      }
      var first = letter(n, lo);
      if (!first) continue;
      if (!onScreen(first.r)) return null;      /* a picture fills the screen: keep the fraction */
      /* scroll view: the end of that line — where pages wrap a little differently, a line cut
         by a page break then opens the later page, where the rest of the screen is */
      var use = first;
      while (!paged) {
        var next = letter(n, use.i + 1);
        if (!next || next.r.top > first.r.top + first.r.height / 2) break;
        use = next;
      }
      var at = document.createRange(); at.setStart(n, use.i); at.setEnd(n, use.i + 1);
      return at;
    }
    return null;
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
    /* 104: back, full screen, closes only what lies on the page — a selection, else the note popup
       (a highlight's kept start stays: pages are being turned to find its end) */
    back: function () {
      if (currentSelection() || noteSelection()) {
        window.getSelection().removeAllRanges(); bar.style.display = 'none';
        if (hlStart) refreshStrip();
      } else if (popOpen()) closePop();
    },
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
    /* 106: read aloud's scroll view and back — unlike setMode, opens at the same words */
    setView: function (m) {
      if ((m === 'page') === paged) return;
      if (pendingTurn !== null) commitTurn();
      stopSlide();
      var at = placeRange(), f = fraction();
      focusId = null; anchorId = null;
      paged = (m === 'page');
      html.classList.toggle('paged', paged);
      layout(f);
      var r = at ? at.getClientRects()[0] : null;
      if (r) {
        if (paged) go(Math.floor((r.left + book.scrollLeft + 1) / W()));
        else {
          var head = headHeight();
          window.scrollTo(0, Math.max(0, window.scrollY + r.top - Math.max(padTop, head ? head + 8 : 0)));
          report();
        }
      }
      if (tts && tts[ttsNow]) ttsPaint(ttsNow); else ttsAt = null;
    },
    setInsets: function (t, b, l, r) {
      var f = fraction(); padTop = t; padBottom = b;
      if (l !== undefined) { padL = l; padR = r; }
      layout(f);
    },
    goFraction: function (f) { layout(f); },
    setHead: function (v) { document.getElementById('rhead').style.display = v ? '' : 'none'; },
    goAnchor: function (id) { anchorId = id; if (!goAnchor()) report(); },
    /* read aloud: this chapter's sentences and the first one on the page on screen */
    ttsList: function (matchText, frac) {
      tts = ttsBuild();
      ttsNow = -1; ttsAt = null;
      var start = tts.length;
      if (typeof matchText === 'string' && matchText) {
        /* after reading on from the file (screen off): the same sentence, nearest that place */
        var norm = function (t) { return t.replace(/\s+/g, ' ').trim(); };
        var want = norm(matchText), best = -1, bestD = 2, n = tts.length || 1;
        for (var k = 0; k < tts.length; k++) {
          if (norm(tts[k].text) !== want) continue;
          var d = Math.abs(k / n - (frac || 0));
          if (d < bestD) { bestD = d; best = k; }
        }
        if (best < 0) best = Math.min(tts.length - 1, Math.max(0, Math.round((frac || 0) * tts.length)));
        if (best >= 0) { ttsShow(best); return { start: best, items: tts.map(function (x) { return x.text; }) }; }
      }
      for (var i = 0; i < tts.length; i++) {
        var r = ttsRect(i, true);
        if (!r) continue;
        if (paged ? r.right > 1 : r.bottom > padTop) { start = i; break; }
      }
      return { start: start, items: tts.map(function (x) { return x.text; }) };
    },
    ttsMark: function (i) {
      if (!tts || !tts[i]) return;
      ttsNow = i; ttsAt = tts[i].range;
      ttsPaint(i);
      ttsShow(i);
    },
    ttsClear: function () {
      if (ttsLayer) ttsLayer.innerHTML = '';
      tts = null; ttsNow = -1;
    },
    /* 지금 소제목 (2.9): of these anchor ids (in 목차 order; '' = the file's start), the last one
       already on or before the page on screen; -1 if none */
    sectionAt: function (ids) {
      var last = -1;
      for (var i = 0; i < ids.length; i++) {
        if (!ids[i]) { last = i; continue; }
        var el = document.getElementById(ids[i]);
        if (!el) continue;
        var r = el.getBoundingClientRect();
        if (paged ? r.left < window.innerWidth - 1 : r.top < window.innerHeight * 0.5) last = i;
      }
      return last;
    },
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
      /* the app keeps where we were, to offer the way back */
      if (hash) { bridge.jump(); window.Reader.goAnchor(decodeURIComponent(hash)); }
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
      var href = x.getAttribute('href') || '';
      if (/backlink/.test(t) || /^[↩↑⤴︎]/.test(s) || /^(돌아가기|back)$/i.test(s)) x.remove();
      else {
        if (/^(https?:|mailto:)/i.test(href)) x.setAttribute('data-url', href);
        x.removeAttribute('href');
      }
    });
    c.querySelectorAll('[id], [class], [style]').forEach(function (x) {
      x.removeAttribute('id'); x.removeAttribute('class'); x.removeAttribute('style');
    });
    return c.innerHTML.trim();
  }
  function showNote(html, href) {
    popBody.innerHTML = html; popHref = href;
    try { linkify(popBody); } catch (e) {}
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
    if (popOpen() && pop.contains(e.target)) {
      var u = e.target.closest && e.target.closest('#notepop a[data-url]');
      if (u && !noteSelection()) { e.preventDefault(); bridge.link(u.getAttribute('data-url')); }
      return;
    }
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
      if (!pop.contains(e.target) && !bar.contains(e.target) && dragTurn === null) closePop();
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
    if (target.closest && target.closest('#hlstrip')) return;
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

  /* 102: a mouse. The WebView gives a mouse no touch events, only pointer/mouse ones, so a click
     is judged here with the same zones as a finger tap (edges 30% turn, middle or top strip bring
     the bars). A drag selects text (no page turn). Fingers keep the touch path above. */
  var mx = 0, my = 0, mdown = false;
  document.addEventListener('pointerdown', function (e) {
    if (e.pointerType !== 'mouse' || e.button !== 0) return;
    mdown = true; mx = e.clientX; my = e.clientY;
  }, true);
  document.addEventListener('pointerup', function (e) {
    if (e.pointerType !== 'mouse' || e.button !== 0 || !mdown) return;
    mdown = false;
    var target = e.target;
    if (popOpen()) {
      if (!pop.contains(target) && !bar.contains(target)) closePop();
      return;
    }
    if (target.closest && (target.closest('#hlbar') || target.closest('#hlstrip') || target.closest('#chapnav'))) return;
    if (Math.abs(e.clientX - mx) > 6 || Math.abs(e.clientY - my) > 6) return;   /* dragged: selecting text */
    if (currentSelection()) return;
    if (my < Math.max(padTop, 44)) { bridge.tap(); return; }
    var mark = target.closest && target.closest('mark.hl');
    if (mark) { bridge.tapHighlight(mark.getAttribute('data-hl')); return; }
    if (target.closest && target.closest('a')) return;
    turnY = Math.max(0, Math.min(1, my / window.innerHeight));
    var x = e.clientX / W();
    if (paged && x < 0.3) window.Reader.prev();
    else if (paged && x > 0.7) window.Reader.next();
    else bridge.tap();
  });

  /* 102: the mouse wheel — book view turns one page per wheel move (a trackpad's long fling
     counts once: it must rest 250ms before the next turn); scroll view scrolls. Inside the note
     popup the popup scrolls by itself. */
  var wheelLast = 0;
  document.addEventListener('wheel', function (e) {
    if (popOpen() && pop.contains(e.target)) return;
    var d = Math.abs(e.deltaY) >= Math.abs(e.deltaX) ? e.deltaY : e.deltaX;
    var unit = e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? window.innerHeight : 1;
    e.preventDefault();
    if (!paged) { window.scrollBy(0, e.deltaY * unit); return; }
    var now = Date.now(), idle = now - wheelLast;
    wheelLast = now;
    if (idle < 250 || Math.abs(d) < 1) return;
    turnY = 0.5;
    if (d > 0) window.Reader.next(); else window.Reader.prev();
  }, { passive: false });

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
  /* 94: a book that sets its sub-headings or list items smaller than the text (▶ 소제목, ⑥ …):
     blocks at 75~95% of the text's size go up to it, whatever their length (#2.10: an 80-letter
     limit left the long ▶ items small). Smaller still is fine print (copyright, sources) and stays;
     so do notes, tables, captions, small and ruby. */
  (function liftHeads() {
    if (!CFG.liftHeads) return;
    var base = parseFloat(getComputedStyle(content).fontSize) || 16;
    var bs = content.querySelectorAll('h1, h2, h3, h4, h5, h6, p, div, li, dt');
    for (var i = 0; i < bs.length; i++) {
      var b = bs[i];
      if (b.closest('aside, table, figure, figcaption, small, sup, sub, rt, nav, [epub\\:type~="footnote"], [epub\\:type~="endnote"], .footnote, .note')) continue;
      if (b.querySelector('p, div, li, table, img, svg')) continue;
      if (!(b.textContent || '').trim()) continue;
      var fs = parseFloat(getComputedStyle(b).fontSize);
      if (fs && fs < base * 0.95 && fs >= base * 0.75) b.style.setProperty('font-size', base + 'px', 'important');
      /* or the small size sits on a span inside the line */
      var ins = b.querySelectorAll('span, b, strong, em, i, font');
      for (var k = 0; k < ins.length; k++) {
        if (ins[k].closest('sup, sub, rt, small')) continue;
        var f2 = parseFloat(getComputedStyle(ins[k]).fontSize);
        if (f2 && f2 < base * 0.95 && f2 >= base * 0.75) ins[k].style.setProperty('font-size', base + 'px', 'important');
      }
    }
  })();
  /* 101: a box of text floated to one side (a pull quote: "선택하라. 지금 필요한 건 …") makes the
     page look like two columns on a phone. Text boxes come out of the float and sit full width
     between the paragraphs, their lines and borders kept; floated pictures stay as they are. */
  (function unfloat() {
    var els = content.querySelectorAll('div, aside, p, blockquote, section, table, span');
    for (var i = 0; i < els.length; i++) {
      var e = els[i], cs = getComputedStyle(e);
      if (cs.cssFloat === 'none' || !cs.cssFloat) continue;
      if (e.querySelector('img, svg, image, video')) continue;
      if (!(e.textContent || '').trim()) continue;
      e.style.setProperty('float', 'none', 'important');
      e.style.setProperty('width', 'auto', 'important');
      e.style.setProperty('max-width', '100%', 'important');
      e.style.setProperty('margin-left', '0', 'important');
      e.style.setProperty('margin-right', '0', 'important');
      if (cs.display === 'inline') e.style.setProperty('display', 'block', 'important');
    }
  })();
  layout(CFG.start);
  /* the app may go on from here (read aloud continues into this chapter) */
  setTimeout(function () { if (bridge.ready) bridge.ready(); }, 250);
  for (var j = 0; j < imgs.length; j++) {
    if (!imgs[j].complete) imgs[j].addEventListener('load', function () { layout(); });
  }
  window.addEventListener('load', function () { layout(); });
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(function () { layout(); });
})();
