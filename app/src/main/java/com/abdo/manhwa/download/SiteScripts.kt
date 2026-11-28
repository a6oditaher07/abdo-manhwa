package com.abdo.manhwa.download

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject
import org.json.JSONTokener

/** فصل مكتشف في صفحة المانهوا */
data class ScanChapter(val title: String, val url: String)

/** نتيجة فحص صفحة مانهوا: الاسم والغلاف والنبذة وقائمة الفصول */
data class ScanResult(
    val url: String,
    val title: String,
    val cover: String?,
    val description: String,
    val chapters: List<ScanChapter>,
    /** روابط صفحات الترقيم الحقيقية (إن كان الموقع يستخدم روابط بدل أزرار JavaScript) */
    val pageUrls: List<String>,
    /** تقرير تشخيصي نصي يمكن نسخه وإرساله لتحسين الاكتشاف */
    val diagnostics: String
) {
    companion object {
        /** يحوّل ناتج evaluateJavascript (نص JSON داخل نص مقتبس) إلى كائن، أو null إن لم يصلح */
        fun parse(raw: String?): ScanResult? {
            if (raw.isNullOrBlank() || raw == "null") return null
            return try {
                val json = (JSONTokener(raw).nextValue() as? String) ?: return null
                fromJson(json)
            } catch (e: Exception) {
                null
            }
        }

        /** يحوّل نص JSON مباشر (كما يصل من الجسر) إلى كائن */
        fun fromJson(json: String): ScanResult? {
            return try {
                val o = JSONObject(json)
                val arr = o.getJSONArray("chapters")
                val chapters = (0 until arr.length()).map {
                    val c = arr.getJSONObject(it)
                    ScanChapter(c.getString("t"), c.getString("u"))
                }
                val pages = o.optJSONArray("pageUrls")
                ScanResult(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    cover = o.optString("cover").ifBlank { null },
                    description = o.optString("desc"),
                    chapters = chapters,
                    pageUrls = if (pages == null) emptyList() else (0 until pages.length()).map { pages.getString(it) },
                    diagnostics = o.optJSONObject("diag")?.toString(2) ?: ""
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** نتيجة قراءة صور الفصل المفتوح حالياً في المتصفح (بدون تمرير الصفحة) */
data class QuickResult(
    val urls: List<String>,
    val container: String,
    /** عدد الصور في منطقة القراءة التي لم تظهر روابطها بعد (تحميل كسول لم يكتمل) */
    val emptySlots: Int,
    val page: String,
    val name: String,
    val chapter: String,
    val mangaUrl: String?,
    val mangaText: String
) {
    /** هل القائمة كاملة بما يكفي لاعتمادها مباشرة دون فحص الفصل في صفحة مخفية؟ */
    val isComplete: Boolean get() = urls.size >= 3 && emptySlots == 0

    val summary: String
        get() = "quick: images=${urls.size}, emptySlots=$emptySlots, container=$container, " +
            "name=$name, chapter=$chapter, mangaUrl=$mangaUrl, first=${urls.firstOrNull()}"

    companion object {
        fun parse(raw: String?): QuickResult? {
            if (raw.isNullOrBlank() || raw == "null") return null
            return try {
                val json = (JSONTokener(raw).nextValue() as? String) ?: return null
                val o = JSONObject(json)
                val arr = o.getJSONArray("urls")
                QuickResult(
                    urls = (0 until arr.length()).map { arr.getString(it) },
                    container = o.optString("container"),
                    emptySlots = o.optInt("emptySlots"),
                    page = o.optString("page"),
                    name = o.optString("name"),
                    chapter = o.optString("chapter"),
                    mangaUrl = o.optString("mangaUrl").ifBlank { null },
                    mangaText = o.optString("mangaText")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** جسر JavaScript لاستقبال نتيجة السكربتات التي تعمل في الصفحة المخفية */
class DlJsBridge(private val onResult: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun result(json: String) {
        main.post { onResult(json) }
    }
}

/** سكربتات تُحقن في صفحات الموقع لاكتشاف الفصول وجمع روابط الصور */
object SiteScripts {

    /** يفحص الصفحة: اسم المانهوا، الغلاف، النبذة، وقائمة روابط الفصول (يتجاهل الأعمال المشابهة). يُعيد نص JSON. */
    const val SCAN = """
(function () {
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return ''; } }
  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function meta(n) {
    var m = document.querySelector('meta[property="' + n + '"],meta[name="' + n + '"]');
    return m ? clean(m.getAttribute('content')) : '';
  }
  function safeDecode(s) { try { return decodeURIComponent(s); } catch (e) { return s; } }
  function depthOf(el) { var d = 0; while (el) { d++; el = el.parentElement; } return d; }
  function isVisible(el) { return !!(el.offsetWidth || el.offsetHeight || (el.getClientRects && el.getClientRects().length)); }
  function describe(el) {
    if (!el) return '';
    var c = (typeof el.className === 'string' && el.className) ? ('.' + el.className.trim().split(/\s+/).slice(0, 3).join('.')) : '';
    return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + c;
  }
  function pick(els) {
    var count = new Map();
    els.forEach(function (el) {
      var p = el.parentElement, d = 0;
      while (p && p !== document.body && d < 7) { count.set(p, (count.get(p) || 0) + 1); p = p.parentElement; d++; }
    });
    var max = 0;
    count.forEach(function (n) { if (n > max) max = n; });
    if (!max) return null;
    var best = null, bestDepth = -1;
    count.forEach(function (n, el) {
      if (n >= max * 0.85) { var dp = depthOf(el); if (dp > bestDepth) { bestDepth = dp; best = el; } }
    });
    return best;
  }

  var CH = /(الفصل|فصل|الحلقة|حلقة|chapter|chap|ch|episode|ep)\s*[.:#\-]?\s*(\d+(?:[.\-]\d+)?)/i;
  var HREFCH = /(?:chapter|chap|ch|فصل)[-_\/ ]*(\d+(?:[.\-]\d+)?)/i;

  function collectChapters() {
    var matched = [], metas = [];
    var anchors = document.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i], href = a.getAttribute('href') || '';
      if (!href || href.charAt(0) === '#' || /^(javascript|mailto|tel):/i.test(href)) continue;
      var u = abs(href);
      if (!u || u.split('#')[0] === location.href.split('#')[0]) continue;
      var text = clean(a.innerText || a.textContent);
      var m = CH.exec(text), word = '', num = '';
      if (m) { word = m[1]; num = m[2]; }
      else {
        var hm = HREFCH.exec(safeDecode(u));
        if (hm) { word = 'الفصل'; num = hm[1]; }
      }
      if (!num) continue;
      if (/^[a-z]/i.test(word)) word = 'Chapter';
      else if (word === 'فصل') word = 'الفصل';
      matched.push(a);
      metas.push({ el: a, t: word + ' ' + num, u: u });
    }
    var box = pick(matched);
    var seen = {}, list = [];
    for (var k = 0; k < metas.length; k++) {
      var it = metas[k];
      if (box && !box.contains(it.el)) continue;
      if (seen[it.u]) continue;
      seen[it.u] = 1;
      list.push({ t: it.t, u: it.u });
    }
    return { chapters: list, matched: matched.length, box: box };
  }

  function imgUrl(im) {
    var u = im.getAttribute('data-src') || im.getAttribute('data-lazy-src') || im.getAttribute('data-original') || im.currentSrc || im.getAttribute('src') || '';
    if (!u || /^data:/i.test(u) || /^blob:/i.test(u)) return '';
    return abs(u);
  }
  function noiseImg(u) {
    if (u.split('?')[0].split('#')[0].slice(-4).toLowerCase() === '.svg') return true;
    return /(logo|avatar|favicon|sprite|emoji|loading|placeholder|banner|icon)/i.test(u);
  }

  /** أعلى عنصر أب لعنوان المانهوا لا يحتوي قائمة الفصول (منطقة الغلاف والعنوان والنبذة) */
  function headerArea(h1El, listBox) {
    if (!h1El) return null;
    var el = h1El, up = 0;
    while (el.parentElement && up < 8 && el.parentElement !== document.body) {
      if (listBox && el.parentElement.contains(listBox)) break;
      el = el.parentElement; up++;
    }
    return el;
  }
  function nearTitleImage(area) {
    if (!area) return '';
    var imgs = area.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i], u = imgUrl(im);
      if (!u || noiseImg(u)) continue;
      if (im.naturalWidth > 0 && im.naturalWidth < 80) continue;
      return u;
    }
    return '';
  }
  function longestTextIn(area) {
    if (!area) return '';
    var best = '', nodes = area.querySelectorAll('p,div,span');
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.querySelector('a,button,h1,h2,h3,ul,li,table,div,p')) continue;
      var t = clean(n.innerText || n.textContent);
      if (t.length > best.length && t.length <= 1500) best = t;
    }
    return best;
  }

  function pageMeta(listBox) {
    var h1El = null, h1 = '', h1s = document.querySelectorAll('h1');
    for (var j = 0; j < h1s.length; j++) {
      var tx = clean(h1s[j].innerText || h1s[j].textContent);
      if (tx.length >= 2 && tx.length <= 120) { h1El = h1s[j]; h1 = tx; break; }
    }
    var ogt = meta('og:title');
    if (ogt) ogt = clean(ogt.split(/\s[|\u2013\u2014\-]\s/)[0]);
    var title = h1 || ogt || clean((document.title || '').split(/\s[|\u2013\u2014\-]\s/)[0]);
    var area = headerArea(h1El, listBox);

    var cover = '', coverSrc = '', og = meta('og:image');
    if (og && !/(logo|favicon|default|placeholder|icon)/i.test(og)) { cover = abs(og); coverSrc = 'og:image'; }
    if (!cover) { var ni = nearTitleImage(area); if (ni) { cover = ni; coverSrc = 'near-title'; } }
    if (!cover) {
      var cimg = document.querySelector('.summary_image img,.thumb img,.cover img,img.wp-post-image,[class*="cover"] img,[class*="poster"] img');
      var cu = cimg ? imgUrl(cimg) : '';
      if (cu) { cover = cu; coverSrc = 'selector'; }
    }
    if (!cover && og) { cover = abs(og); coverSrc = 'og:image(logo?)'; }

    var desc = '', descSrc = '';
    var dEl = document.querySelector('.summary__content,.description-summary,[itemprop="description"],[class*="synopsis"],[class*="summary"] p,.entry-content p,[class*="story"]');
    var d1 = dEl ? clean(dEl.innerText || dEl.textContent) : '';
    if (d1.length >= 40) { desc = d1; descSrc = 'selector'; }
    if (!desc) { var lt = longestTextIn(area); if (lt.length >= 40) { desc = lt; descSrc = 'near-title'; } }
    if (!desc) { var od = meta('og:description') || meta('description'); if (od) { desc = od; descSrc = 'meta'; } }
    if (!desc && d1) { desc = d1; descSrc = 'selector-short'; }
    return { title: title, cover: cover, coverSrc: coverSrc, desc: desc.substring(0, 800), descSrc: descSrc };
  }

  function isCurrentEl(el) {
    var probe = (typeof el.className === 'string' ? el.className : '') + ' ' +
      (el.firstElementChild && typeof el.firstElementChild.className === 'string' ? el.firstElementChild.className : '');
    if (/(active|current|selected)/i.test(probe)) return true;
    if (el.getAttribute('aria-current')) return true;
    var inner = el.querySelector ? el.querySelector('[aria-current]') : null;
    return !!inner;
  }

  /** يكتشف شريط ترقيم الصفحات: عناصر أرقام متجاورة (1 2 3 ...) تحت أب واحد */
  function pagerInfo() {
    var els = document.querySelectorAll('a,button,li,span,div,input');
    var groups = new Map();
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var t = clean(el.textContent);
      if (t.length < 1 || t.length > 3 || /\D/.test(t)) continue;
      if (el.children && el.children.length > 1) continue;
      if (!isVisible(el)) continue;
      var p = el.parentElement;
      if (!p) continue;
      if (!groups.has(p)) groups.set(p, []);
      groups.get(p).push(el);
    }
    var bestP = null, bestN = 0;
    groups.forEach(function (arr, p) { if (arr.length > bestN) { bestN = arr.length; bestP = p; } });
    if (!bestP || bestN < 3) return null;
    var items = groups.get(bestP).map(function (el) {
      var target = (el.matches && el.matches('a,button')) ? el : ((el.querySelector && el.querySelector('a,button')) || el);
      var href = '';
      if (target.tagName === 'A') {
        var h = target.getAttribute('href') || '';
        if (h && h.charAt(0) !== '#' && !/^javascript:/i.test(h)) href = abs(h);
      }
      return { n: parseInt(clean(el.textContent), 10), el: target, current: isCurrentEl(el), href: href };
    });
    return { box: bestP, items: items };
  }

  function pageUrlsOf() {
    var info = pagerInfo(), out = [];
    if (info) info.items.forEach(function (it) { if (it.href && out.indexOf(it.href) < 0) out.push(it.href); });
    return out;
  }
  var cc = collectChapters();
  var m = pageMeta(cc.box);
  return JSON.stringify({
    url: location.href, title: m.title, cover: m.cover, desc: m.desc,
    chapters: cc.chapters, pageUrls: pageUrlsOf(),
    diag: {
      page: location.href, anchorsMatched: cc.matched, chaptersPicked: cc.chapters.length,
      listContainer: describe(cc.box), sample: cc.chapters.slice(0, 3),
      title: m.title, cover: m.cover, coverSource: m.coverSrc,
      descLen: m.desc.length, descSource: m.descSrc, pagerUrls: pageUrlsOf().length
    }
  });
})()
"""

    /** نفس الفحص لكن يرسل النتيجة عبر AbdoDl.result (للصفحة المخفية) */
    const val SCAN_HIDDEN = "window.AbdoDl.result(" + SCAN + ");"

    /** للصفحة المخفية: ينتقل بين صفحات ترقيم قائمة الفصول ويجمعها كلها ثم يرسلها عبر AbdoDl.result */
    const val FULL = """
(function () {
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return ''; } }
  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function meta(n) {
    var m = document.querySelector('meta[property="' + n + '"],meta[name="' + n + '"]');
    return m ? clean(m.getAttribute('content')) : '';
  }
  function safeDecode(s) { try { return decodeURIComponent(s); } catch (e) { return s; } }
  function depthOf(el) { var d = 0; while (el) { d++; el = el.parentElement; } return d; }
  function isVisible(el) { return !!(el.offsetWidth || el.offsetHeight || (el.getClientRects && el.getClientRects().length)); }
  function describe(el) {
    if (!el) return '';
    var c = (typeof el.className === 'string' && el.className) ? ('.' + el.className.trim().split(/\s+/).slice(0, 3).join('.')) : '';
    return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + c;
  }
  function pick(els) {
    var count = new Map();
    els.forEach(function (el) {
      var p = el.parentElement, d = 0;
      while (p && p !== document.body && d < 7) { count.set(p, (count.get(p) || 0) + 1); p = p.parentElement; d++; }
    });
    var max = 0;
    count.forEach(function (n) { if (n > max) max = n; });
    if (!max) return null;
    var best = null, bestDepth = -1;
    count.forEach(function (n, el) {
      if (n >= max * 0.85) { var dp = depthOf(el); if (dp > bestDepth) { bestDepth = dp; best = el; } }
    });
    return best;
  }

  var CH = /(الفصل|فصل|الحلقة|حلقة|chapter|chap|ch|episode|ep)\s*[.:#\-]?\s*(\d+(?:[.\-]\d+)?)/i;
  var HREFCH = /(?:chapter|chap|ch|فصل)[-_\/ ]*(\d+(?:[.\-]\d+)?)/i;

  function collectChapters() {
    var matched = [], metas = [];
    var anchors = document.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i], href = a.getAttribute('href') || '';
      if (!href || href.charAt(0) === '#' || /^(javascript|mailto|tel):/i.test(href)) continue;
      var u = abs(href);
      if (!u || u.split('#')[0] === location.href.split('#')[0]) continue;
      var text = clean(a.innerText || a.textContent);
      var m = CH.exec(text), word = '', num = '';
      if (m) { word = m[1]; num = m[2]; }
      else {
        var hm = HREFCH.exec(safeDecode(u));
        if (hm) { word = 'الفصل'; num = hm[1]; }
      }
      if (!num) continue;
      if (/^[a-z]/i.test(word)) word = 'Chapter';
      else if (word === 'فصل') word = 'الفصل';
      matched.push(a);
      metas.push({ el: a, t: word + ' ' + num, u: u });
    }
    var box = pick(matched);
    var seen = {}, list = [];
    for (var k = 0; k < metas.length; k++) {
      var it = metas[k];
      if (box && !box.contains(it.el)) continue;
      if (seen[it.u]) continue;
      seen[it.u] = 1;
      list.push({ t: it.t, u: it.u });
    }
    return { chapters: list, matched: matched.length, box: box };
  }

  function imgUrl(im) {
    var u = im.getAttribute('data-src') || im.getAttribute('data-lazy-src') || im.getAttribute('data-original') || im.currentSrc || im.getAttribute('src') || '';
    if (!u || /^data:/i.test(u) || /^blob:/i.test(u)) return '';
    return abs(u);
  }
  function noiseImg(u) {
    if (u.split('?')[0].split('#')[0].slice(-4).toLowerCase() === '.svg') return true;
    return /(logo|avatar|favicon|sprite|emoji|loading|placeholder|banner|icon)/i.test(u);
  }

  /** أعلى عنصر أب لعنوان المانهوا لا يحتوي قائمة الفصول (منطقة الغلاف والعنوان والنبذة) */
  function headerArea(h1El, listBox) {
    if (!h1El) return null;
    var el = h1El, up = 0;
    while (el.parentElement && up < 8 && el.parentElement !== document.body) {
      if (listBox && el.parentElement.contains(listBox)) break;
      el = el.parentElement; up++;
    }
    return el;
  }
  function nearTitleImage(area) {
    if (!area) return '';
    var imgs = area.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i], u = imgUrl(im);
      if (!u || noiseImg(u)) continue;
      if (im.naturalWidth > 0 && im.naturalWidth < 80) continue;
      return u;
    }
    return '';
  }
  function longestTextIn(area) {
    if (!area) return '';
    var best = '', nodes = area.querySelectorAll('p,div,span');
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.querySelector('a,button,h1,h2,h3,ul,li,table,div,p')) continue;
      var t = clean(n.innerText || n.textContent);
      if (t.length > best.length && t.length <= 1500) best = t;
    }
    return best;
  }

  function pageMeta(listBox) {
    var h1El = null, h1 = '', h1s = document.querySelectorAll('h1');
    for (var j = 0; j < h1s.length; j++) {
      var tx = clean(h1s[j].innerText || h1s[j].textContent);
      if (tx.length >= 2 && tx.length <= 120) { h1El = h1s[j]; h1 = tx; break; }
    }
    var ogt = meta('og:title');
    if (ogt) ogt = clean(ogt.split(/\s[|\u2013\u2014\-]\s/)[0]);
    var title = h1 || ogt || clean((document.title || '').split(/\s[|\u2013\u2014\-]\s/)[0]);
    var area = headerArea(h1El, listBox);

    var cover = '', coverSrc = '', og = meta('og:image');
    if (og && !/(logo|favicon|default|placeholder|icon)/i.test(og)) { cover = abs(og); coverSrc = 'og:image'; }
    if (!cover) { var ni = nearTitleImage(area); if (ni) { cover = ni; coverSrc = 'near-title'; } }
    if (!cover) {
      var cimg = document.querySelector('.summary_image img,.thumb img,.cover img,img.wp-post-image,[class*="cover"] img,[class*="poster"] img');
      var cu = cimg ? imgUrl(cimg) : '';
      if (cu) { cover = cu; coverSrc = 'selector'; }
    }
    if (!cover && og) { cover = abs(og); coverSrc = 'og:image(logo?)'; }

    var desc = '', descSrc = '';
    var dEl = document.querySelector('.summary__content,.description-summary,[itemprop="description"],[class*="synopsis"],[class*="summary"] p,.entry-content p,[class*="story"]');
    var d1 = dEl ? clean(dEl.innerText || dEl.textContent) : '';
    if (d1.length >= 40) { desc = d1; descSrc = 'selector'; }
    if (!desc) { var lt = longestTextIn(area); if (lt.length >= 40) { desc = lt; descSrc = 'near-title'; } }
    if (!desc) { var od = meta('og:description') || meta('description'); if (od) { desc = od; descSrc = 'meta'; } }
    if (!desc && d1) { desc = d1; descSrc = 'selector-short'; }
    return { title: title, cover: cover, coverSrc: coverSrc, desc: desc.substring(0, 800), descSrc: descSrc };
  }

  function isCurrentEl(el) {
    var probe = (typeof el.className === 'string' ? el.className : '') + ' ' +
      (el.firstElementChild && typeof el.firstElementChild.className === 'string' ? el.firstElementChild.className : '');
    if (/(active|current|selected)/i.test(probe)) return true;
    if (el.getAttribute('aria-current')) return true;
    var inner = el.querySelector ? el.querySelector('[aria-current]') : null;
    return !!inner;
  }

  /** يكتشف شريط ترقيم الصفحات: عناصر أرقام متجاورة (1 2 3 ...) تحت أب واحد */
  function pagerInfo() {
    var els = document.querySelectorAll('a,button,li,span,div,input');
    var groups = new Map();
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var t = clean(el.textContent);
      if (t.length < 1 || t.length > 3 || /\D/.test(t)) continue;
      if (el.children && el.children.length > 1) continue;
      if (!isVisible(el)) continue;
      var p = el.parentElement;
      if (!p) continue;
      if (!groups.has(p)) groups.set(p, []);
      groups.get(p).push(el);
    }
    var bestP = null, bestN = 0;
    groups.forEach(function (arr, p) { if (arr.length > bestN) { bestN = arr.length; bestP = p; } });
    if (!bestP || bestN < 3) return null;
    var items = groups.get(bestP).map(function (el) {
      var target = (el.matches && el.matches('a,button')) ? el : ((el.querySelector && el.querySelector('a,button')) || el);
      var href = '';
      if (target.tagName === 'A') {
        var h = target.getAttribute('href') || '';
        if (h && h.charAt(0) !== '#' && !/^javascript:/i.test(h)) href = abs(h);
      }
      return { n: parseInt(clean(el.textContent), 10), el: target, current: isCurrentEl(el), href: href };
    });
    return { box: bestP, items: items };
  }

  function pageUrlsOf() {
    var info = pagerInfo(), out = [];
    if (info) info.items.forEach(function (it) { if (it.href && out.indexOf(it.href) < 0) out.push(it.href); });
    return out;
  }
  if (window.__abdoFull) return;
  window.__abdoFull = true;
  var BR = window.AbdoDl;
  var all = {}, order = [], visited = {}, pageUrls = {}, steps = 0, maxSeen = 0, lastClicked = -1, clicks = 0;

  function addCurrent() {
    var cc = collectChapters(), added = 0;
    cc.chapters.forEach(function (c) { if (!all[c.u]) { all[c.u] = 1; order.push(c); added++; } });
    return added;
  }
  function signature() {
    var cc = collectChapters(), l = cc.chapters;
    return l.length + '|' + (l[0] ? l[0].u : '') + '|' + (l[l.length - 1] ? l[l.length - 1].u : '');
  }
  function finish() {
    var cc = collectChapters();
    var m = pageMeta(cc.box);
    try {
      BR.result(JSON.stringify({
        url: location.href, title: m.title, cover: m.cover, desc: m.desc,
        chapters: order, pageUrls: Object.keys(pageUrls),
        diag: { page: location.href, chaptersPicked: order.length, clicks: clicks, maxPage: maxSeen,
                title: m.title, coverSource: m.coverSrc, descSource: m.descSrc }
      }));
    } catch (e) {}
  }
  function waitChange(before, cb) {
    var waited = 0;
    var t = setInterval(function () {
      waited += 250;
      if (signature() !== before || waited >= 2500) { clearInterval(t); cb(); }
    }, 250);
  }
  function step() {
    steps++;
    if (steps > 40) { finish(); return; }
    var info = pagerInfo();
    if (!info) { finish(); return; }
    var hrefMode = false;
    info.items.forEach(function (it) {
      if (it.n > maxSeen) maxSeen = it.n;
      if (it.href) { pageUrls[it.href] = 1; hrefMode = true; }
    });
    if (hrefMode) { finish(); return; }
    info.items.forEach(function (it) { if (it.current) visited[it.n] = true; });
    var unvisited = info.items.filter(function (it) { return !visited[it.n]; });
    var target = null;
    if (unvisited.length) {
      unvisited.sort(function (a, b) { return b.n - a.n; });
      target = unvisited[0];
    } else {
      var hole = 0;
      for (var n = 1; n <= maxSeen; n++) { if (!visited[n]) { hole = n; break; } }
      if (!hole) { finish(); return; }
      info.items.sort(function (a, b) { return Math.abs(a.n - hole) - Math.abs(b.n - hole); });
      target = info.items[0];
      if (target.n === lastClicked) { finish(); return; }
    }
    visited[target.n] = true;
    lastClicked = target.n;
    clicks++;
    var before = signature();
    try { target.el.click(); } catch (e) {}
    waitChange(before, function () { addCurrent(); setTimeout(step, 150); });
  }
  addCurrent();
  setTimeout(step, 300);
})();
"""

    /** للصفحة المخفية: يمرّر الصفحة تلقائياً حتى تظهر كل الصور الكسولة، ثم يرسل روابطها عبر AbdoDl.result */
    const val COLLECT = """
(function () {
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return ''; } }
  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function depthOf(el) { var d = 0; while (el) { d++; el = el.parentElement; } return d; }
  function describe(el) {
    if (!el) return '';
    var c = (typeof el.className === 'string' && el.className) ? ('.' + el.className.trim().split(/\s+/).slice(0, 3).join('.')) : '';
    return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + c;
  }
  function urlOf(im) {
    var u = im.getAttribute('data-src') || im.getAttribute('data-lazy-src') || im.getAttribute('data-original') || im.getAttribute('data-url') || '';
    if (!u) u = im.currentSrc || im.getAttribute('src') || '';
    if (!u) { var ss = im.getAttribute('srcset') || ''; if (ss) u = ss.split(',').pop().trim().split(' ')[0]; }
    if (!u || /^data:/i.test(u) || /^blob:/i.test(u)) return '';
    return abs(u);
  }
  function isNoise(u) {
    if (u.split('?')[0].split('#')[0].slice(-4).toLowerCase() === '.svg') return true;
    return /(logo|avatar|favicon|sprite|emoji|loading|placeholder|banner)/i.test(u);
  }
  function candidates() {
    var out = [], imgs = document.images;
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i], u = urlOf(im);
      if (!u || isNoise(u)) continue;
      if (im.naturalWidth > 0 && im.naturalWidth < 200) continue;
      if (im.closest && im.closest('header,footer,nav,aside')) continue;
      out.push({ im: im, u: u });
    }
    return out;
  }
  function pick(list) {
    var count = new Map();
    list.forEach(function (c) {
      var p = c.im.parentElement, d = 0;
      while (p && p !== document.body && d < 7) { count.set(p, (count.get(p) || 0) + 1); p = p.parentElement; d++; }
    });
    var max = 0;
    count.forEach(function (n) { if (n > max) max = n; });
    if (!max) return null;
    var best = null, bestDepth = -1;
    count.forEach(function (n, el) {
      if (n >= max * 0.85) { var dp = depthOf(el); if (dp > bestDepth) { bestDepth = dp; best = el; } }
    });
    return best;
  }
  function best() {
    var list = candidates();
    var box = pick(list);
    var seen = {}, urls = [];
    list.forEach(function (c) {
      if (box && !box.contains(c.im)) return;
      if (seen[c.u]) return;
      seen[c.u] = 1;
      urls.push(c.u);
    });
    return { urls: urls, box: box };
  }
  if (window.__abdoCollecting) return;
  window.__abdoCollecting = true;
  var BR = window.AbdoDl;
  var last = -1, stable = 0, ticks = 0, finished = false;
  function finish() {
    if (finished) return;
    finished = true;
    var r = best();
    try { BR.result(JSON.stringify({ urls: r.urls, container: describe(r.box), page: location.href })); } catch (e) {}
  }
  var timer = setInterval(function () {
    ticks++;
    window.scrollBy(0, Math.max(400, Math.floor(window.innerHeight * 0.9)));
    var root = document.scrollingElement || document.documentElement;
    var atBottom = (window.innerHeight + root.scrollTop) >= (root.scrollHeight - 4);
    var cnt = best().urls.length;
    if (cnt === last) stable++; else { stable = 0; last = cnt; }
    if ((atBottom && stable >= 8) || ticks > 240) { clearInterval(timer); finish(); }
  }, 250);
})();
"""

    /** للصفحة المفتوحة أمامك: يقرأ روابط الصور بالترتيب فوراً دون تمرير الصفحة. يُعيد نص JSON. */
    const val QUICK = """
(function () {
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return ''; } }
  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function depthOf(el) { var d = 0; while (el) { d++; el = el.parentElement; } return d; }
  function describe(el) {
    if (!el) return '';
    var c = (typeof el.className === 'string' && el.className) ? ('.' + el.className.trim().split(/\s+/).slice(0, 3).join('.')) : '';
    return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + c;
  }
  function urlOf(im) {
    var u = im.getAttribute('data-src') || im.getAttribute('data-lazy-src') || im.getAttribute('data-original') || im.getAttribute('data-url') || '';
    if (!u) u = im.currentSrc || im.getAttribute('src') || '';
    if (!u) { var ss = im.getAttribute('srcset') || ''; if (ss) u = ss.split(',').pop().trim().split(' ')[0]; }
    if (!u || /^data:/i.test(u) || /^blob:/i.test(u)) return '';
    return abs(u);
  }
  function isNoise(u) {
    if (u.split('?')[0].split('#')[0].slice(-4).toLowerCase() === '.svg') return true;
    return /(logo|avatar|favicon|sprite|emoji|loading|placeholder|banner)/i.test(u);
  }
  function candidates() {
    var out = [], imgs = document.images;
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i], u = urlOf(im);
      if (!u || isNoise(u)) continue;
      if (im.naturalWidth > 0 && im.naturalWidth < 200) continue;
      if (im.closest && im.closest('header,footer,nav,aside')) continue;
      out.push({ im: im, u: u });
    }
    return out;
  }
  function pick(list) {
    var count = new Map();
    list.forEach(function (c) {
      var p = c.im.parentElement, d = 0;
      while (p && p !== document.body && d < 7) { count.set(p, (count.get(p) || 0) + 1); p = p.parentElement; d++; }
    });
    var max = 0;
    count.forEach(function (n) { if (n > max) max = n; });
    if (!max) return null;
    var best = null, bestDepth = -1;
    count.forEach(function (n, el) {
      if (n >= max * 0.85) { var dp = depthOf(el); if (dp > bestDepth) { bestDepth = dp; best = el; } }
    });
    return best;
  }
  function best() {
    var list = candidates();
    var box = pick(list);
    var seen = {}, urls = [];
    list.forEach(function (c) {
      if (box && !box.contains(c.im)) return;
      if (seen[c.u]) return;
      seen[c.u] = 1;
      urls.push(c.u);
    });
    return { urls: urls, box: box };
  }
  var CH = /(الفصل|فصل|الحلقة|حلقة|chapter|chap|ch|episode|ep)\s*[.:#\-]?\s*(\d+(?:[.\-]\d+)?)/i;
  function trimSep(s) {
    s = clean(s);
    while (s.length && ' -|:\u2013\u2014\u00B7'.indexOf(s.charAt(s.length - 1)) >= 0) s = s.slice(0, -1);
    return s;
  }
  function cut(s) {
    s = clean(s);
    var m = CH.exec(s);
    if (!m) return { name: trimSep(s), ch: '' };
    var w = m[1];
    if (/^[a-z]/i.test(w)) w = 'Chapter'; else if (w === 'فصل') w = 'الفصل';
    return { name: trimSep(s.substring(0, m.index)), ch: w + ' ' + m[2] };
  }

  var r = best();
  var empty = 0;
  if (r.box) {
    var all = r.box.querySelectorAll('img');
    for (var i = 0; i < all.length; i++) {
      var im = all[i];
      if (im.naturalWidth > 0 && im.naturalWidth < 200) continue;
      if (!urlOf(im)) empty++;
    }
  }

  var h1 = '', hs = document.querySelectorAll('h1');
  for (var j = 0; j < hs.length; j++) {
    var tx = clean(hs[j].innerText || hs[j].textContent);
    if (tx.length >= 2 && tx.length <= 160) { h1 = tx; break; }
  }
  var a1 = cut(h1), a2 = cut(document.title || '');
  var name = a1.name || a2.name;
  var chapter = a1.ch || a2.ch;

  var path = location.pathname, mangaUrl = '', mangaText = '', bestLen = 0;
  var links = document.querySelectorAll('a[href]');
  for (var k = 0; k < links.length; k++) {
    var u;
    try { u = new URL(links[k].getAttribute('href'), location.href); } catch (e) { continue; }
    if (u.origin !== location.origin) continue;
    var p = u.pathname;
    if (p.length < 2 || p === path) continue;
    var pn = p.charAt(p.length - 1) === '/' ? p : p + '/';
    if (path.indexOf(pn) !== 0) continue;
    if (p.length > bestLen) {
      bestLen = p.length;
      mangaUrl = u.href;
      mangaText = clean(links[k].innerText || links[k].textContent);
    }
  }
  if (!name && mangaText && mangaText.length <= 120) name = mangaText;

  return JSON.stringify({
    urls: r.urls, container: describe(r.box), emptySlots: empty, page: location.href,
    name: name, chapter: chapter, mangaUrl: mangaUrl, mangaText: mangaText
  });
})()
"""
}
