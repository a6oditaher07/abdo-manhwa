package com.abdo.manhwa.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** حجم الفصل على القرص */
object ChapterSize {

    /**
     * فصل محمّل من الإنترنت (…/dl_xxx/index.html): مجموع ملفات المجلد (الصور + index.html).
     * فصل SingleFile قديم: حجم ملف HTML نفسه (الصور مضمّنة داخله).
     */
    fun bytes(path: String?): Long {
        if (path.isNullOrBlank()) return 0L
        val file = File(path)
        if (!file.exists()) return 0L
        val parent = file.parentFile
        return if (file.name == "index.html" && parent != null && parent.name.startsWith("dl_")) {
            parent.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: file.length()
        } else file.length()
    }

    fun format(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }
}

/** نص حجم الفصل (يُحسب خارج الخيط الرئيسي ويُعاد حسابه إن تغيّر المسار) */
@Composable
fun rememberChapterSizeText(filePath: String?): String {
    val text by produceState(initialValue = "", filePath) {
        value = withContext(Dispatchers.IO) { ChapterSize.format(ChapterSize.bytes(filePath)) }
    }
    return text
}
