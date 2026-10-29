package com.mistakebook.pomodoro.stats

import com.mistakebook.data.local.PomodoroDao
import com.mistakebook.data.local.SessionWithTask
import com.mistakebook.data.local.SubjectTotal
import java.time.LocalDate
import java.time.YearMonth

/**
 * 统计时间范围。
 *
 * 「全部」用空串 fromDay 表达（见 PomodoroDao 的口径说明），
 * 四个选项共用同一批 SQL。
 */
enum class StatsRange(val label: String) {
    TODAY("今日"),
    DAYS_7("近 7 天"),
    DAYS_30("近 30 天"),
    ALL("全部");

    /** 区间起始日（'yyyy-MM-dd'）；ALL 返回空串 = 不限。 */
    fun fromDay(today: LocalDate): String = when (this) {
        TODAY -> today.toString()
        DAYS_7 -> today.minusDays(6).toString()
        DAYS_30 -> today.minusDays(29).toString()
        ALL -> ""
    }
}

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
 *   这两个窗口固定以今天为终点，**不随统计范围变**——它们是「最近动态」，
 *   选「全部」时也看得到最近一个月的节奏。
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
        /** 区间内总会话次数，与 totalCompleted 一起算完成率。 */
        val sessionCount: Int = 0,
        /** 最近几次专注记录（新的在前）。 */
        val recent: List<SessionWithTask> = emptyList(),
        /** 快照对应的统计范围（UI 回显用）。 */
        val range: StatsRange = StatsRange.ALL,
        // ---- 新增：效仿统计页的多图表数据 ----
        /** 累计专注秒数（**不受统计范围影响**，累计卡的分子）。 */
        val totalAllSec: Long = 0L,
        /** 累计完成的番茄数（全部时间）。 */
        val totalAllCompleted: Int = 0,
        /** 有记录的自然天数（累计数据卡用）。 */
        val activeDays: Int = 0,
        /** 有记录的月份数（累计数据卡用）。 */
        val activeMonths: Int = 0,
        /** 近 12 个月每月专注秒数，**下标 0 是最远那个月**。 */
        val months12: List<Long> = List(12) { 0L },
        /** 本月按周聚合的专注秒数（本月柱状图），下标 0 = 第 1 周。 */
        val monthWeeks: List<Long> = emptyList(),
        /** 本月共几周（1–6，按「日期落在第几周」切）。 */
        val monthWeekCount: Int = 0
    ) {
        /** 目标完成率（0f~1f）。一次都没记时为 0。 */
        val completionRate: Float
            get() = if (sessionCount <= 0) 0f else totalCompleted.toFloat() / sessionCount
    }

    suspend fun snapshot(
        dao: PomodoroDao,
        range: StatsRange = StatsRange.ALL
    ): Snapshot {
        val today = LocalDate.now()
        val from = range.fromDay(today)

        val rangeSec = dao.totalSecSince(from)
        val rangePomodoros = dao.pomodoroCountSince(from)
        val totalCompleted = dao.completedSessionCount(from)
        val sessionCount = dao.sessionCountSince(from)

        // 「今日」是**固定口径**，不随统计范围变（v0.0.67 修复）：
        // 旧实现把它也挂到 range 上，统计页切到「全部」再生成打卡海报，
        // 海报上就写「今日专注 300h」。UiState.todaySec / 海报都用这里的值。
        val todaySec = dao.totalSecSince(today.toString())
        val todayPomodoros = dao.pomodoroCountSince(today.toString())

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

        val allDays = dao.distinctDays("")
        val days = dao.distinctDays(from).toSet()
        val streak = computeStreak(days, today)
        val totalAllSec = dao.totalSecSince("")
        val totalAllCompleted = dao.completedSessionCount("")

        val taskTotals = dao.taskTotalsOnce(from).map {
            TaskRow(it.taskId, it.taskName, it.subjectId, it.sec, it.sessionCount)
        }
        val subjectTotals = dao.subjectTotalsOnce(from)
        val recent = dao.recentSessions(from, RECENT_LIMIT)

        // ---- 近 12 个月：按 'yyyy-MM' 分组，缺月补 0 ----
        // 「累计活跃月」用**全量**月份查询（v0.0.67 修复）：旧实现取
        // 12 个月窗口里的键数，用满一年后「累计 X 个月」永远 ≤ 12，
        // 与旁边「累计」卡的口径不符。
        val allMonths = dao.monthlyTotalsSince("")
        val activeMonths = allMonths.size
        val monthFloor = YearMonth.from(today).minusMonths(11).toString()
        val monthMap = allMonths
            .filter { it.month >= monthFloor }
            .associate { it.month to it.sec }
        val monthKeys = (11 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()).toString() }
        val months12 = monthKeys.map { monthMap[it] ?: 0L }

        // ---- 本月按周：第 1–7 天是第 1 周，8–14 第 2 周，以此类推 ----
        val ym = YearMonth.from(today)
        val weekCount = ((ym.lengthOfMonth() - 1) / 7 + 1).coerceAtLeast(1)
        val fromMonthStart = ym.atDay(1).toString()
        val byDayMonth = dao.dailyTotalsSince(fromMonthStart).associate { it.day to it.sec }
        val monthWeeks = List(weekCount) { w ->
            val start = w * 7 + 1
            val end = minOf(start + 6, ym.lengthOfMonth())
            (start..end).sumOf { d ->
                byDayMonth[ym.atDay(d).toString()] ?: 0L
            }
        }

        return Snapshot(
            todaySec = todaySec,
            todayPomodoros = todayPomodoros,
            streakDays = streak,
            totalSec = rangeSec,
            totalCompleted = totalCompleted,
            last7 = last7,
            last30 = last30,
            taskTotals = taskTotals,
            subjectTotals = subjectTotals,
            sessionCount = sessionCount,
            recent = recent,
            range = range,
            totalAllSec = totalAllSec,
            totalAllCompleted = totalAllCompleted,
            activeDays = allDays.size,
            activeMonths = monthMap.keys.size,
            months12 = months12,
            monthWeeks = monthWeeks,
            monthWeekCount = weekCount
        )
    }

    /** 「最近专注」列表长度：够翻一屏，又不至于把统计页拉成无限列表。 */
    private const val RECENT_LIMIT = 20

    /**
     * 把近 12 个月（**下标 0 是最远那个月**）并成季度，每 3 个月一格。
     *
     * 季度折线只有 4 个点——比 12 个点的月折线陡得多，看的是
     * 「一个学期的量级」，而不是「这个月有没有偷懒」。
     *
     * 不足 12 个月时从**尾部**开始每 3 个一组、前面补 0：
     * 这样「最近的季度」永远贴着图的右端，左边的 0 表示
     * 「那时还没开始用」，语义正确。反过来补会让人以为最近的
     * 季度数据缺失。
     */
    fun quartersOf(months12: List<Long>): List<Long> {
        if (months12.isEmpty()) return emptyList()
        val padded = List((12 - months12.size).coerceAtLeast(0)) { 0L } + months12
        return padded.chunked(3).map { it.sum() }
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

    /**
     * 单日卡翻页的目标日期；**翻到未来时返回 null（不许翻）**。
     *
     * 统计记录的是已发生的事实，「后一天」翻到明天只会看到一片空白，
     * 还会让人以为数据丢了。抽成纯函数是为了钉住这条规则：
     * 用户反馈过「左右点击切换日期居然能切换到未来」。
     *
     * @param current 当前翻到的日期
     * @param delta +1 / -1 天
     * @param today 今天
     */
    fun shiftDayClamped(current: LocalDate, delta: Long, today: LocalDate): LocalDate? {
        val next = current.plusDays(delta)
        return if (next.isAfter(today)) null else next
    }
}