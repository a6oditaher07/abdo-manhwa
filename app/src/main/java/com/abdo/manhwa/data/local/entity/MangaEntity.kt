package com.abdo.manhwa.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * يمثل مانهوا واحدة مخزّنة في المكتبة.
 * coverPath: مسار صورة الغلاف داخل مساحة التطبيق الداخلية.
 * lastReadChapterId: آخر فصل توقف المستخدم عند قراءته (null إذا لم يقرأ شيئاً بعد).
 */
@Entity(tableName = "manga")
data class MangaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String,
    val coverPath: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val lastReadChapterId: Long? = null,
    val lastReadScrollPercent: Float = 0f,
    val lastReadAt: Long = 0L,
    val isFavorite: Boolean = false
)
