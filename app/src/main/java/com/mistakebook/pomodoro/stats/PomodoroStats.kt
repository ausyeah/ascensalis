package com.mistakebook.pomodoro.stats

import com.mistakebook.data.local.PomodoroDao
import java.time.LocalDate

/**
 * 番茄钟统计。
 *
 * ## 口径说明（写死在这里，避免各处各算各的）
 *
 * - **专注时长**：只累加**实际计时秒数**，暂停不算。不足 1 分钟的那次
 *   在落库前就被丢掉（ViewModel 里判断），所以这里不必再过滤。
 * - **番茄数**：只认 `mode = POMODORO` 且 `completed` 的那几次。
 *   定时/正计时不进番茄数——它们是不同的心智模型，混在一起数字就虚了。
 * - **连续天数（streak）**：从今天往回数；今天还没学不算断（今天还剩十几个小时），
 *   只要昨天/前天…连续有记录就一直数下去。
 * - **近 7 天**：含今天，缺的天补 0，保证柱状图永远是 7 根柱子。
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
        val taskTotals: List<TaskRow> = emptyList()
    )

    suspend fun snapshot(dao: PomodoroDao): Snapshot {
        val today = LocalDate.now()
        val todayKey = today.toString()
        val from = today.minusDays(6).toString()

        val todaySec = dao.totalSecOfDay(todayKey)
        val todayPomodoros = dao.pomodoroCountOfDay(todayKey)
        val totalSec = dao.totalSecAll()
        val totalCompleted = dao.completedSessionCount()

        val byDay = dao.dailyTotalsSince(from).associate { it.day to it.sec }
        val last7 = (0..6).map { offset ->
            byDay[today.minusDays(offset.toLong()).toString()] ?: 0L
        }

        val days = dao.distinctDays().toSet()
        val streak = computeStreak(days, today)

        val taskTotals = dao.taskTotalsOnce().map {
            TaskRow(it.taskId, it.taskName, it.subjectId, it.sec, it.sessionCount)
        }

        return Snapshot(
            todaySec = todaySec,
            todayPomodoros = todayPomodoros,
            streakDays = streak,
            totalSec = totalSec,
            totalCompleted = totalCompleted,
            last7 = last7,
            taskTotals = taskTotals
        )
    }

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
