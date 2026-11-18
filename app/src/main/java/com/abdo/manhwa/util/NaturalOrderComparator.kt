package com.abdo.manhwa.util

/**
 * مقارنة "ترتيب طبيعي" لأسماء الملفات بحيث يسبق الفصل رقم 2 الفصل رقم 10
 * (وليس ترتيباً أبجدياً نصياً بحتاً يضع "10" قبل "2").
 */
object NaturalOrderComparator : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val ca = a.toCharArray()
        val cb = b.toCharArray()
        var i = 0
        var j = 0
        while (i < ca.size && j < cb.size) {
            val chA = ca[i]
            val chB = cb[j]

            if (chA.isDigit() && chB.isDigit()) {
                val startA = i
                while (i < ca.size && ca[i].isDigit()) i++
                val startB = j
                while (j < cb.size && cb[j].isDigit()) j++

                val numA = a.substring(startA, i).trimStart('0').ifEmpty { "0" }
                val numB = b.substring(startB, j).trimStart('0').ifEmpty { "0" }

                val cmp = if (numA.length != numB.length) {
                    numA.length - numB.length
                } else {
                    numA.compareTo(numB)
                }
                if (cmp != 0) return cmp
            } else {
                val cmp = chA.lowercaseChar().compareTo(chB.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (ca.size - i) - (cb.size - j)
    }

    /** يستخرج أول رقم موجود داخل اسم الملف ليُستخدم كعنوان افتراضي للفصل، مثل "الفصل 12" */
    fun extractNumber(name: String): Int? {
        val match = Regex("\\d+").find(name) ?: return null
        return match.value.toIntOrNull()
    }
}
