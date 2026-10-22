package com.mistakebook.wordbook.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WordbookDao {

    @Query("SELECT * FROM word_progress")
    fun observeAllProgress(): Flow<List<WordProgress>>

    @Query("SELECT * FROM word_progress WHERE word = :word LIMIT 1")
    suspend fun getProgress(word: String): WordProgress?

    @Query("SELECT * FROM word_progress WHERE isWrongBook = 1 ORDER BY updatedAt DESC")
    fun observeWrongBook(): Flow<List<WordProgress>>

    @Query("SELECT * FROM word_progress WHERE everWrong = 1 AND isWrongBook = 0 ORDER BY updatedAt DESC")
    fun observeEverWrong(): Flow<List<WordProgress>>

    @Query("SELECT * FROM word_progress WHERE isMastered = 1 ORDER BY updatedAt DESC")
    fun observeMastered(): Flow<List<WordProgress>>

    /** 生词本（手动收藏）。按收藏更新时间倒序，后收藏的排前面。 */
    @Query("SELECT * FROM word_progress WHERE isFavorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorite(): Flow<List<WordProgress>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: WordProgress)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllProgress(list: List<WordProgress>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudyLog(log: WordStudyLog)

    @Query("SELECT COUNT(DISTINCT word) FROM word_study_log WHERE answeredAt >= :startOfDayMs")
    fun observeStudiedTodayCount(startOfDayMs: Long): Flow<Int>

    /** 近 N 天的作答流水。聚合（按天分组、正确率）在 Kotlin 侧做，避免 SQL 方言差异。 */
    @Query("SELECT * FROM word_study_log WHERE answeredAt >= :sinceMs ORDER BY answeredAt ASC")
    suspend fun logsSince(sinceMs: Long): List<WordStudyLog>

    @Query("SELECT COUNT(*) FROM word_progress WHERE level >= 2")
    fun observeMasteredCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM word_progress WHERE isWrongBook = 1")
    fun observeWrongBookCount(): Flow<Int>

    /** 生词本收藏数（错题本页子标签角标用）。 */
    @Query("SELECT COUNT(*) FROM word_progress WHERE isFavorite = 1")
    fun observeFavoriteCount(): Flow<Int>

    @Query("DELETE FROM word_study_log")
    suspend fun clearLogs()
}
