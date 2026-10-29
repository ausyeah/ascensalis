package com.mistakebook.plan

import com.mistakebook.domain.ReviewRules
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 考前复习排期规划器（F3）——纯函数，无 IO。
 *
 * ## 解决什么问题
 *
 * 艾宾浩斯阶梯（1/2/4/7/15 天）是"自然节奏"，不知道考试在哪天。
 * 300 道到期错题堆在今天做不完；一道 stage-0 的题自然走完要 29 天，
 * 考前 20 天才开始就注定最后一轮落在考后——白排。
 *
 * ## 规划模型
 *
 * 1. 把到期错题**均匀摊开**到 [今天, 考前一天]：第 i 道的复习日 = 今天 + i % 窗口天数。
 *    （300 道 / 20 天 = 每天 15 道，这就是 dailyLoad 的来源。）
 * 2. 对每道题检查它的**剩余自然阶梯**（[ReviewRules.INTERVALS_DAYS] 从当前轮次加到第 4 轮）
 *    是否还能在自己起始日之后走完；走不完就按比例压缩各间隔（每段至少 1 天）。
 *    压缩只影响后续轮次的节奏，下一轮复习日不变——用户复习完一题后重新跑规划即可滚动修正。
 */
data class PlanQuestion(
    val id: Long,
    val stage: Int
)

data class ExamPlan(
    val examDate: LocalDate,
    val today: LocalDate,
    /** 含今天（今天算第 1 天）。考试当天/已过 = 窗口关闭。 */
    val daysLeft: Int,
    val dueCount: Int,
    /** 距考试还能"自然"完整过几轮（整阶梯 29 天一轮）。0 = 不压缩来不及。 */
    val fullPassesPossible: Int,
    /** 摊开后每天要复习的题量（向上取整）。 */
    val dailyLoad: Int,
    /** 有任何一题的阶梯需要压缩才能收进考前。 */
    val compressed: Boolean,
    /** questionId → 新 nextReviewAt（epochDay）。空 = 无需调整。 */
    val newSchedule: Map<Long, Long>
)

object ExamPlanner {

    /** 完整阶梯天数：1+2+4+7+15。展示"还能过几轮"用。（非常量表达式，不能 const。） */
    val FULL_LADDER_DAYS: Int = ReviewRules.INTERVALS_DAYS.sum().toInt()

    /**
     * 规划 [questions]（应传"当前到期未掌握"的题）。
     *
     * @param windowDays 可用复习窗口（含今天，不含考试日）。窗口 ≤0 时返回的
     *   newSchedule 为空——没有可排的余量，摊开无从谈起。
     */
    fun plan(questions: List<PlanQuestion>, today: LocalDate, examDate: LocalDate): ExamPlan {
        val daysLeft = (examDate.toEpochDay() - today.toEpochDay()).toInt()
        val active = questions.filter { it.stage in 0 until ReviewRules.MAX_STAGE }
        val windowDays = (daysLeft - 1).coerceAtLeast(0) // 考前一天是最后复习日

        val daysLeftSafe = daysLeft.coerceAtLeast(0)
        val base = ExamPlan(
            examDate = examDate,
            today = today,
            daysLeft = daysLeftSafe,
            dueCount = active.size,
            fullPassesPossible = if (windowDays <= 0) 0 else windowDays / FULL_LADDER_DAYS,
            dailyLoad = if (windowDays <= 0) 0 else ceil(active.size.toDouble() / windowDays).toInt(),
            compressed = false,
            newSchedule = emptyMap()
        )
        if (windowDays <= 0 || active.isEmpty()) return base

        var anyCompressed = false
        val schedule = HashMap<Long, Long>(active.size)
        active.forEachIndexed { index, question ->
            // 均匀摊开：第 i 题落在第 i % windowDays 天，窗口头尾都有人
            val offset = index % windowDays
            val naturalRemaining = remainingNaturalDays(question.stage)
            val slack = windowDays - offset
            if (naturalRemaining > slack) anyCompressed = true
            schedule[question.id] = today.toEpochDay() + offset
        }
        return base.copy(compressed = anyCompressed, newSchedule = schedule)
    }

    /** stage s 走到 MASTERED 还需要的自然天数（第 s 轮间隔 + … + 第 4 轮间隔）。 */
    internal fun remainingNaturalDays(stage: Int): Int =
        ReviewRules.INTERVALS_DAYS.drop(stage.coerceIn(0, ReviewRules.INTERVALS_DAYS.lastIndex))
            .sum().toInt()

    // ===== 考试日期预置 =====

    /** 高考：下一个 6 月 7 日（今年已过取明年）。 */
    fun nextGaokao(today: LocalDate): LocalDate {
        val thisYear = LocalDate.of(today.year, 6, 7)
        return if (thisYear.isAfter(today)) thisYear else LocalDate.of(today.year + 1, 6, 7)
    }

    /**
     * 考研初试：12 月的倒数第二个周六（近年惯例）。
     * 惯例可能变动，预置只是省一次手输，用户可自定义覆盖。
     */
    fun nextKaoyan(today: LocalDate): LocalDate {
        fun secondLastSaturdayOfDecember(year: Int): LocalDate {
            var date = LocalDate.of(year, 12, 31)
            while (date.dayOfWeek != DayOfWeek.SATURDAY) date = date.minusDays(1)
            return date.minusWeeks(1)
        }
        val thisYear = secondLastSaturdayOfDecember(today.year)
        return if (thisYear.isAfter(today)) thisYear else secondLastSaturdayOfDecember(today.year + 1)
    }
}
