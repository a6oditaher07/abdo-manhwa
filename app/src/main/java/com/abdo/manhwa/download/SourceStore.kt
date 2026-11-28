package com.abdo.manhwa.download

import android.content.Context
import com.abdo.manhwa.data.repository.normalizeTitleKey

/**
 * يحفظ رابط صفحة المصدر (الموقع الذي نُزّلت منه المانهوا) لكل مانهوا في المكتبة،
 * بدون أي تغيير في قاعدة البيانات (حتى لا تُمسح المكتبة).
 * للمانهوا التي حُمّلت قبل هذا التحديث: يبحث في الأسماء المحفوظة من المتصفح ("name:<رابط>" = اسم).
 */
object SourceStore {

    private const val PREFS = "abdo_sources"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, title: String): String? {
        val key = normalizeTitleKey(title)
        prefs(context).getString("src:$key", null)?.takeIf { it.isNotBlank() }?.let { return it }

        // احتياطي: روابط حُفظت أسماؤها من المتصفح عند تحميل فصل منفرد
        val legacy = context.applicationContext
            .getSharedPreferences("abdo_browser", Context.MODE_PRIVATE).all
        return legacy.entries
            .firstOrNull { (k, v) ->
                k.startsWith("name:") && v is String && normalizeTitleKey(v) == key
            }
            ?.key?.removePrefix("name:")?.takeIf { it.isNotBlank() }
    }

    fun put(context: Context, title: String, url: String) {
        if (title.isBlank() || url.isBlank()) return
        prefs(context).edit().putString("src:" + normalizeTitleKey(title), url.trim()).apply()
    }
}
