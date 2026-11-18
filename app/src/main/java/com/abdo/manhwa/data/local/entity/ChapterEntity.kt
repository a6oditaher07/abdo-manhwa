package com.abdo.manhwa.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * يمثل فصلاً واحداً من فصول المانهوا.
 * filePath: مسار ملف HTML الخاص بالفصل داخل مساحة التطبيق الداخلية.
 * orderIndex: ترتيب الفصل ضمن المانهوا (ترتيب طبيعي عند الإضافة، قابل لإعادة الترتيب يدوياً).
 * isRead: هل أنهى المستخدم قراءة هذا الفصل بالكامل.
 * scrollPercent: آخر نسبة تمرير محفوظة لهذا الفصل تحديداً.
 */
@Entity(
    tableName = "chapter",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("mangaId")]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mangaId: Long,
    val title: String,
    val filePath: String,
    val orderIndex: Int,
    val isRead: Boolean = false,
    val scrollPercent: Float = 0f
)
