package com.abdo.manhwa.data.local.dao

import androidx.room.*
import com.abdo.manhwa.data.local.entity.MangaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaDao {

    @Query("SELECT * FROM manga ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MangaEntity>>

    @Query("SELECT * FROM manga WHERE id = :id")
    fun observeById(id: Long): Flow<MangaEntity?>

    @Query("SELECT * FROM manga WHERE id = :id")
    suspend fun getById(id: Long): MangaEntity?

    @Insert
    suspend fun insert(manga: MangaEntity): Long

    @Update
    suspend fun update(manga: MangaEntity)

    @Delete
    suspend fun delete(manga: MangaEntity)

    @Query("UPDATE manga SET lastReadChapterId = :chapterId, lastReadScrollPercent = :percent, lastReadAt = :time WHERE id = :mangaId")
    suspend fun updateLastRead(mangaId: Long, chapterId: Long, percent: Float, time: Long)
}
