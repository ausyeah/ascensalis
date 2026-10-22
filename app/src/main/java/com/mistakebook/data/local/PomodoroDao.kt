package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mistakebook.data.local.entities.PomodoroSession
import com.mistakebook.data.local.entities.PomodoroTask
import kotlinx.coroutines.flow.Flow

/**
 * 番茄钟数据访问。
 *
 * ## 统计为什么直接 SQL 聚合而不是预聚合表
 *
 * 一个人的专注记录一年也就几千条，Room 的 SUM / GROUP BY 在这种量级
 * 上是微秒级。预聚合表换来的是「每次改计时逻辑都要同步维护两处写入」，
 * 收益远不抵复杂度。所以：
 * - 时长/番茄数/按任务排行 → 实时聚合查询；
 * - 连续天数（streak）→ 只取去重的日期序列，在内存里判连续（逻辑直观、
 *   且要处理「今天还没学不算断」这种业务规则，SQL 写出来反而没人看得懂）。
 */
@Dao
interface PomodoroDao {

    // ------------------------------------------------------------ 任务

    @Query("SELECT * FROM pomodoro_tasks WHERE archived = 0 ORDER BY sortOrder, id")
    fun observeTasks(): Flow<List<PomodoroTask>>

    @Query("SELECT * FROM pomodoro_tasks ORDER BY sortOrder, id")
    fun observeAllTasks(): Flow<List<PomodoroTask>>

    @Insert
    suspend fun insertTask(task: PomodoroTask): Long

    @Update
    suspend fun updateTask(task: PomodoroTask)

    @Query("UPDATE pomodoro_tasks SET archived = 1 WHERE id = :id")
    suspend fun archiveTask(id: Long)

    @Query("SELECT COUNT(*) FROM pomodoro_tasks WHERE archived = 0")
    suspend fun activeTaskCount(): Int

    // ------------------------------------------------------------ 会话写入

    @Insert
    suspend fun insertSession(session: PomodoroSession): Long

    // ------------------------------------------------------------ 统计

    /** 某天总专注秒数。 */
    @Query("SELECT COALESCE(SUM(durationSec), 0) FROM pomodoro_sessions WHERE day = :day")
    suspend fun totalSecOfDay(day: String): Long

    /** 某天完成的番茄数（只认走完目标的那几次）。 */
    @Query(
        """
        SELECT COUNT(*) FROM pomodoro_sessions
        WHERE day = :day AND completed = 1 AND mode = 'POMODORO'
        """
    )
    suspend fun pomodoroCountOfDay(day: String): Int

    /** 近 N 天（含今天）按日聚合的专注秒数，缺的天不返回行，由调用方补零。 */
    @Query(
        """
        SELECT day, SUM(durationSec) AS sec FROM pomodoro_sessions
        WHERE day >= :fromDay
        GROUP BY day
        ORDER BY day
        """
    )
    suspend fun dailyTotalsSince(fromDay: String): List<DayTotal>

    /** 按任务聚合的累计专注秒数（不限时间），一次性读取。 */
    @Query(
        """
        SELECT t.id AS taskId, t.name AS taskName, t.subjectId AS subjectId,
               COALESCE(SUM(s.durationSec), 0) AS sec,
               COUNT(s.id) AS sessionCount
        FROM pomodoro_tasks t
        LEFT JOIN pomodoro_sessions s ON s.taskId = t.id
        WHERE t.archived = 0
        GROUP BY t.id
        ORDER BY sec DESC
        """
    )
    suspend fun taskTotalsOnce(): List<TaskTotal>

    /** 按任务聚合的累计专注秒数（不限时间）。 */
    @Query(
        """
        SELECT t.id AS taskId, t.name AS taskName, t.subjectId AS subjectId,
               COALESCE(SUM(s.durationSec), 0) AS sec,
               COUNT(s.id) AS sessionCount
        FROM pomodoro_tasks t
        LEFT JOIN pomodoro_sessions s ON s.taskId = t.id
        WHERE t.archived = 0
        GROUP BY t.id
        ORDER BY sec DESC
        """
    )
    fun observeTaskTotals(): Flow<List<TaskTotal>>

    /** 某天按任务聚合（今日分布用）。 */
    @Query(
        """
        SELECT t.id AS taskId, t.name AS taskName, t.subjectId AS subjectId,
               COALESCE(SUM(s.durationSec), 0) AS sec,
               COUNT(s.id) AS sessionCount
        FROM pomodoro_tasks t
        JOIN pomodoro_sessions s ON s.taskId = t.id AND s.day = :day
        GROUP BY t.id
        ORDER BY sec DESC
        """
    )
    suspend fun taskTotalsOfDay(day: String): List<TaskTotal>

    /**
     * 有记录的日期序列（去重、升序）。
     *
     * 连续天数要在内存里判：规则是「今天还没学不算断、昨天没学才断」，
     * 这种带业务语义的判断写成 SQL 只会更难维护。
     */
    @Query("SELECT DISTINCT day FROM pomodoro_sessions ORDER BY day DESC")
    suspend fun distinctDays(): List<String>

    /** 累计专注秒数与总次数（全时段）。 */
    @Query("SELECT COALESCE(SUM(durationSec), 0) FROM pomodoro_sessions")
    suspend fun totalSecAll(): Long

    @Query("SELECT COUNT(*) FROM pomodoro_sessions WHERE completed = 1")
    suspend fun completedSessionCount(): Int

    /** 最近若干条记录，用于「最近专注」列表。 */
    @Query(
        """
        SELECT s.*, t.name AS taskName FROM pomodoro_sessions s
        JOIN pomodoro_tasks t ON t.id = s.taskId
        ORDER BY s.endedAt DESC
        LIMIT :limit
        """
    )
    suspend fun recentSessions(limit: Int): List<SessionWithTask>

    /** 删任务时的级联由外键处理；这里只用于清空某个任务的记录（暂未使用）。 */
    @Query("DELETE FROM pomodoro_sessions WHERE taskId = :taskId")
    suspend fun deleteSessionsOf(taskId: Long)
}

/** 按日聚合结果。 */
data class DayTotal(
    val day: String,
    val sec: Long
)

/** 按任务聚合结果。 */
data class TaskTotal(
    val taskId: Long,
    val taskName: String,
    val subjectId: Long?,
    val sec: Long,
    val sessionCount: Int
)

/** 会话 + 任务名（列表展示用）。 */
data class SessionWithTask(
    val id: Long,
    val taskId: Long,
    val mode: String,
    val day: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationSec: Long,
    val targetSec: Long,
    val completed: Boolean,
    val taskName: String
)
