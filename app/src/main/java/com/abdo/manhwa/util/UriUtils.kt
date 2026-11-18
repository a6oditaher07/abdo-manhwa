package com.abdo.manhwa.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** يستخرج الاسم الظاهري لملف عبر Uri (يُستخدم لعرض اسم الفصل المختار واستخراج رقمه) */
fun resolveDisplayName(context: Context, uri: Uri): String {
    var name = "ملف"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(index) ?: name
        }
    }
    return name
}
