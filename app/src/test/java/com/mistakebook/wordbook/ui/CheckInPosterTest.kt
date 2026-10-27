package com.mistakebook.wordbook.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 打卡海报数据装配单测。
 *
 * 只测「海报上的数字对不对」——画得好不好看测不了（要真机/截图），
 * 但数字错了是硬伤：用户把海报发出去才发现 Day 数不对，
 * 那种错误发出去就收不回来。
 */
class CheckInPosterTest {

    private fun day(studied: Int, correct: Int, total: Int) = DayStat(
        dayMs = 1_700_000_000_000L,
        studiedCount = studied,
        correctCount = correct,
        totalCount = total
    )

    @Test
    fun `正确率取今天那条并按百分比取整`() {
        val state = WordbookUiState(
            weeklyDayStats = listOf(
                day(10, 5, 10), // 昨天：50%
                day(20, 17, 20) // 今天：85%
            )
        )
        val data = buildPosterData(state, now = 0L)
        assertEquals(85, data.accuracyPercent)
        assertEquals("85%", data.accuracyText())
    }

    @Test
    fun `今天没作答时正确率为空且不谎报 0`() {
        val state = WordbookUiState(
            weeklyDayStats = listOf(day(20, 0, 0))
        )
        val data = buildPosterData(state, now = 0L)
        assertNull(data.accuracyPercent)
        assertEquals("—", data.accuracyText())
    }

    @Test
    fun `没有任何日统计时正确率为空`() {
        val data = buildPosterData(WordbookUiState(), now = 0L)
        assertNull(data.accuracyPercent)
        assertEquals("—", data.accuracyText())
    }

    @Test
    fun `日期按中文年月日格式`() {
        // 2026-10-25 21:00 GMT+8 → 时间戳
        val data = buildPosterData(WordbookUiState(), now = 1_761_400_000_000L)
        // 具体是哪天取决于运行环境时区，只断言格式：中文年月日 + 单个「日」
        assertTrue2(data.dateText.matches(Regex("""\d{4}年\d{1,2}月\d{1,2}日""")))
    }

    @Test
    fun `连续天数与累计掌握原样进入海报`() {
        val state = WordbookUiState(streakDays = 12, masteredCount = 340, dailyGoal = 30)
        val data = buildPosterData(state, now = 0L)
        assertEquals(12, data.streakDays)
        assertEquals(340, data.masteredCount)
        assertEquals(30, data.dailyGoal)
    }

    private fun assertTrue2(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}
