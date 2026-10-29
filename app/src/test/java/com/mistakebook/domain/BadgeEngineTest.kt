package com.mistakebook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 徽章判定引擎单测（任务书 T10 硬性要求：每枚徽章正反两例）。
 *
 * 引擎是纯函数，这里穷举每枚徽章的解锁/未解锁边界与进度计算。
 */
class BadgeEngineTest {

    private val today = LocalDate.of(2026, 10, 27)

    private fun empty() = BadgeStats(
        totalQuestions = 0,
        masteredQuestions = 0,
        totalReviews = 0,
        maxDailyEntries = 0,
        studyDays = emptySet(),
        clearedKnowledgePoints = 0,
        firstWordRecorded = false,
        masteredWords = 0
    )

    private fun unlockedKeys(stats: BadgeStats) =
        BadgeEngine.evaluate(stats, today).filter { it.unlocked }.map { it.badge.key }.toSet()

    private fun progressOf(stats: BadgeStats, badge: Badge) =
        BadgeEngine.evaluate(stats, today).first { it.badge == badge }

    // ---- 入门：第一次录入 ----

    @Test
    fun `first question unlocks on first entry`() {
        assertTrue(Badge.FIRST_QUESTION.key in unlockedKeys(empty().copy(totalQuestions = 1)))
        assertTrue(Badge.FIRST_QUESTION.key !in unlockedKeys(empty()))
        assertEquals(1, progressOf(empty().copy(totalQuestions = 1), Badge.FIRST_QUESTION).current)
    }

    // ---- 百题斩：累计 100 ----

    @Test
    fun `hundred questions boundary`() {
        assertFalse(
            Badge.HUNDRED_QUESTIONS.key in unlockedKeys(empty().copy(totalQuestions = 99))
        )
        assertTrue(
            Badge.HUNDRED_QUESTIONS.key in unlockedKeys(empty().copy(totalQuestions = 100))
        )
        assertEquals(
            99,
            progressOf(empty().copy(totalQuestions = 99), Badge.HUNDRED_QUESTIONS).current
        )
    }

    // ---- 毕业典礼：累计 10 道毕业 ----

    @Test
    fun `ten mastered boundary`() {
        assertFalse(Badge.TEN_MASTERED.key in unlockedKeys(empty().copy(masteredQuestions = 9)))
        assertTrue(Badge.TEN_MASTERED.key in unlockedKeys(empty().copy(masteredQuestions = 10)))
    }

    // ---- 温故知新：累计 100 次复习 ----

    @Test
    fun `hundred reviews boundary`() {
        assertFalse(Badge.HUNDRED_REVIEWS.key in unlockedKeys(empty().copy(totalReviews = 99)))
        assertTrue(Badge.HUNDRED_REVIEWS.key in unlockedKeys(empty().copy(totalReviews = 100)))
        assertEquals(50, progressOf(empty().copy(totalReviews = 50), Badge.HUNDRED_REVIEWS).current)
    }

    // ---- 七日之约 / 一月之诺：连续学习天数 ----

    @Test
    fun `seven day streak counts consecutive days including today`() {
        val days = (0L until 7L).map { today.minusDays(it) }.toSet()
        assertTrue(Badge.SEVEN_DAY_STREAK.key in unlockedKeys(empty().copy(studyDays = days)))
        assertTrue(Badge.THIRTY_DAY_STREAK.key !in unlockedKeys(empty().copy(studyDays = days)))
        assertEquals(7, progressOf(empty().copy(studyDays = days), Badge.SEVEN_DAY_STREAK).current)
    }

    @Test
    fun `thirty day streak needs full month`() {
        val days = (0L until 30L).map { today.minusDays(it) }.toSet()
        assertTrue(Badge.THIRTY_DAY_STREAK.key in unlockedKeys(empty().copy(studyDays = days)))
        val twentyNine = (0L until 29L).map { today.minusDays(it) }.toSet()
        assertFalse(
            Badge.THIRTY_DAY_STREAK.key in unlockedKeys(empty().copy(studyDays = twentyNine))
        )
    }

    @Test
    fun `streak gap breaks the chain`() {
        // 今天、昨天有，前天缺 → 连续 2 天
        val days = setOf(today, today.minusDays(1), today.minusDays(3))
        assertEquals(2, BadgeEngine.streakDays(days, today))
        assertFalse(Badge.SEVEN_DAY_STREAK.key in unlockedKeys(empty().copy(studyDays = days)))
    }

    @Test
    fun `streak tolerates today not yet studied`() {
        // 昨天起连续 7 天（今天还没开始学习）仍算 7 天——不把坚持清零
        val days = (1L until 8L).map { today.minusDays(it) }.toSet()
        assertEquals(7, BadgeEngine.streakDays(days, today))
        assertTrue(Badge.SEVEN_DAY_STREAK.key in unlockedKeys(empty().copy(studyDays = days)))
    }

    @Test
    fun `empty study days yield zero streak`() {
        assertEquals(0, BadgeEngine.streakDays(emptySet(), today))
        assertFalse(Badge.SEVEN_DAY_STREAK.key in unlockedKeys(empty()))
    }

    // ---- 知识点清零 ----

    @Test
    fun `knowledge cleared on first cleared point`() {
        assertTrue(
            Badge.KNOWLEDGE_CLEARED.key in unlockedKeys(
                empty().copy(clearedKnowledgePoints = 1)
            )
        )
        assertFalse(Badge.KNOWLEDGE_CLEARED.key in unlockedKeys(empty()))
    }

    // ---- 单日十连 ----

    @Test
    fun `ten in one day boundary`() {
        assertFalse(Badge.TEN_IN_ONE_DAY.key in unlockedKeys(empty().copy(maxDailyEntries = 9)))
        assertTrue(Badge.TEN_IN_ONE_DAY.key in unlockedKeys(empty().copy(maxDailyEntries = 10)))
    }

    // ---- 词汇类 ----

    @Test
    fun `first word on first vocab record`() {
        assertTrue(Badge.FIRST_WORD.key in unlockedKeys(empty().copy(firstWordRecorded = true)))
        assertFalse(Badge.FIRST_WORD.key in unlockedKeys(empty()))
    }

    @Test
    fun `hundred words boundary`() {
        assertFalse(Badge.HUNDRED_WORDS.key in unlockedKeys(empty().copy(masteredWords = 99)))
        assertTrue(Badge.HUNDRED_WORDS.key in unlockedKeys(empty().copy(masteredWords = 100)))
    }

    // ---- 综合：顺序与规模 ----

    @Test
    fun `evaluate returns every badge in order`() {
        val results = BadgeEngine.evaluate(empty(), today)
        assertEquals(Badge.entries.size, results.size)
        assertEquals(
            Badge.entries.map { it.key },
            results.map { it.badge.key }
        )
    }

    @Test
    fun `all badges unlock with saturated stats`() {
        // v0.0.57 目录扩到 48 枚，最高档到「千题 / 五百掌握 / 五千复习 /
        // 一年坚持 / 单日三十 / 十关清零 / 五百词」。饱和值随之抬高。
        val stats = empty().copy(
            totalQuestions = 1000,
            masteredQuestions = 500,
            totalReviews = 5000,
            maxDailyEntries = 30,
            studyDays = (0L until 365L).map { today.minusDays(it) }.toSet(),
            clearedKnowledgePoints = 10,
            firstWordRecorded = true,
            masteredWords = 500
        )
        assertEquals(Badge.entries.size, unlockedKeys(stats).size)
    }
}
