package com.mistakebook.pomodoro.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计页新增图表的纯逻辑测试：季度并组与时段口径。
 *
 * 这两处是「图上看到的数字」与「库里算出的数字」之间的翻译层，
 * 翻译错了不会报错、只会画出一张好看的错图——所以必须钉死。
 */
class PomodoroStatsChartTest {

    // ------------------------------------------------------------ 季度并组

    @Test
    fun `12 个月并成 4 个季度，每格 3 个月`() {
        // 下标 0 = 最远那个月
        val months = (1L..12L).toList()
        assertEquals(listOf(6L, 15L, 24L, 33L), PomodoroStats.quartersOf(months))
    }

    @Test
    fun `不足 12 个月时前面补 0，最近的季度仍贴右端`() {
        // 只有最近 4 个月有数据：0,0,0,0,10,20,30,40,50,60,70,80
        val months = listOf(0L, 0L, 0L, 0L, 10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L)
        assertEquals(listOf(0L, 30L, 120L, 210L), PomodoroStats.quartersOf(months))
    }

    @Test
    fun `刚好 6 个月也补成 4 格而不是 2 格`() {
        val months = listOf(1L, 2L, 3L, 4L, 5L, 6L)
        assertEquals(listOf(0L, 0L, 6L, 15L), PomodoroStats.quartersOf(months))
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(PomodoroStats.quartersOf(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------ 时段口径

    @Test
    fun `时段口径的起始日各自正确`() {
        val today = java.time.LocalDate.of(2026, 3, 8)
        assertEquals("2026-03-08", HourBucketRange.DAY.fromDay(today))
        assertEquals("2026-03-02", HourBucketRange.WEEK.fromDay(today))
        assertEquals("2026-03-01", HourBucketRange.MONTH.fromDay(today))
        // 空串 = 不限，与 DAO 的口径统一
        assertEquals("", HourBucketRange.ALL.fromDay(today))
    }

    @Test
    fun `周口径跨月时正确回退到上月末`() {
        val today = java.time.LocalDate.of(2026, 3, 3)
        assertEquals("2026-02-25", HourBucketRange.WEEK.fromDay(today))
    }

    @Test
    fun `四个口径互不相同`() {
        val today = java.time.LocalDate.of(2026, 6, 15)
        val set = HourBucketRange.entries.map { it.fromDay(today) }.toSet()
        assertEquals(4, set.size)
    }
}