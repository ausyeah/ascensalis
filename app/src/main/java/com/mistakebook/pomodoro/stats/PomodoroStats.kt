package com.mistakebook.pomodoro.stats

import com.mistakebook.data.local.PomodoroDao
import com.mistakebook.data.local.SessionWithTask
import com.mistakebook.data.local.SubjectTotal
import java.time.LocalDate

/**
 * 番茄钟统计。
 *
 * ## 口径说明（写死在这里，避免各处各算各的）
 *
 * - **专注时长**：只累加**实际计时秒数**，暂停不算。不足 1 分钟的那次
 *   在落库前就被丢掉（ViewModel 里判断），所以这里不必再过滤。
 * - **番茄数**：只认 `mode = POMODORO` 且 `completed` 的那几次。
 *   正计时不进番茄数——它们是不同的心智模型，混在一起数字就虚了。
 * - **连续天数（streak）**：从今天往回数；今天还没学不算断（今天还剩十几个小时），
 *   只要昨天/前天…连续有记录就一直数下去。
 * - **近 7 天 / 近 30 天**：含今天，缺的天补 0，保证柱状图永远是满格。
 *   数组下标 0 是**今天**，由使用方决定正序还是倒序画。
 */
object PomodoroStats {

    data class TaskRow(
        val taskId: Long,
        val taskName: String,
        val subjectId: Long?,
        val sec: Long,
        val sessionCount: Int
    )

    data class Snapshot(
        val todaySec: Long = 0L,
        val todayPomodoros: Int = 0,
        val streakDays: Int = 0,
        val totalSec: Long = 0L,
        val totalCompleted: Int = 0,
        val last7: List<Long> = List(7) { 0L },
        val last30: List<Long> = List(30) { 0L },
        val taskTotals: List<TaskRow> = emptyList(),
        /** 按学科聚合（含未分类的 null 行）。 */
        val subjectTotals: List<SubjectTotal> = emptyList(),
        /** 总会话次数，与 totalCompleted 一起算完成率。 */
        val sessionCount: Int = 0,
        /** 最近几次专注记录（新的在前）。 */
        val recent: List<SessionWithTask> = emptyList()
    ) {
        /** 目标完成率（0f~1f）。一次都没记时为 0。 */
        val completionRate: Float
            get() = if (sessionCount <= 0) 0f else totalCompleted.toFloat() / sessionCount
    }

    suspend fun snapshot(dao: PomodoroDao): Snapshot {
        val today = LocalDate.now()
        val todayKey = today.toString()

        val todaySec = dao.totalSecOfDay(todayKey)
        val todayPomodoros = dao.pomodoroCountOfDay(todayKey)
        val totalSec = dao.totalSecAll()
        val totalCompleted = dao.completedSessionCount()
        val sessionCount = dao.sessionCountAll()

        val from7 = today.minusDays(6).toString()
        val byDay7 = dao.dailyTotalsSince(from7).associate { it.day to it.sec }
        val last7 = (0..6).map { offset ->
            byDay7[today.minusDays(offset.toLong()).toString()] ?: 0L
        }

        // 30 天与 7 天共用一次查询结果会为时已晚（fromDay 不同），
        // 但两条 SQL 在这种量级都是微秒级，各查各的更直观。
        val from30 = today.minusDays(29).toString()
        val byDay30 = dao.dailyTotalsSince(from30).associate { it.day to it.sec }
        val last30 = (0..29).map { offset ->
            byDay30[today.minusDays(offset.toLong()).toString()] ?: 0L
        }

        val days = dao.distinctDays().toSet()
        val streak = computeStreak(days, today)

        val taskTotals = dao.taskTotalsOnce().map {
            TaskRow(it.taskId, it.taskName, it.subjectId, it.sec, it.sessionCount)
        }
        val subjectTotals = dao.subjectTotalsOnce()
        val recent = dao.recentSessions(RECENT_LIMIT)

        return Snapshot(
            todaySec = todaySec,
            todayPomodoros = todayPomodoros,
            streakDays = streak,
            totalSec = totalSec,
            totalCompleted = totalCompleted,
            last7 = last7,
            last30 = last30,
            taskTotals = taskTotals,
            subjectTotals = subjectTotals,
            sessionCount = sessionCount,
            recent = recent
        )
    }

    /** 「最近专注」列表长度：够翻一屏，又不至于把统计页拉成无限列表。 */
    private const val RECENT_LIMIT = 20

    /**
     * 连续天数。
     *
     * 规则：今天有记录 → 从今天开始数；今天没有但昨天有 → 从昨天开始数
     * （今天还没过完，不能算断）；否则 0。
     */
    fun computeStreak(days: Set<String>, today: LocalDate): Int {
        var cursor = if (days.contains(today.toString())) today else today.minusDays(1)
        if (!days.contains(cursor.toString())) return 0
        var count = 0
        while (days.contains(cursor.toString())) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }
}
