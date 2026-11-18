package com.abdo.manhwa.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** يبحث عن الـ Activity الحقيقية من أي Context (مفيد عند استخدام LocalContext داخل Compose) */
fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
