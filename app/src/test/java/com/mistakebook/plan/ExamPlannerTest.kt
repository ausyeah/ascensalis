package com.mistakebook.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 考前排期规划器（F3）的纯函数测试。
 */
class ExamPlannerTest {

    private val today = LocalDate.of(2026, 10, 25)

    @Test
    fun `完整阶梯天数等于间隔之和`() {
        assertEquals(29, ExamPlanner.FULL_LADDER_DAYS)
    }

    @Test
    fun `剩余自然天数按轮次递减`() {
        // stage0: 1+2+4+7+15=29; stage3: 7+15=22; stage4: 15
        assertEquals(29, ExamPlanner.remainingNaturalDays(0))
        assertEquals(22, ExamPlanner.remainingNaturalDays(3))
        assertEquals(15, ExamPlanner.remainingNaturalDays(4))
    }

    @Test
    fun `无到期题返回空计划`() {
        val plan = ExamPlanner.plan(emptyList(), today, today.plusDays(30))
        assertEquals(0, plan.dueCount)
        assertTrue(plan.newSchedule.isEmpty())
        assertFalse(plan.compressed)
    }

    @Test
    fun `考试当天或已过窗口关闭不产生排期`() {
        val plan = ExamPlanner.plan(
            listOf(PlanQuestion(1, 0)), today, today.minusDays(1)
        )
        assertEquals(0, plan.daysLeft)
        assertTrue(plan.newSchedule.isEmpty())
    }

    @Test
    fun `题目均匀摊开且不超出考前一天`() {
        val exam = today.plusDays(10) // 窗口 = 今天..考前一天 共 9 天
        val questions = (1L..20L).map { PlanQuestion(it, 0) }
        val plan = ExamPlanner.plan(questions, today, exam)
        assertEquals(20, plan.dueCount)
        assertEquals(20, plan.newSchedule.size)
        val lastDay = exam.toEpochDay() - 1
        plan.newSchedule.values.forEach { day ->
            assertTrue("排期日 $day 越过考前一天 $lastDay", day in today.toEpochDay()..lastDay)
        }
        // 摊开：每天至多 ceil(20/9)=3 道
        plan.newSchedule.values.groupBy { it }.values.forEach { group ->
            assertTrue("单日负载 ${group.size} 超过预期", group.size <= 3)
        }
        assertEquals(3, plan.dailyLoad)
    }

    @Test
    fun `窗口不足 29 天时标记需要压缩`() {
        // stage0 自然走完要 29 天，只剩 9 天窗口必然压缩
        val plan = ExamPlanner.plan(
            listOf(PlanQuestion(1, 0)), today, today.plusDays(10)
        )
        assertTrue(plan.compressed)
        assertEquals(0, plan.fullPassesPossible)
    }

    @Test
    fun `窗口充足不压缩且轮数正确`() {
        // 40 天窗口（不含考试日 39 天可用）：39/29 = 1 轮
        val plan = ExamPlanner.plan(
            listOf(PlanQuestion(1, 0)), today, today.plusDays(40)
        )
        assertFalse(plan.compressed)
        assertEquals(1, plan.fullPassesPossible)
    }

    @Test
    fun `已掌握轮次的题被排除`() {
        // stage = MAX_STAGE（毕业态）才被排除；stage5 是 15 天档待确认，仍在计划内
        val plan = ExamPlanner.plan(
            listOf(PlanQuestion(1, com.mistakebook.domain.ReviewRules.MAX_STAGE), PlanQuestion(2, 0)),
            today, today.plusDays(40)
        )
        assertEquals(1, plan.dueCount)
        assertEquals(setOf(2L), plan.newSchedule.keys)
    }

    @Test
    fun `高考预置取下一个 6 月 7 日`() {
        assertEquals(LocalDate.of(2027, 6, 7), ExamPlanner.nextGaokao(today))
        val beforeGaokao = LocalDate.of(2026, 5, 1)
        assertEquals(LocalDate.of(2026, 6, 7), ExamPlanner.nextGaokao(beforeGaokao))
    }

    @Test
    fun `考研预置是 12 月倒数第二个周六`() {
        val kaoyan = ExamPlanner.nextKaoyan(today)
        assertEquals(DayOfWeek.SATURDAY, kaoyan.dayOfWeek)
        assertEquals(12, kaoyan.monthValue)
        // 性质：它之后那个 12 月里恰有一个更晚的周六（最后一个周六）
        var lastSaturday = LocalDate.of(kaoyan.year, 12, 31)
        while (lastSaturday.dayOfWeek != DayOfWeek.SATURDAY) lastSaturday = lastSaturday.minusDays(1)
        assertEquals(kaoyan.plusWeeks(1), lastSaturday)
        assertTrue(kaoyan.isAfter(today))
    }
}
