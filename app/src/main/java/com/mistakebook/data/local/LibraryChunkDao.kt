package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mistakebook.data.local.entities.LibraryChunkEntity
import kotlinx.coroutines.flow.Flow

/**
 * 资料库向量索引块（v0.0.113）。
 *
 * 检索是全表扫（量级几百块，暴力余弦亚毫秒），所以没有
 * `getById` 这类单条接口——用得上的只有「全量读」「按文档读写」。
 */
@Dao
interface LibraryChunkDao {

    @Query("SELECT * FROM library_chunks ORDER BY docName, chunkIndex")
    suspend fun allOnce(): List<LibraryChunkEntity>

    @Query("SELECT * FROM library_chunks ORDER BY docName, chunkIndex")
    fun observeAll(): Flow<List<LibraryChunkEntity>>

    @Query("SELECT * FROM library_chunks WHERE docName = :docName ORDER BY chunkIndex")
    suspend fun byDoc(docName: String): List<LibraryChunkEntity>

    @Query("SELECT COUNT(*) FROM library_chunks")
    suspend fun count(): Int

    /** 各文档的块数（索引状态行展示用）。 */
    @Query("SELECT docName, COUNT(*) AS chunks FROM library_chunks GROUP BY docName")
    suspend fun countsByDoc(): List<DocChunkCount>

    @Insert
    suspend fun insert(chunks: List<LibraryChunkEntity>)

    @Query("DELETE FROM library_chunks WHERE docName = :docName")
    suspend fun deleteDoc(docName: String)

    @Query("DELETE FROM library_chunks")
    suspend fun clear()
}

/** 一份文档的块数（GROUP BY 投影）。 */
data class DocChunkCount(val docName: String, val chunks: Int)
