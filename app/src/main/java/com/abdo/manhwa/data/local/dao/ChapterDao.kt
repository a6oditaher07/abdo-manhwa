package com.abdo.manhwa.data.local.dao

import androidx.room.*
import com.abdo.manhwa.data.local.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapter WHERE mangaId = :mangaId ORDER BY orderIndex ASC")
    fun observeByManga(mangaId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapter WHERE mangaId = :mangaId ORDER BY orderIndex ASC")
    suspend fun getByManga(mangaId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapter WHERE id = :id")
    suspend fun getById(id: Long): ChapterEntity?

    @Query("SELECT * FROM chapter WHERE id = :id")
    fun observeById(id: Long): Flow<ChapterEntity?>

    @Query("SELECT MAX(orderIndex) FROM chapter WHERE mangaId = :mangaId")
    suspend fun getMaxOrderIndex(mangaId: Long): Int?

    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    @Update
    suspend fun update(chapter: ChapterEntity)

    @Update
    suspend fun updateAll(chapters: List<ChapterEntity>)

    @Delete
    suspend fun delete(chapter: ChapterEntity)

    @Query("SELECT COUNT(*) FROM chapter WHERE mangaId = :mangaId")
    suspend fun countForManga(mangaId: Long): Int

    @Query("SELECT COUNT(*) FROM chapter WHERE mangaId = :mangaId AND isRead = 1")
    suspend fun countReadForManga(mangaId: Long): Int

    @Query("SELECT * FROM chapter WHERE mangaId = :mangaId AND orderIndex > :currentOrder ORDER BY orderIndex ASC LIMIT 1")
    suspend fun getNextChapter(mangaId: Long, currentOrder: Int): ChapterEntity?

    @Query("SELECT * FROM chapter WHERE mangaId = :mangaId AND orderIndex < :currentOrder ORDER BY orderIndex DESC LIMIT 1")
    suspend fun getPreviousChapter(mangaId: Long, currentOrder: Int): ChapterEntity?
}
