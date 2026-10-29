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

    /**
     * 按名字找未归档任务（CSV 导入时按「任务名」对已有任务复用）。
     * 学科匹配在 Kotlin 侧做：SQLite 的 `IS` 对可为空的外键在各版本
     * 行为不完全一致，两条 `subjectId IS NULL` / `= :id` 分支更直白。
     */
    @Query("SELECT * FROM pomodoro_tasks WHERE name = :name AND archived = 0")
    suspend fun findTasksByName(name: String): List<PomodoroTask>

    @Query("SELECT * FROM pomodoro_tasks WHERE id = :id")
    suspend fun findTaskById(id: Long): PomodoroTask?

    @Insert
    suspend fun insertTask(task: PomodoroTask): Long

    @Update
    suspend fun updateTask(task: PomodoroTask)

    @Query("UPDATE pomodoro_tasks SET archived = 1 WHERE id = :id")
    suspend fun archiveTask(id: Long)

    /**
     * 硬删任务（长按编辑里的「删除」用）。
     *
     * 级联由外键负责：pomodoro_sessions.taskId 是 ON DELETE CASCADE，
     * 任务的会话记录会一起消失。这与「归档」的区别就是**统计一起没**——
     * 归档保留历史，删除是「这个任务连同它的记录都不该存在」。
     */
    @Query("DELETE FROM pomodoro_tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)

    @Query("SELECT COUNT(*) FROM pomodoro_tasks WHERE archived = 0")
    suspend fun activeTaskCount(): Int

    // ------------------------------------------------------------ 会话写入

    @Insert
    suspend fun insertSession(session: PomodoroSession): Long

    // ------------------------------------------------------------ 统计

    /** 某天总专注秒数（不含休息，见 BREAK 口径说明）。 */
    @Query(
        """
        SELECT COALESCE(SUM(durationSec), 0) FROM pomodoro_sessions
        WHERE day = :day AND mode != 'BREAK'
        """
    )
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
        WHERE day >= :fromDay AND mode != 'BREAK'
        GROUP BY day
        ORDER BY day
        """
    )
    suspend fun dailyTotalsSince(fromDay: String): List<DayTotal>

    /** 按任务聚合的累计专注秒数（可按区间过滤，fromDay 传 "" = 不限）。 */
    @Query(
        """
        SELECT t.id AS taskId, t.name AS taskName, t.subjectId AS subjectId,
               COALESCE(SUM(s.durationSec), 0) AS sec,
               COUNT(s.id) AS sessionCount
        FROM pomodoro_tasks t
        LEFT JOIN pomodoro_sessions s ON s.taskId = t.id AND s.day >= :fromDay AND s.mode != 'BREAK'
        WHERE t.archived = 0
        GROUP BY t.id
        ORDER BY sec DESC
        """
    )
    suspend fun taskTotalsOnce(fromDay: String = ""): List<TaskTotal>

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
     * 区间内按小时聚合的专注秒数（时段分布饼图用，0–23 时）。
     *
     * `startedAt` 是毫秒时间戳，`strftime` 的 'unixepoch' 先把它除到秒，
     * 'localtime' 再换成本地时区——否则饼图会整体偏成 UTC 的形状
     * （晚上八点的专注跑到下午三点那一格）。SQLite 没有 left join，
     * 所以缺的小时由调用方补 0：SQL 只返回出现过的小时。
     */
    @Query(
        """
        SELECT CAST(strftime('%H', startedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
               COALESCE(SUM(durationSec), 0) AS sec
        FROM pomodoro_sessions
        WHERE day >= :fromDay
        GROUP BY hour
        ORDER BY hour
        """
    )
    suspend fun hourlyTotalsSince(fromDay: String = ""): List<HourTotal>

    /**
     * 区间内按月聚合的专注秒数（近 12 个月折线 / 季度折线用）。
     *
     * `day` 是 'yyyy-MM-dd'，取前 7 位就是 'yyyy-MM'，直接可分组。
     */
    @Query(
        """
        SELECT substr(day, 1, 7) AS month,
               COALESCE(SUM(durationSec), 0) AS sec
        FROM pomodoro_sessions
        WHERE day >= :fromDay AND mode != 'BREAK'
        GROUP BY month
        ORDER BY month
        """
    )
    suspend fun monthlyTotalsSince(fromDay: String = ""): List<MonthTotal>

    /**
     * 有记录的日期序列（去重、降序；可按区间过滤）。
     *
     * 连续天数要在内存里判：规则是「今天还没学不算断、昨天没学才断」，
     * 这种带业务语义的判断写成 SQL 只会更难维护。
     */
    @Query("SELECT DISTINCT day FROM pomodoro_sessions WHERE day >= :fromDay AND mode != 'BREAK' ORDER BY day DESC")
    suspend fun distinctDays(fromDay: String = ""): List<String>

    /**
     * 统计口径统一带一个 fromDay（'yyyy-MM-dd'，字符串比较）。
     *
     * 传空串 `""` 即「全部」——SQLite 里任何非空日期串都 `>= ''`，
     * 这样「全部 / 今日 / 近 7 天 / 近 30 天」共用同一批 SQL，
     * 不必为每个区间各写一套。
     *
     * ## BREAK 口径（T2 休息循环）
     *
     * 两条规则，不许一刀切：
     * - **番茄计数类**（pomodoroCountOfDay / pomodoroCountSince）：
     *   只认 `mode = 'POMODORO'`，维持 v0.0.33 口径不变；
     * - **时长 / 天数 / 完成率类**（本组其余全部查询）：
     *   排除 `mode = 'BREAK'`——休息是到点自动落库的可追溯噪声，
     *   不该稀释专注时长、连续天数和完成率。
     *   **注意正计时（STOPWATCH）是真实专注，必须继续计入**：
     *   这里绝不能写成 `mode = 'POMODORO'`，否则正计时用户的全部
     *   时长会被清零（回归事故）。
     */

    /** 区间内总专注秒数（fromDay 传 "" = 全部；不含休息）。 */
    @Query(
        """
        SELECT COALESCE(SUM(durationSec), 0) FROM pomodoro_sessions
        WHERE day >= :fromDay AND mode != 'BREAK'
        """
    )
    suspend fun totalSecSince(fromDay: String): Long

    /** 区间内完成的番茄数（只认 mode = POMODORO 且 completed）。 */
    @Query(
        """
        SELECT COUNT(*) FROM pomodoro_sessions
        WHERE day >= :fromDay AND completed = 1 AND mode = 'POMODORO'
        """
    )
    suspend fun pomodoroCountSince(fromDay: String): Int

    /** 区间内总会话次数（完成率的分母；休息会话是自动落库的噪声，不稀释完成率）。 */
    @Query(
        """
        SELECT COUNT(*) FROM pomodoro_sessions
        WHERE day >= :fromDay AND mode != 'BREAK'
        """
    )
    suspend fun sessionCountSince(fromDay: String): Int

    /** 区间内完成次数（完成率的分子；不含休息）。 */
    @Query(
        """
        SELECT COUNT(*) FROM pomodoro_sessions
        WHERE completed = 1 AND day >= :fromDay AND mode != 'BREAK'
        """
    )
    suspend fun completedSessionCount(fromDay: String): Int

    /**
     * 按学科聚合的累计专注（学科分布图用，可按区间过滤）。
     *
     * subjectId 为 null 的行是「未分类」任务——不能丢，它们的时长
     * 也是用户实打实专注出来的。LEFT JOIN 而不是 INNER：某个学科下
     * 一条记录都没有时也会出现 0 秒行，图上是空槽，布局稳定。
     */
    @Query(
        """
        SELECT t.subjectId AS subjectId,
               COALESCE(SUM(s.durationSec), 0) AS sec,
               COUNT(s.id) AS sessionCount
        FROM pomodoro_tasks t
        LEFT JOIN pomodoro_sessions s ON s.taskId = t.id AND s.day >= :fromDay AND s.mode != 'BREAK'
        WHERE t.archived = 0
        GROUP BY t.subjectId
        ORDER BY sec DESC
        """
    )
    suspend fun subjectTotalsOnce(fromDay: String = ""): List<SubjectTotal>

    /** 最近若干条记录，用于「最近专注」列表（可按区间过滤）。 */
    @Query(
        """
        SELECT s.*, t.name AS taskName FROM pomodoro_sessions s
        JOIN pomodoro_tasks t ON t.id = s.taskId
        WHERE s.day >= :fromDay
        ORDER BY s.endedAt DESC
        LIMIT :limit
        """
    )
    suspend fun recentSessions(fromDay: String = "", limit: Int): List<SessionWithTask>

    /**
     * 清空全部专注记录（统计页「重置统计」用）。
     *
     * 只删会话流水，不动任务与学科——用户重置的是「统计数据」，
     * 不是「我列的任务清单」。任务清单没了，图表反而全零没意义。
     */
    @Query("DELETE FROM pomodoro_sessions")
    suspend fun clearAllSessions()
}

/** 按日聚合结果。 */
data class DayTotal(
    val day: String,
    val sec: Long
)

/** 按小时聚合结果（hour 取 0–23，缺的小时不返回行）。 */
data class HourTotal(
    val hour: Int,
    val sec: Long
)

/** 按月聚合结果（month 形如 '2026-03'）。 */
data class MonthTotal(
    val month: String,
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

/** 按学科聚合结果（subjectId 为 null = 未分类）。 */
data class SubjectTotal(
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
