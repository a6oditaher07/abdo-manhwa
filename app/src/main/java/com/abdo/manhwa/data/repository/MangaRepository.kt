package com.abdo.manhwa.data.repository

import android.content.Context
import android.net.Uri
import com.abdo.manhwa.data.local.AppDatabase
import com.abdo.manhwa.data.local.entity.ChapterEntity
import com.abdo.manhwa.data.local.entity.MangaEntity
import com.abdo.manhwa.util.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** عنصر وسيط يمثل فصلاً تم اختياره في شاشة الإضافة قبل التأكيد والحفظ النهائي */
data class PickedChapter(
    val uri: Uri,
    val displayName: String,
    val title: String
)

class MangaRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val mangaDao = db.mangaDao()
    private val chapterDao = db.chapterDao()
    private val fileStorage = FileStorageManager(context)

    fun observeLibrary(): Flow<List<MangaEntity>> = mangaDao.observeAll()

    fun observeManga(mangaId: Long): Flow<MangaEntity?> = mangaDao.observeById(mangaId)

    fun observeChapters(mangaId: Long): Flow<List<ChapterEntity>> = chapterDao.observeByManga(mangaId)

    fun observeChapter(chapterId: Long): Flow<ChapterEntity?> = chapterDao.observeById(chapterId)

    suspend fun getManga(mangaId: Long): MangaEntity? = withContext(Dispatchers.IO) { mangaDao.getById(mangaId) }

    suspend fun getChapter(chapterId: Long): ChapterEntity? = withContext(Dispatchers.IO) { chapterDao.getById(chapterId) }

    suspend fun getChapters(mangaId: Long): List<ChapterEntity> = withContext(Dispatchers.IO) { chapterDao.getByManga(mangaId) }

    suspend fun chapterCounts(mangaId: Long): Pair<Int, Int> = withContext(Dispatchers.IO) {
        chapterDao.countForManga(mangaId) to chapterDao.countReadForManga(mangaId)
    }

    /** يرتب قائمة الفصول المختارة ترتيباً طبيعياً حسب الرقم الموجود في اسم الملف */
    fun sortChaptersNaturally(chapters: List<PickedChapter>): List<PickedChapter> {
        return chapters.sortedWith(compareBy(NaturalOrderComparator) { it.displayName })
    }

    /**
     * ينشئ مانهوا جديدة كاملة: ينسخ الغلاف والفصول إلى التخزين الداخلي ثم يحفظ كل شيء في قاعدة البيانات.
     * onProgress يُستدعى بنسبة مئوية من 0 إلى 1 أثناء نسخ الفصول.
     */
    suspend fun createManga(
        title: String,
        description: String,
        coverUri: Uri?,
        chapters: List<PickedChapter>,
        onProgress: (Float) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val mangaId = mangaDao.insert(
            MangaEntity(title = title, description = description, coverPath = null)
        )
        val folderName = fileStorage.folderNameFor(mangaId)

        val coverPath = coverUri?.let { fileStorage.copyCoverImage(folderName, it) }

        val entities = mutableListOf<ChapterEntity>()
        chapters.forEachIndexed { index, picked ->
            val path = fileStorage.copyChapterFile(folderName, picked.uri, picked.displayName)
            if (path != null) {
                entities.add(
                    ChapterEntity(
                        mangaId = mangaId,
                        title = picked.title,
                        filePath = path,
                        orderIndex = index
                    )
                )
            }
            onProgress((index + 1) / chapters.size.toFloat())
        }
        chapterDao.insertAll(entities)

        if (coverPath != null) {
            mangaDao.update(mangaDao.getById(mangaId)!!.copy(coverPath = coverPath))
        }
        mangaId
    }

    /** يضيف فصولاً جديدة إلى مانهوا موجودة مسبقاً، مستأنفاً الترقيم من آخر فصل محفوظ */
    suspend fun addChaptersToExisting(
        mangaId: Long,
        chapters: List<PickedChapter>,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val folderName = fileStorage.folderNameFor(mangaId)
        val startIndex = (chapterDao.getMaxOrderIndex(mangaId) ?: -1) + 1
        val entities = mutableListOf<ChapterEntity>()
        chapters.forEachIndexed { i, picked ->
            val path = fileStorage.copyChapterFile(folderName, picked.uri, picked.displayName)
            if (path != null) {
                entities.add(
                    ChapterEntity(
                        mangaId = mangaId,
                        title = picked.title,
                        filePath = path,
                        orderIndex = startIndex + i
                    )
                )
            }
            onProgress((i + 1) / chapters.size.toFloat())
        }
        chapterDao.insertAll(entities)
    }

    suspend fun updateMangaInfo(manga: MangaEntity, newCoverUri: Uri?) = withContext(Dispatchers.IO) {
        var updated = manga
        if (newCoverUri != null) {
            val folderName = fileStorage.folderNameFor(manga.id)
            val newPath = fileStorage.copyCoverImage(folderName, newCoverUri)
            if (newPath != null) updated = updated.copy(coverPath = newPath)
        }
        mangaDao.update(updated)
    }

    suspend fun deleteManga(manga: MangaEntity) = withContext(Dispatchers.IO) {
        mangaDao.delete(manga)
        fileStorage.deleteMangaFolder(fileStorage.folderNameFor(manga.id))
    }

    suspend fun markChapterRead(chapter: ChapterEntity) = withContext(Dispatchers.IO) {
        chapterDao.update(chapter.copy(isRead = true, scrollPercent = 1f))
    }

    suspend fun saveReadingProgress(mangaId: Long, chapter: ChapterEntity, scrollPercent: Float) = withContext(Dispatchers.IO) {
        val markRead = scrollPercent >= 0.97f
        chapterDao.update(chapter.copy(scrollPercent = scrollPercent, isRead = chapter.isRead || markRead))
        mangaDao.updateLastRead(mangaId, chapter.id, scrollPercent, System.currentTimeMillis())
    }

    suspend fun getNextChapter(mangaId: Long, currentOrder: Int): ChapterEntity? =
        withContext(Dispatchers.IO) { chapterDao.getNextChapter(mangaId, currentOrder) }

    suspend fun getPreviousChapter(mangaId: Long, currentOrder: Int): ChapterEntity? =
        withContext(Dispatchers.IO) { chapterDao.getPreviousChapter(mangaId, currentOrder) }

    suspend fun reorderChapters(chapters: List<ChapterEntity>) = withContext(Dispatchers.IO) {
        chapterDao.updateAll(chapters.mapIndexed { index, c -> c.copy(orderIndex = index) })
    }

    suspend fun deleteChapter(chapter: ChapterEntity) = withContext(Dispatchers.IO) {
        chapterDao.delete(chapter)
    }

    fun chapterFileExists(path: String?) = fileStorage.fileExists(path)
}
