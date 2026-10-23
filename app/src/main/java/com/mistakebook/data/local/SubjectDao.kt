package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mistakebook.data.local.entities.Subject
import kotlinx.coroutines.flow.Flow

@Dao
interface SubjectDao {

    @Query("SELECT * FROM subjects ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Subject>>

    @Query("SELECT * FROM subjects WHERE id = :id")
    suspend fun findById(id: Long): Subject?

    @Query("SELECT * FROM subjects WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Subject?

    @Insert
    suspend fun insert(subject: Subject): Long

    @Insert
    suspend fun insertAll(subjects: List<Subject>)

    @Update
    suspend fun update(subject: Subject)

    /**
     * 删除学科。
     *
     * 外键是 SET_NULL（见 PomodoroTask）：学科没了，它下面的任务
     * 全部滑到「未分类」，历史记录一条不丢。删学科不该连带删掉
     * 用户几个月的专注记录——那是不可逆的。
     */
    @Delete
    suspend fun delete(subject: Subject)

    @Query("SELECT COUNT(*) FROM subjects")
    suspend fun count(): Int
}
