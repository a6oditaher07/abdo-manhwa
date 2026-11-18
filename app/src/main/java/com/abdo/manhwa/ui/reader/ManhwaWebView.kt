package com.abdo.manhwa.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView

/**
 * WebView مخصص يميّز بين النقر البسيط في منتصف الشاشة (لإظهار/إخفاء أدوات التحكم)
 * وبين التمرير العادي أو التكبير بإصبعين، دون التأثير على سلوك WebView الافتراضي للتمرير والتكبير.
 * الضغط على أطراف الشاشة (الجوانب) يُتجاهل عمداً كما هو مطلوب.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ManhwaWebView(
    context: Context,
    private val onCenterTap: () -> Unit
) : WebView(context) {

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val widthRatio = e.x / width.coerceAtLeast(1)
            // المنطقة الوسطى فقط (20% إلى 80% من العرض) تُفعّل إظهار/إخفاء التحكم
            if (widthRatio in 0.2f..0.8f) {
                onCenterTap()
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }
}
