package com.mistakebook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 复习推进规则（T1 复习闭环的领域层）。
 *
 * 这条链路曾经是死代码：ReviewRules 全库无调用方，nextReviewAt 从不推进，
 * 所有未掌握题永远「待复习」。闭环接回来后，这里就是推进语义的唯一权威，
 * 仓库层 applyReviewResult 只做转发——所以规则本身必须有独立测试钉死。
 */
class ReviewProgressionTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 24)

    // ---------- nextStage ----------

    @Test
    fun `答对进入下一轮`() {
        assertEquals(1, ReviewRules.nextStage(0, ReviewResult.CORRECT))
        assertEquals(4, ReviewRules.nextStage(3, ReviewResult.CORRECT))
    }

    @Test
    fun `答对到达上限后不再涨`() {
        assertEquals(ReviewRules.MAX_STAGE, ReviewRules.nextStage(ReviewRules.MAX_STAGE, ReviewResult.CORRECT))
        assertEquals(ReviewRules.MAX_STAGE, ReviewRules.nextStage(ReviewRules.MAX_STAGE + 5, ReviewResult.CORRECT))
    }

    @Test
    fun `答模糊保持当前轮`() {
        assertEquals(2, ReviewRules.nextStage(2, ReviewResult.VAGUE))
        assertEquals(0, ReviewRules.nextStage(0, ReviewResult.VAGUE))
    }

    @Test
    fun `答不会归零`() {
        assertEquals(0, ReviewRules.nextStage(3, ReviewResult.WRONG))
        assertEquals(0, ReviewRules.nextStage(0, ReviewResult.WRONG))
    }

    // ---------- nextReviewDay ----------

    @Test
    fun `答对按当前轮间隔推后`() {
        // 第 0 轮答对 → 间隔表第 0 项 = 1 天；第 2 轮答对 → 4 天
        assertEquals(today.plusDays(1), ReviewRules.nextReviewDay(0, ReviewResult.CORRECT, today))
        assertEquals(today.plusDays(4), ReviewRules.nextReviewDay(2, ReviewResult.CORRECT, today))
        assertEquals(today.plusDays(15), ReviewRules.nextReviewDay(4, ReviewResult.CORRECT, today))
    }

    @Test
    fun `模糊与不会都是明天回来`() {
        assertEquals(today.plusDays(1), ReviewRules.nextReviewDay(2, ReviewResult.VAGUE, today))
        assertEquals(today.plusDays(1), ReviewRules.nextReviewDay(4, ReviewResult.WRONG, today))
    }

    // ---------- statusFor 与掌握判定 ----------

    @Test
    fun `未到上限保持原状态`() {
        assertEquals(MasteryStatus.ACTIVE, ReviewRules.statusFor(0))
        assertEquals(MasteryStatus.ACTIVE, ReviewRules.statusFor(ReviewRules.MAX_STAGE - 1))
    }

    @Test
    fun `到上限标记已掌握`() {
        assertEquals(MasteryStatus.MASTERED, ReviewRules.statusFor(ReviewRules.MAX_STAGE))
        assertTrue(ReviewRules.isMastered(ReviewRules.MAX_STAGE))
        assertFalse(ReviewRules.isMastered(ReviewRules.MAX_STAGE - 1))
    }

    // ---------- 连续答对五轮的完整推进（验收 3） ----------

    @Test
    fun `连续答对五轮走完全部间隔并掌握`() {
        var stage = 0
        var day = today
        for (round in 0 until ReviewRules.MAX_STAGE) {
            day = ReviewRules.nextReviewDay(stage, ReviewResult.CORRECT, day)
            stage = ReviewRules.nextStage(stage, ReviewResult.CORRECT)
        }
        assertEquals(ReviewRules.MAX_STAGE, stage)
        // 五轮间隔合计 1+2+4+7+15 = 29 天后掌握
        assertEquals(today.plusDays(29), day)
        assertEquals(MasteryStatus.MASTERED, ReviewRules.statusFor(stage))
    }

    // ---------- 间隔表 ----------

    @Test
    fun `间隔表是艾宾浩斯经典节奏`() {
        assertEquals(listOf(1L, 2L, 4L, 7L, 15L), ReviewRules.INTERVALS_DAYS.toList())
    }
}
