/* Baca PDF — jembatan antara aplikasi Android (Kotlin) dan PDF.js viewer.
 * Dimuat sebagai script biasa SEBELUM viewer.mjs (module), jadi listener
 * "webviewerloaded" sudah terpasang saat viewer mulai. */
'use strict';
(function () {
  const N = window.Android || {
    // stub agar bisa diuji di browser biasa
    onReady() {}, onDoc() {}, onPage() {}, onViewArea() {}, onModified() {}, onSaveChunk() {}, onSaveDone() {}, onSaveError() {},
    onFind() {}, onTap() {}, onError() {}, onResult() {}, onMode() {}, onSelection() {}, onPassword() {},
  };
  const T = { NONE: 0, FREETEXT: 3, HIGHLIGHT: 9, STAMP: 13, INK: 15 };
  const P = { FREETEXT_SIZE: 11, FREETEXT_COLOR: 12, INK_COLOR: 21, INK_THICKNESS: 22, INK_OPACITY: 23, HIGHLIGHT_COLOR: 31, HIGHLIGHT_THICKNESS: 32, HIGHLIGHT_FREE: 33, HIGHLIGHT_SHOW_ALL: 34 };
  const COLORS = { kuning: '#FFFF98', hijau: '#53FFBC', biru: '#80EBFF', pink: '#FFCBE6', merah: '#FF4F5F' };
  let app = null, ui = null, pendingHash = null, docKey = '', curMode = 0;
  const S = { modified: false, saving: false };

  document.addEventListener('webviewerloaded', () => {
    const O = window.PDFViewerApplicationOptions;
    O.set('disableHistory', true);          // halaman terakhir disimpan oleh aplikasi
    O.set('disablePreferences', true);
    O.set('enableScripting', false);
    O.set('disableAutoFetch', false);
    O.set('disableRange', true);
    O.set('disableStream', false);
    O.set('defaultUrl', '');
    O.set('sidebarViewOnLoad', 0);
    O.set('viewOnLoad', 1);
    O.set('defaultZoomValue', 'page-width');
    O.set('highlightEditorColors', 'yellow=#FFFF98,green=#53FFBC,blue=#80EBFF,pink=#FFCBE6,red=#FF4F5F');
    O.set('enableHighlightFloatingButton', false);
    O.set('supportsPinchToZoom', true);
    O.set('annotationEditorMode', 0);
    O.set('enablePermissions', false);
    O.set('pdfBugEnabled', false);
    O.set('maxCanvasPixels', Math.min(16777216, Math.round(screen.width * screen.height * devicePixelRatio * devicePixelRatio * 4)));
    try { O.set('enableComment', true); } catch (_) {}
    try { O.set('enableUpdatedAddImage', true); } catch (_) {}
  }, { once: true });

  function boot() {
    app = window.PDFViewerApplication;
    if (!app || !app.initializedPromise) return setTimeout(boot, 30);
    app.initializedPromise.then(() => {
      const bus = app.eventBus;
      bus.on('annotationeditoruimanager', ({ uiManager }) => { ui = uiManager; });
      bus.on('documentinit', () => {
        const doc = app.pdfDocument;
        doc.annotationStorage.onSetModified = () => { S.modified = true; N.onModified(true); };
        doc.annotationStorage.onResetModified = () => { S.modified = false; N.onModified(false); };
      });
      bus.on('pagesinit', () => {
        if (pendingHash) { try { app.pdfLinkService.setHash(pendingHash); } catch (_) {} pendingHash = null; }
        else app.pdfViewer.currentScaleValue = 'page-width';
        sendDoc();
      });
      bus.on('pagechanging', e => N.onPage(e.pageNumber, app.pagesCount));
      let vaTimer = 0;
      bus.on('updateviewarea', e => {
        clearTimeout(vaTimer);
        const loc = e.location; if (!loc) return;
        vaTimer = setTimeout(() => N.onViewArea(loc.pageNumber, loc.pdfOpenParams.replace(/^#/, ''), String(loc.scale)), 250);
      });
      bus.on('updatefindmatchescount', e => N.onFind(e.matchesCount.current, e.matchesCount.total, -1));
      bus.on('updatefindcontrolstate', e => N.onFind(e.matchesCount ? e.matchesCount.current : 0, e.matchesCount ? e.matchesCount.total : 0, e.state));
      bus.on('annotationeditormodechanged', e => { curMode = e.mode; N.onMode(e.mode); updateTouchAction(); });
      for (const ev of ['scalechanging', 'pagechanging', 'pagerendered', 'rotationchanging', 'spreadmodechanged']) bus.on(ev, () => setTimeout(updateTouchAction, 30));
      window.addEventListener('resize', () => setTimeout(updateTouchAction, 120));
      bus.on('documenterror', e => N.onError(String(e.message || e.reason || 'Gagal membuka PDF')));
      // kata sandi: pakai dialog PDF.js, beri tahu aplikasi agar bar tampil
      const origPw = app.passwordPrompt && app.passwordPrompt.open && app.passwordPrompt.open.bind(app.passwordPrompt);
      if (origPw) app.passwordPrompt.open = (...a) => { N.onPassword(); return origPw(...a); };
      installTap();
      installSlide();
      installMarker();
      installSelectionWatch();
      N.onReady();
    });
  }
  boot();

  async function sendDoc() {
    const doc = app.pdfDocument; if (!doc) return;
    let title = '';
    try { const md = await doc.getMetadata(); title = (md.metadata && md.metadata.get('dc:title')) || (md.info && md.info.Title) || ''; } catch (_) {}
    N.onDoc(JSON.stringify({ pages: doc.numPages, title, fingerprint: (doc.fingerprints || [])[0] || '', page: app.page }));
  }

  // ---------- ketuk layar (tampil/sembunyi bar) ----------
  function installTap() {
    const c = document.getElementById('viewerContainer');
    let down = null, moved = false;
    c.addEventListener('pointerdown', e => { down = { x: e.clientX, y: e.clientY, t: Date.now(), n: e.isPrimary }; moved = false; }, true);
    c.addEventListener('pointermove', e => { if (down && Math.hypot(e.clientX - down.x, e.clientY - down.y) > 12) moved = true; }, true);
    c.addEventListener('pointerup', e => {
      if (!down || moved || Date.now() - down.t > 350) { down = null; return; }
      down = null;
      if (curMode !== T.NONE) return;
      const t = e.target;
      if (t.closest && (t.closest('a') || t.closest('.annotationEditorLayer .selectedEditor') || t.closest('.editToolbar') || t.closest('.popup') || t.closest('.annotationLayer section:not(.linkAnnotation)') || t.closest('.highlightEditor'))) return;
      setTimeout(() => {
        const sel = document.getSelection();
        if (sel && !sel.isCollapsed) return;
        const w = window.innerWidth, x = e.clientX;
        N.onTap(x < w * 0.18 ? 'left' : x > w * 0.82 ? 'right' : 'center');
      }, 180);
    }, true);
  }
  let lastRange = null, lastRangeAt = 0;
  // ---------- mode slide: geser per halaman dengan animasi ----------
  const SL = { on: false, dir: 'h', anim: true, busy: false };
  const $c = () => document.getElementById('viewerContainer');
  const $v = () => document.getElementById('viewer');
  const tf = px => SL.dir === 'h' ? `translate3d(${px}px,0,0)` : `translate3d(0,${px}px,0)`;
  function slideFits() {
    const c = $c();
    return SL.dir === 'h' ? c.scrollWidth <= c.clientWidth + 2 : c.scrollHeight <= c.clientHeight + 2;
  }
  function updateTouchAction() {
    const c = $c(); if (!c) return;
    // halaman pas di layar → semua geseran ditangani slide; bila di-zoom → geser biasa dulu
    c.style.touchAction = SL.on && curMode === 0 && slideFits() ? 'none' : '';
  }
  function canGo(fwd) { return fwd ? app.page < app.pagesCount : app.page > 1; }
  function snap() {
    const v = $v();
    v.style.transition = 'transform .2s ease-out, opacity .2s';
    v.style.transform = tf(0); v.style.opacity = '';
    setTimeout(() => { if (!SL.busy) { v.style.transition = ''; v.style.transform = ''; } }, 220);
  }
  function flip(fwd) {
    if (SL.busy) return;
    if (!canGo(fwd)) { bump(fwd); return; }
    const go = () => fwd ? app.pdfViewer.nextPage() : app.pdfViewer.previousPage();
    if (!SL.anim) { go(); return; }
    const v = $v(), s = SL.dir === 'h' ? $c().clientWidth : $c().clientHeight;
    SL.busy = true;
    v.style.transition = 'transform .17s ease-in, opacity .17s';
    v.style.transform = tf(fwd ? -s : s); v.style.opacity = '0.25';
    setTimeout(() => {
      go();
      v.style.transition = 'none';
      v.style.transform = tf(fwd ? s * 0.4 : -s * 0.4); v.style.opacity = '0.25';
      void v.offsetWidth;
      requestAnimationFrame(() => {
        v.style.transition = 'transform .22s cubic-bezier(.2,.8,.3,1), opacity .22s';
        v.style.transform = tf(0); v.style.opacity = '1';
        setTimeout(() => { SL.busy = false; v.style.transition = ''; v.style.transform = ''; v.style.opacity = ''; updateTouchAction(); }, 240);
      });
    }, 170);
  }
  /** pantulan kecil di halaman pertama/terakhir */
  function bump(fwd) {
    if (!SL.anim) return;
    const v = $v();
    v.style.transition = 'transform .12s ease-out'; v.style.transform = tf(fwd ? -28 : 28);
    setTimeout(snap, 120);
  }
  function installSlide() {
    const c = $c(), v = $v();
    let st = null;
    c.addEventListener('touchstart', e => {
      st = null;
      if (!SL.on || SL.busy || curMode !== 0 || e.touches.length !== 1) return;
      const t = e.touches[0];
      const h = SL.dir === 'h';
      st = {
        x: t.clientX, y: t.clientY, t: Date.now(), lock: null, d: 0,
        atStart: h ? c.scrollLeft <= 1 : c.scrollTop <= 1,
        atEnd: h ? c.scrollLeft + c.clientWidth >= c.scrollWidth - 1 : c.scrollTop + c.clientHeight >= c.scrollHeight - 1,
      };
    }, { passive: true });
    c.addEventListener('touchmove', e => {
      if (!st) return;
      if (e.touches.length !== 1) { if (st.lock) snap(); st = null; return; }
      const t = e.touches[0];
      const dx = t.clientX - st.x, dy = t.clientY - st.y;
      const main = SL.dir === 'h' ? dx : dy, cross = SL.dir === 'h' ? dy : dx;
      if (st.lock === null) {
        if (Math.abs(main) < 10 && Math.abs(cross) < 10) return;
        const sel = document.getSelection();
        if (sel && !sel.isCollapsed) { st = null; return; }            // sedang memilih teks
        if (Math.abs(main) > Math.abs(cross) * 1.2 && ((main < 0 && st.atEnd) || (main > 0 && st.atStart))) st.lock = true;
        else { st = null; return; }                                        // geser biasa (halaman di-zoom)
      }
      if (e.cancelable) e.preventDefault();
      st.d = canGo(main < 0) ? main : main * 0.25;
      v.style.transition = 'none';
      v.style.transform = tf(st.d);
    }, { passive: false });
    c.addEventListener('touchend', () => {
      if (!st || !st.lock) { st = null; return; }
      const d = st.d, dt = Math.max(1, Date.now() - st.t);
      const s = SL.dir === 'h' ? c.clientWidth : c.clientHeight;
      const fwd = d < 0;
      st = null;
      if (canGo(fwd) && (Math.abs(d) > s * 0.2 || (Math.abs(d) / dt > 0.45 && Math.abs(d) > 30))) flip(fwd);
      else snap();
    });
    c.addEventListener('touchcancel', () => { if (st && st.lock) snap(); st = null; });
  }

  // ---------- stabilo di atas teks (layar sentuh) ----------
  // PDF.js: di mode highlight, menekan tulisan menunggu seleksi teks ala mouse, yang tidak terjadi
  // dengan jari, jadi tidak ada apa-apa. Di sini sapuan jari di atas teks diubah jadi seleksi teks
  // (mengikuti baris), lalu di-highlight. Mulai dari area kosong tetap stabilo bebas bawaan PDF.js.
  function caretAt(x, y) {
    if (document.caretRangeFromPoint) {
      const r = document.caretRangeFromPoint(x, y);
      return r ? { node: r.startContainer, off: r.startOffset } : null;
    }
    if (document.caretPositionFromPoint) {
      const p = document.caretPositionFromPoint(x, y);
      return p ? { node: p.offsetNode, off: p.offset } : null;
    }
    return null;
  }
  function inLayer(c, tl) { return c && c.node && tl.contains(c.node) && c.node.nodeType === 3; }
  /** caret di awal/akhir kata pada span (bila titik tidak tepat di teks) */
  function edgeCaret(span, end) {
    const t = span.firstChild; if (!t || t.nodeType !== 3) return null;
    return { node: t, off: end ? t.length : 0 };
  }
  const MK = { active: false };
  /** seleksi dari a ke b, dilebarkan ke batas kata */
  function selectWords(a, b) {
    const sel = document.getSelection();
    try { sel.setBaseAndExtent(a.node, a.off, b.node, b.off); } catch (_) { return; }
    if (sel.isCollapsed || !sel.rangeCount) return;
    const r = sel.getRangeAt(0);
    let s = r.startContainer, so = r.startOffset, e = r.endContainer, eo = r.endOffset;
    if (s.nodeType === 3) while (so > 0 && /\S/.test(s.data[so - 1])) so--;
    if (e.nodeType === 3) while (eo < e.length && /\S/.test(e.data[eo])) eo++;
    try { sel.setBaseAndExtent(s, so, e, eo); } catch (_) {}
  }
  function installMarker() {
    document.addEventListener('pointerdown', e => {
      if (curMode !== T.HIGHLIGHT || e.pointerType === 'mouse' || !e.isPrimary || MK.active) return;
      const span = e.target && e.target.closest && e.target.closest('.textLayer span');
      if (!span || !span.textContent.trim()) return;
      const tl = span.closest('.textLayer');
      e.preventDefault(); e.stopImmediatePropagation();
      let a = caretAt(e.clientX, e.clientY);
      if (!inLayer(a, tl)) a = edgeCaret(span, false);
      if (!a) return;
      Object.assign(MK, { active: true, id: e.pointerId, tl, a, b: a, moved: false, x: e.clientX, y: e.clientY });
      const sel = document.getSelection(); sel.removeAllRanges();
    }, true);
    document.addEventListener('pointermove', e => {
      if (!MK.active || e.pointerId !== MK.id) return;
      e.preventDefault(); e.stopImmediatePropagation();
      if (!MK.moved && Math.hypot(e.clientX - MK.x, e.clientY - MK.y) < 6) return;
      MK.moved = true;
      let b = caretAt(e.clientX, e.clientY);
      if (!inLayer(b, MK.tl)) {
        const sp = document.elementFromPoint(e.clientX, e.clientY);
        const s2 = sp && sp.closest && sp.closest('.textLayer span');
        b = s2 && MK.tl.contains(s2) ? edgeCaret(s2, e.clientX > MK.x) : null;
      }
      if (!b) return;
      MK.b = b;
      selectWords(MK.a, b);
    }, true);
    const end = e => {
      if (!MK.active || e.pointerId !== MK.id) return;
      e.preventDefault(); e.stopImmediatePropagation();
      MK.active = false;
      const sel = document.getSelection();
      if (e.type === 'pointercancel' || !MK.moved || !sel || sel.isCollapsed || !sel.toString().trim()) { sel && sel.removeAllRanges(); return; }
      lastRange = null;
      try { ui && ui.highlightSelection('main_toolbar'); } catch (err) { N.onError('Gagal stabilo: ' + err.message); }
    };
    document.addEventListener('pointerup', end, true);
    document.addEventListener('pointercancel', end, true);
    // jari kedua (cubit zoom) membatalkan sapuan
    document.addEventListener('touchstart', e => { if (MK.active && e.touches.length > 1) { MK.active = false; document.getSelection().removeAllRanges(); } }, true);
  }

  function installSelectionWatch() {
    let last = false;
    document.addEventListener('selectionchange', () => {
      const sel = document.getSelection();
      const has = !!(sel && !sel.isCollapsed && sel.toString().trim());
      if (has && sel.rangeCount && sel.anchorNode && sel.anchorNode.parentElement && sel.anchorNode.parentElement.closest('.textLayer')) {
        lastRange = sel.getRangeAt(0).cloneRange(); lastRangeAt = Date.now();
      }
      if (has !== last) { last = has; N.onSelection(has); }
    });
  }
  /** pulihkan seleksi terakhir (mis. setelah menu/dialog Android menutupnya) */
  function ensureSelection() {
    const sel = document.getSelection();
    if (sel && !sel.isCollapsed) return true;
    if (!lastRange || Date.now() - lastRangeAt > 120000) return false;
    sel.removeAllRanges(); sel.addRange(lastRange);
    return !sel.isCollapsed;
  }

  function done(id, v) { N.onResult(id, JSON.stringify(v === undefined ? null : v)); }
  function hexToRgb(h) { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; }
  function rgbToHex(c) { return '#' + Array.from(c).map(v => (+v).toString(16).padStart(2, '0')).join('').toUpperCase(); }

  // ---------- teks di bawah kotak highlight ----------
  const textCache = new Map();
  async function pageText(page) {
    if (textCache.has(page.pageNumber)) return textCache.get(page.pageNumber);
    const tc = await page.getTextContent();
    const items = tc.items.filter(i => i.str).map(i => {
      const [a, b, , d, e, f] = i.transform;
      const h = i.height || Math.hypot(b, d) || Math.abs(d) || 10;
      return { s: i.str, x: e, y: f, w: i.width, h, vert: Math.abs(b) > Math.abs(a) };
    });
    textCache.set(page.pageNumber, items);
    return items;
  }
  function quadsOf(q) {
    const out = [];
    for (let i = 0; i + 7 < q.length; i += 8) {
      const xs = [q[i], q[i + 2], q[i + 4], q[i + 6]], ys = [q[i + 1], q[i + 3], q[i + 5], q[i + 7]];
      out.push({ x0: Math.min(...xs), x1: Math.max(...xs), y0: Math.min(...ys), y1: Math.max(...ys) });
    }
    return out;
  }
  function textUnder(items, quads) {
    // gabungkan rentang karakter per item dari semua kotak (kotak bisa tumpang tindih)
    const hit = new Map();
    for (const q of quads) {
      for (const it of items) {
        if (it.vert) continue;
        const iy0 = it.y - it.h * 0.25, iy1 = it.y + it.h * 0.85;
        const ov = Math.min(iy1, q.y1) - Math.max(iy0, q.y0);
        if (ov < Math.min(it.h, q.y1 - q.y0) * 0.45) continue;
        const ix1 = it.x + it.w;
        if (ix1 < q.x0 + 0.5 || it.x > q.x1 - 0.5) continue;
        const n = it.s.length || 1;
        const a = it.w > 0 ? Math.max(0, Math.round((Math.max(q.x0, it.x) - it.x) / it.w * n)) : 0;
        const b = it.w > 0 ? Math.min(n, Math.round((Math.min(q.x1, ix1) - it.x) / it.w * n)) : n;
        const h = hit.get(it);
        if (h) { h[0] = Math.min(h[0], a); h[1] = Math.max(h[1], b); } else hit.set(it, [a, b]);
      }
    }
    const list = [...hit.entries()].map(([it, [a, b]]) => ({ x: it.x, y: it.y, h: it.h, w: it.w, s: it.s.slice(a, Math.max(a, b)) }));
    // urutkan per baris (atas → bawah), lalu kiri → kanan
    list.sort((m, n) => (Math.abs(m.y - n.y) < Math.min(m.h, n.h) * 0.5 ? m.x - n.x : n.y - m.y));
    let out = '', prev = null;
    for (const l of list) {
      if (prev && (Math.abs(prev.y - l.y) >= Math.min(prev.h, l.h) * 0.5 || l.x - (prev.x + prev.w) > l.h * 0.15)) out += ' ';
      out += l.s; prev = l;
    }
    return out.replace(/\s+/g, ' ').trim();
  }

  // ---------- API untuk Kotlin ----------
  window.APP = {
    open(url, hash, key) {
      pendingHash = hash || null; docKey = key || ''; textCache.clear();
      S.modified = false;
      app.initializedPromise.then(() => app.open({ url }).catch(e => N.onError(String(e && e.message || e))));
    },
    goto(n) {
      app.page = Math.max(1, Math.min(app.pagesCount, n | 0));
      // jangan sampai atas halaman tertutup bar atas aplikasi
      const pad = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--top-pad')) || 0;
      const c = document.getElementById('viewerContainer');
      if (pad && app.pdfViewer.scrollMode === 0) requestAnimationFrame(() => { if (c.scrollTop > 0) c.scrollTop = Math.max(0, c.scrollTop - pad - 6); });
    },
    next() { if (SL.on) flip(true); else app.pdfViewer.nextPage(); },
    prev() { if (SL.on) flip(false); else app.pdfViewer.previousPage(); },
    /** tata letak: scroll 0 vertikal, 1 horizontal, 3 slide; spread 0/1; dir 'h'|'v' */
    layout(scroll, spread, dir, anim) {
      const V = app.pdfViewer, was = SL.on;
      SL.on = scroll === 3; SL.dir = dir === 'v' ? 'v' : 'h'; SL.anim = anim !== false;
      document.documentElement.classList.toggle('slideMode', SL.on);
      if (V.scrollMode !== scroll) V.scrollMode = scroll;
      if (V.spreadMode !== spread) V.spreadMode = spread;
      if (SL.on && !was) V.currentScaleValue = 'page-fit';
      else if (!SL.on && was) V.currentScaleValue = 'page-width';
      else if (SL.on) V.currentScaleValue = V.currentScaleValue;  // hitung ulang setelah ganti spread
      setTimeout(updateTouchAction, 150);
    },
    slideState() { return { on: SL.on, dir: SL.dir, touch: $c().style.touchAction, fits: slideFits(), busy: SL.busy }; },
    zoom(v) {
      const V = app.pdfViewer;
      if (v === '+') V.increaseScale(); else if (v === '-') V.decreaseScale(); else V.currentScaleValue = v;
    },
    // 0 vertikal, 1 horizontal, 2 berjajar, 3 per halaman
    scroll(m) { app.pdfViewer.scrollMode = m; },
    spread(m) { app.pdfViewer.spreadMode = m; },
    rotate(d) { app.pdfViewer.pagesRotation = (app.pdfViewer.pagesRotation + d + 360) % 360; },
    theme(t) { document.documentElement.dataset.theme = t; },
    find(q, again, prev, opts) {
      opts = opts || {};
      app.eventBus.dispatch('find', { source: null, type: again ? 'again' : '', query: q, caseSensitive: !!opts.cs, entireWord: !!opts.word, highlightAll: true, findPrevious: !!prev, matchDiacritics: false });
    },
    findClose() { app.eventBus.dispatch('findbarclose', { source: null }); app.findController && app.findController._reset && 0; },
    /** highlight teks yang sedang diseleksi */
    highlight(color, withNote) {
      if (!ui) return false;
      if (!ensureSelection()) return false;
      try { ui.unselectAll(); } catch (_) {}
      if (!ensureSelection()) return false;
      lastRange = null;
      ui.updateParams(P.HIGHLIGHT_COLOR, color || COLORS.kuning);
      try { app.eventBus.dispatch('switchannotationeditorparams', { source: null, type: P.HIGHLIGHT_COLOR, value: color }); } catch (_) {}
      if (withNote && ui.commentSelection) ui.commentSelection('context_menu'); else ui.highlightSelection('context_menu');
      setTimeout(() => this.mode(0), 60);
      return true;
    },
    selectionText() { ensureSelection(); const s = document.getSelection(); return s ? s.toString().replace(/\s+\n/g, '\n') : ''; },
    clearSelection() { lastRange = null; const s = document.getSelection(); if (s) s.removeAllRanges(); },
    /** mode alat: 0 baca, 9 highlight bebas, 15 pena, 3 teks */
    mode(m) {
      if (!app) return;
      app.eventBus.dispatch('switchannotationeditormode', { source: null, mode: m });
      if (m === T.HIGHLIGHT) setTimeout(() => ui && ui.updateParams(P.HIGHLIGHT_FREE, true), 50);
    },
    color(kind, hex) {
      if (!ui) return;
      const type = kind === 'ink' ? P.INK_COLOR : kind === 'text' ? P.FREETEXT_COLOR : P.HIGHLIGHT_COLOR;
      // tanpa ini, warna baru malah mengubah coretan terakhir yang masih terpilih
      try { ui.unselectAll(); } catch (_) {}
      ui.updateParams(type, hex);
    },
    thickness(v) { ui && ui.updateParams(P.INK_THICKNESS, v); },
    undo() { ui && ui.undo(); },
    redo() { ui && ui.redo(); },
    deleteSelected() { ui && ui.delete(); },
    isModified() { return !!S.modified; },

    async save(id, commit) {
      if (S.saving) return N.onSaveError(id, 'sedang menyimpan');
      S.saving = true;
      try {
        if (commit && ui) { try { ui.commitOrRemove && ui.commitOrRemove(); } catch (_) {} }
        const data = await app.pdfDocument.saveDocument();
        const CH = 512 * 1024;
        for (let i = 0; i < data.length; i += CH) {
          const sub = data.subarray(i, Math.min(data.length, i + CH));
          let bin = '';
          for (let j = 0; j < sub.length; j += 0x8000) bin += String.fromCharCode.apply(null, sub.subarray(j, j + 0x8000));
          N.onSaveChunk(id, btoa(bin), i === 0);
        }
        N.onSaveDone(id, data.length);
        app.pdfDocument.annotationStorage.resetModified();
      } catch (e) { N.onSaveError(id, String(e && e.message || e)); }
      finally { S.saving = false; }
    },

    async outline(id) {
      const doc = app.pdfDocument; if (!doc) return done(id, []);
      const out = [];
      const ol = await doc.getOutline().catch(() => null);
      async function pageOf(dest) {
        try {
          if (typeof dest === 'string') dest = await doc.getDestination(dest);
          if (!Array.isArray(dest)) return 0;
          const ref = dest[0];
          if (typeof ref === 'number') return ref + 1;
          return (await doc.getPageIndex(ref)) + 1;
        } catch (_) { return 0; }
      }
      async function walk(items, depth) {
        for (const it of items || []) {
          out.push({ t: it.title, p: await pageOf(it.dest), d: depth });
          if (out.length > 3000) return;
          await walk(it.items, depth + 1);
        }
      }
      await walk(ol, 0);
      done(id, out);
    },

    /** semua highlight: yang tersimpan di file + yang baru dibuat (belum disimpan) */
    async highlights(id) {
      const doc = app.pdfDocument; if (!doc) return done(id, []);
      const res = [];
      const edited = new Map(), fresh = [];
      try {
        for (const [, v] of doc.annotationStorage.serializable.map || []) {
          if (!v) continue;
          if (v.id && (v.deleted || v.annotationType === T.HIGHLIGHT)) edited.set(v.id, v);
          else if (v.annotationType === T.HIGHLIGHT) fresh.push(v);
        }
      } catch (_) {}
      for (let p = 1; p <= doc.numPages; p++) {
        const page = await doc.getPage(p);
        const anns = (await page.getAnnotations()).filter(a => a.annotationType === 9 || a.annotationType === 10 || a.annotationType === 11);
        const mine = fresh.filter(f => f.pageIndex === p - 1);
        if (!anns.length && !mine.length) continue;
        const items = await pageText(page);
        for (const a of anns) {
          const e = edited.get(a.id);
          if (e && e.deleted) continue;
          const quads = a.quadPoints ? quadsOf(a.quadPoints) : [{ x0: a.rect[0], y0: a.rect[1], x1: a.rect[2], y1: a.rect[3] }];
          res.push({ p, y: -quads[0].y1, c: rgbToHex(e ? e.color : (a.color || [255, 255, 0])), s: textUnder(items, quads) || '(gambar/area)', n: (e && e.comment) || (a.contentsObj && a.contentsObj.str) || '', k: a.annotationType === 9 ? 'h' : a.annotationType === 10 ? 'u' : 's', saved: true });
        }
        for (const f of mine) {
          const quads = f.quadPoints ? quadsOf(f.quadPoints) : [{ x0: f.rect[0], y0: f.rect[1], x1: f.rect[2], y1: f.rect[3] }];
          const note = f.comment && (typeof f.comment === 'string' ? f.comment : f.comment.text) || '';
          res.push({ p, y: -quads[0].y1, c: rgbToHex(f.color), s: (f.quadPoints ? textUnder(items, quads) : '') || '(coretan highlight)', n: note, k: 'h', saved: false });
        }
      }
      res.sort((a, b) => a.p - b.p || a.y - b.y);
      done(id, res);
    },

    async pageTextOf(id, n) {
      const page = await app.pdfDocument.getPage(n);
      const tc = await page.getTextContent();
      done(id, tc.items.map(i => i.str + (i.hasEOL ? '\n' : '')).join(''));
    },
    info(id) {
      done(id, { page: app.page, pages: app.pagesCount, scale: app.pdfViewer.currentScale, mode: curMode, modified: !!S.modified });
    },
  };
})();
