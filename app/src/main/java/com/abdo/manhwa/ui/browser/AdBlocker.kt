package com.abdo.manhwa.ui.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * مانع إعلانات بسيط للمتصفح الداخلي: يحجب الطلبات إلى نطاقات الإعلانات المعروفة،
 * ويمنع إعادة التوجيه (النوافذ المنبثقة) إليها، ويخفي عناصر الإعلانات الشائعة بـCSS.
 * الحالة تُحفظ في SharedPreferences ("abdo_browser") بالمفتاح "adblock".
 */
object AdBlocker {

    private const val PREFS = "abdo_browser"
    private const val KEY = "adblock"

    /** يُقرأ من خيوط الشبكة في WebView، لذلك volatile */
    @Volatile
    var enabled: Boolean = true
        private set

    /** يقرأ الحالة المحفوظة (الافتراضي: مفعّل) ويعيدها */
    fun load(context: Context): Boolean {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
        return enabled
    }

    fun set(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    // قائمة نطاقات إعلانات/تتبّع شائعة. المطابقة بالنطاق نفسه أو أي نطاق فرعي منه.
    private val blockedHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "2mdn.net", "adnxs.com", "adsrvr.org", "amazon-adsystem.com",
        "criteo.com", "criteo.net", "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
        "taboola.com", "outbrain.com", "revcontent.com", "mgid.com", "adform.net", "smartadserver.com",
        "moatads.com", "scorecardresearch.com", "quantserve.com", "yieldmo.com", "bidswitch.net",
        "popads.net", "popcash.net", "popunderjs.com", "propellerads.com", "propellerclick.com",
        "exoclick.com", "exosrv.com", "exdynsrv.com", "juicyads.com", "trafficjunky.net", "trafficstars.com",
        "adsterra.com", "adsterratools.com", "highperformanceformat.com", "highrevenuegate.com",
        "profitablegatecpm.com", "profitableratecpm.com", "effectivegatecpm.com", "effectiveratecpm.com",
        "onclickads.net", "clickadu.com", "hilltopads.net", "hilltopads.com", "adcash.com", "a-ads.com",
        "ad-maven.com", "admaven.com", "monetag.com", "richpartners.com", "adskeeper.com", "adskeeper.co.uk",
        "bidvertiser.com", "adk2.com", "adk2x.com", "ero-advertising.com", "tsyndicate.com", "mc.yandex.ru",
        "an.yandex.ru", "yandexadexchange.net", "ads.yahoo.com", "advertising.com", "zedo.com",
        "serving-sys.com", "adblade.com", "content.ad", "infolinks.com", "media.net", "tynt.com",
        "histats.com", "clickaine.com", "pushails.com", "pushwhy.com", "notifpush.com", "wpushorg.com",
        "acscdn.com", "realsrv.com", "syndication.realsrv.com", "mondiad.com", "dtscout.com", "kettledroopingcontinuation.com"
    )

    fun isBlockedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return blockedHosts.any { h == it || h.endsWith(".$it") }
    }

    fun isBlocked(uri: Uri?): Boolean = enabled && isBlockedHost(uri?.host)

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** يخفي عناصر الإعلانات الشائعة (محافظ عمداً حتى لا يخفي صور الفصول) */
    const val COSMETIC_JS = """
(function () {
  try {
    if (document.getElementById('abdo-adblock-style')) return;
    var css = 'ins.adsbygoogle, iframe[src*="doubleclick"], iframe[src*="googlesyndication"], ' +
      'iframe[src*="/ads/"], iframe[id^="google_ads"], div[id^="google_ads"], div[id^="div-gpt-ad"], ' +
      'div[class*="adsbox"], div[class*="ad-banner"], div[class*="ads-banner"], div[id*="banner-ad"], ' +
      'a[href*="popads"], a[href*="exoclick"], a[href*="juicyads"], a[href*="adsterra"], ' +
      '.adsbygoogle, [data-ad-slot], [data-adsbygoogle-status] { display: none !important; }';
    var s = document.createElement('style');
    s.id = 'abdo-adblock-style';
    s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  } catch (e) {}
})();
"""
}
