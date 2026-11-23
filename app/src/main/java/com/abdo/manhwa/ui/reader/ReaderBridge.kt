package com.abdo.manhwa.ui.reader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import java.io.File

/**
 * الجسر بين صفحة الـ WebView (JavaScript) والتطبيق (Kotlin).
 * كل الاستدعاءات تصل من خيط خلفي، لذلك نحوّلها إلى الخيط الرئيسي قبل لمس أي حالة.
 */
class ReaderJsBridge(
    private val onVisible: (Long) -> Unit,
    private val onProgress: (Long, Float) -> Unit,
    private val onFinished: (Long) -> Unit,
    private val onNeedNext: (Long) -> Unit,
    private val chapterPathProvider: (Long) -> String?
) {
    private val main = Handler(Looper.getMainLooper())

    /** الفصل الظاهر في منتصف الشاشة تغيّر */
    @JavascriptInterface
    fun visible(id: Long) { main.post { onVisible(id) } }

    /** نسبة التمرير داخل الفصل الظاهر (0..1) */
    @JavascriptInterface
    fun progress(id: Long, percent: Double) {
        main.post { onProgress(id, percent.toFloat().coerceIn(0f, 1f)) }
    }

    /** وصل القارئ إلى فاصل نهاية الفصل */
    @JavascriptInterface
    fun finished(id: Long) { main.post { onFinished(id) } }

    /** اقترب القارئ من نهاية آخر فصل محمّل: حمّل الفصل التالي مسبقاً */
    @JavascriptInterface
    fun needNext(lastId: Long) { main.post { onNeedNext(lastId) } }

    /** احتياطي: يقرأ ملف الفصل إذا فشل XMLHttpRequest على file:// في بعض الأجهزة */
    @JavascriptInterface
    fun readChapter(id: Long): String {
        val path = chapterPathProvider(id) ?: return ""
        return try { File(path).readText() } catch (e: Exception) { "" }
    }
}

/** سكربت القراءة المتصلة: يُحقن في صفحة الفصل الأول ويدمج الفصول التالية أسفلها. */
object ReaderRuntime {
    const val SCRIPT = """
(function () {
  'use strict';
  if (window.__abdoReady) return;
  window.__abdoReady = true;

  var B = window.AbdoReader;
  var KEEP_BEHIND = 2;
  var PRELOAD_SCREENS = 3;
  var STRIP = 'script,noscript,iframe,ins.adsbygoogle,[class*="adsbygoogle"],[id^="google_ads"],[data-ad-slot],[data-ad-client]';

  var chs = [];
  var tail = null;
  var noMore = false;
  var pending = false;
  var pendingSince = 0;
  var activeIdx = -1;
  var lastId = -1, lastP = -1, lastAt = 0;
  var trailTimer = 0;
  var userScrolled = false;
  var initAt = 0;
  var holdUntil = 0;
  var busy = {};

  function now() { return Date.now(); }
  function vh() { return window.innerHeight || document.documentElement.clientHeight; }
  function sy() { var d = document.scrollingElement || document.documentElement; return d.scrollTop; }
  function absTop(el) { return el.getBoundingClientRect().top + sy(); }

  function bVisible(id) { try { B.visible(id); } catch (e) {} }
  function bProgress(id, p) { try { B.progress(id, p); } catch (e) {} }
  function bFinished(id) { try { B.finished(id); } catch (e) {} }
  function bNeedNext(id) { try { B.needNext(id); } catch (e) {} }
  function bRead(id) { try { return B.readChapter(id); } catch (e) { return ''; } }

  function endOf(c) { return c.endEl || tail; }
  function geom(c) { return { top: absTop(c.marker), bottom: absTop(endOf(c)) }; }

  function timeText(secs) {
    var m = Math.floor(secs / 60), s = secs % 60;
    return 'قضيت ' + (m > 0 ? (m + ' د ' + s + ' ث') : 'أقل من دقيقة') + ' في هذا الفصل';
  }
  function setTime(c) { if (c && c.timeEl) c.timeEl.textContent = timeText(c.secs); }

  function injectStyle(accent) {
    if (document.getElementById('abdo-style')) return;
    var css =
      '.abdo-divider{box-sizing:border-box;display:block;width:100%;margin:0;padding:48px 16px 44px;background:#05080f;color:#fff;text-align:center;direction:rtl;font-family:system-ui,-apple-system,Roboto,"Noto Sans Arabic",sans-serif}' +
      '.abdo-flame{display:inline-block;color:ACCENT;filter:drop-shadow(0 0 14px ACCENT);animation:abdoFloat 2.4s ease-in-out infinite}' +
      '@keyframes abdoFloat{0%,100%{transform:translateY(0)}50%{transform:translateY(-6px)}}' +
      '.abdo-time{margin:14px 0 16px;font-size:13px;opacity:.7;line-height:1.5}' +
      '.abdo-pill{display:inline-block;padding:9px 18px;border-radius:999px;border:1px solid ACCENT;background:rgba(255,255,255,.04);font-size:14px;font-weight:600;color:ACCENT;line-height:1.4}' +
      '.abdo-pill b{font-weight:700;margin-right:6px}' +
      '.abdo-loading{box-sizing:border-box;width:100%;padding:28px 0;background:#05080f;color:rgba(255,255,255,.55);text-align:center;font-size:13px;direction:rtl;font-family:system-ui,sans-serif}';
    var st = document.createElement('style');
    st.id = 'abdo-style';
    st.textContent = css.replace(/ACCENT/g, accent || '#7C4DFF');
    (document.head || document.documentElement).appendChild(st);
  }

  function makeMarker(id) {
    var m = document.createElement('div');
    m.className = 'abdo-ch-start';
    m.setAttribute('data-abdo-ch', String(id));
    m.style.cssText = 'display:block;height:0;margin:0;padding:0;border:0;overflow:hidden;';
    return m;
  }

  function makeDivider(nextTitle) {
    var d = document.createElement('div');
    d.className = 'abdo-divider';
    d.setAttribute('dir', 'rtl');
    d.innerHTML =
      '<div class="abdo-flame"><svg viewBox="0 0 64 80" width="64" height="80" xmlns="http://www.w3.org/2000/svg">' +
      '<path d="M32 4C32 4 8 34 8 52a24 24 0 0 0 48 0C56 34 32 4 32 4z" fill="currentColor"/>' +
      '<ellipse cx="24" cy="30" rx="5" ry="9" fill="#fff" fill-opacity=".35" transform="rotate(18 24 30)"/>' +
      '<circle cx="24" cy="54" r="3.2" fill="#06121c"/><circle cx="40" cy="54" r="3.2" fill="#06121c"/>' +
      '<path d="M27 62q5 5 10 0" stroke="#06121c" stroke-width="2.4" fill="none" stroke-linecap="round"/></svg></div>' +
      '<div class="abdo-time"></div>' +
      '<div class="abdo-pill"><span class="abdo-pl"></span><b class="abdo-pt"></b></div>';
    if (nextTitle === null) {
      d.querySelector('.abdo-pl').textContent = 'وصلت إلى آخر فصل متوفر';
      d.querySelector('.abdo-pt').textContent = '';
    } else {
      d.querySelector('.abdo-pl').textContent = 'الفصل التالي \u00B7';
      d.querySelector('.abdo-pt').textContent = nextTitle;
    }
    return d;
  }

  function renderTail(loading) {
    if (!tail) return;
    if (loading) {
      tail.innerHTML = '<div class="abdo-loading">جارٍ تحميل الفصل التالي…</div>';
    } else {
      tail.innerHTML = '';
    }
  }

  function fixLazy(root) {
    if (!root || root.nodeType !== 1) return;
    var imgs = root.nodeName === 'IMG' ? [root] : root.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i];
      var ds = im.getAttribute('data-src') || im.getAttribute('data-lazy-src') || im.getAttribute('data-original');
      if (!ds) continue;
      var cur = im.getAttribute('src');
      if (!cur || (/^data:image\/(gif|svg)/.test(cur) && cur.length < 400)) im.setAttribute('src', ds);
    }
  }

  function buildNodes(doc, skipStyles) {
    var body = doc && doc.body;
    if (!body) return [];
    try {
      var kill = body.querySelectorAll(STRIP);
      for (var i = 0; i < kill.length; i++) {
        if (kill[i].parentNode) kill[i].parentNode.removeChild(kill[i]);
      }
    } catch (e) {}
    var out = [];
    for (var n = body.firstChild; n; n = n.nextSibling) {
      if (skipStyles && n.nodeType === 1 && (n.nodeName === 'STYLE' || n.nodeName === 'LINK' || n.nodeName === 'META')) continue;
      var imp = document.importNode(n, true);
      fixLazy(imp);
      out.push(imp);
    }
    return out;
  }

  function fetchDoc(url, id, cb) {
    var done = false;
    function parse(text) {
      var doc = null;
      try { doc = new DOMParser().parseFromString(text, 'text/html'); } catch (e) {}
      cb(doc);
    }
    function fallback() {
      if (done) return;
      done = true;
      var t = bRead(id);
      if (t) parse(t); else cb(null);
    }
    try {
      var x = new XMLHttpRequest();
      x.open('GET', url, true);
      x.responseType = 'text';
      x.onload = function () {
        if (done) return;
        if (x.responseText && x.responseText.length > 0) { done = true; parse(x.responseText); }
        else fallback();
      };
      x.onerror = fallback;
      x.send();
    } catch (e) { fallback(); }
  }

  function trim(c) {
    var g = geom(c);
    var hgt = Math.max(0, g.bottom - g.top);
    var end = endOf(c);
    var n = c.marker.nextSibling;
    while (n && n !== end) {
      var nx = n.nextSibling;
      var keep = n.nodeType === 1 && (n.nodeName === 'STYLE' || n.nodeName === 'LINK' || n.nodeName === 'SCRIPT' || n.nodeName === 'META');
      if (!keep) n.parentNode.removeChild(n);
      n = nx;
    }
    var ph = document.createElement('div');
    ph.className = 'abdo-ph';
    ph.style.cssText = 'display:block;width:100%;margin:0;padding:0;height:' + hgt + 'px;';
    end.parentNode.insertBefore(ph, end);
    c.ph = ph;
    c.trimmed = true;
  }

  function restore(c) {
    if (busy[c.id]) return;
    busy[c.id] = true;
    fetchDoc(c.url, c.id, function (doc) {
      busy[c.id] = false;
      if (!doc || !c.trimmed || !c.ph) return;
      var nodes = buildNodes(doc, true);
      var ph = c.ph;
      var parent = ph.parentNode;
      for (var k = 0; k < nodes.length; k++) parent.insertBefore(nodes[k], ph);
      parent.removeChild(ph);
      c.ph = null;
      c.trimmed = false;
    });
  }

  function manageMemory() {
    for (var i = 0; i < chs.length; i++) {
      var c = chs[i];
      if (i <= activeIdx - (KEEP_BEHIND + 1)) {
        if (!c.trimmed) trim(c);
      } else if (c.trimmed && i <= activeIdx + 1) {
        restore(c);
      }
    }
  }

  function requestNext() {
    pending = true;
    pendingSince = now();
    renderTail(true);
    bNeedNext(chs[chs.length - 1].id);
  }

  function update() {
    if (!chs.length || !tail) return;
    var t = now();
    var y = sy(), h = vh();
    var probe = y + h * 0.35;

    var idx = 0;
    for (var i = 0; i < chs.length; i++) {
      if (absTop(chs[i].marker) <= probe) idx = i; else break;
    }
    var c = chs[idx];
    if (idx !== activeIdx) {
      var first = (activeIdx === -1);
      activeIdx = idx;
      if (!first) bVisible(c.id);
      manageMemory();
    }

    if (t < holdUntil && !userScrolled) {
      clearTimeout(trailTimer);
      trailTimer = setTimeout(update, holdUntil - t + 20);
      return;
    }
    var g = geom(c);
    var denom = g.bottom - g.top - h;
    var p = denom <= 0 ? 0 : Math.min(1, Math.max(0, (y - g.top) / denom));

    for (var k = Math.max(0, idx - 1); k <= idx; k++) {
      var ck = chs[k];
      if (ck.finished) continue;
      var gk = (k === idx) ? g : geom(ck);
      if (y > 20 && t - initAt > 1500 && y + h >= gk.bottom - 2) {
        ck.finished = true;
        setTime(ck);
        bFinished(ck.id);
      }
    }

    if (c.id !== lastId) {
      lastId = c.id; lastP = p; lastAt = t;
      bProgress(c.id, p);
    } else if (Math.abs(p - lastP) >= 0.003) {
      if (t - lastAt >= 150) {
        lastP = p; lastAt = t;
        bProgress(c.id, p);
      } else {
        clearTimeout(trailTimer);
        trailTimer = setTimeout(update, 180);
      }
    }

    if (!noMore && !pending && idx === chs.length - 1) {
      var tailTop = absTop(tail);
      if (tailTop - (y + h) < h * PRELOAD_SCREENS) requestNext();
    }
  }

  var ticking = false;
  function onScroll() {
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(function () { ticking = false; update(); });
  }

  function tick() {
    if (pending && now() - pendingSince > 10000) {
      pending = false;
      renderTail(false);
    }
    if (document.hidden) return;
    var c = chs[activeIdx];
    if (c && !c.finished) { c.secs++; setTime(c); }
  }

  function seekTo(c, p) {
    var g = geom(c);
    var span = Math.max(0, g.bottom - g.top - vh());
    window.scrollTo(0, Math.floor(g.top + span * p));
  }

  function findIdx(id) {
    for (var i = 0; i < chs.length; i++) if (chs[i].id === id) return i;
    return -1;
  }

  window.__abdoInit = function (id, title, savedPercent, accent) {
    if (!document.body || tail) return;
    injectStyle(accent);
    tail = document.createElement('div');
    tail.id = 'abdo-tail';
    tail.style.cssText = 'display:block;width:100%;margin:0;padding:0;clear:both;';
    document.body.appendChild(tail);
    var m = makeMarker(id);
    document.body.insertBefore(m, document.body.firstChild);
    chs.push({ id: id, title: title, url: location.href, marker: m, endEl: null, secs: 0,
               finished: false, trimmed: false, ph: null, timeEl: null });
    initAt = now();
    holdUntil = initAt + (savedPercent > 0 ? 3000 : 600);

    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    window.addEventListener('touchstart', function () { userScrolled = true; }, { passive: true });
    window.addEventListener('wheel', function () { userScrolled = true; }, { passive: true });
    setInterval(tick, 1000);

    if (savedPercent > 0) {
      var doSeek = function () { if (!userScrolled) seekTo(chs[0], savedPercent); };
      setTimeout(doSeek, 250);
      setTimeout(doSeek, 1000);
      setTimeout(doSeek, 2200);
    }
    setTimeout(update, holdUntil - initAt + 20);
    update();
  };

  window.__abdoAppend = function (id, title, url) {
    if (!tail || findIdx(id) >= 0) { pending = false; renderTail(false); return; }
    var prev = chs[chs.length - 1];
    fetchDoc(url, id, function (doc) {
      if (!doc || !doc.body) { pending = false; renderTail(false); return; }
      if (findIdx(id) >= 0) return;
      var nodes = buildNodes(doc, false);
      var div = makeDivider(title);
      prev.endEl = div;
      prev.timeEl = div.querySelector('.abdo-time');
      setTime(prev);
      document.body.insertBefore(div, tail);
      var m = makeMarker(id);
      document.body.insertBefore(m, tail);
      for (var k = 0; k < nodes.length; k++) document.body.insertBefore(nodes[k], tail);
      chs.push({ id: id, title: title, url: url, marker: m, endEl: null, secs: 0,
                 finished: false, trimmed: false, ph: null, timeEl: null });
      pending = false;
      renderTail(false);
      onScroll();
    });
  };

  window.__abdoNoMore = function () {
    if (!tail || noMore) return;
    var prev = chs[chs.length - 1];
    if (prev.endEl) return;
    var div = makeDivider(null);
    prev.endEl = div;
    prev.timeEl = div.querySelector('.abdo-time');
    setTime(prev);
    document.body.insertBefore(div, tail);
    noMore = true;
    pending = false;
    renderTail(false);
    onScroll();
  };

  window.__abdoSeek = function (id, p) {
    var i = findIdx(id);
    if (i < 0 || chs[i].trimmed) return false;
    userScrolled = true;
    seekTo(chs[i], p);
    return true;
  };

  window.__abdoGoto = function (id) {
    var i = findIdx(id);
    if (i < 0 || chs[i].trimmed) return false;
    for (var j = 0; j < i; j++) chs[j].finished = true;
    userScrolled = true;
    window.scrollTo(0, Math.floor(absTop(chs[i].marker)));
    setTimeout(update, 60);
    return true;
  };
})();

"""
}
