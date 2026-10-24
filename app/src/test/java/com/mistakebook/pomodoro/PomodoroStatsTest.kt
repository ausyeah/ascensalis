package com.mistakebook.pomodoro.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 连续天数（streak）测试。
 *
 * 这是统计里唯一带业务规则的纯逻辑：「今天还没学不算断」。
 * 规则写错的表现很隐蔽——用户明明天天在学，连续天数却莫名归零，
 * 而数字是用户坚持的动力，错了比没有更糟。
 */
class PomodoroStatsTest {

    private val today = LocalDate.of(2026, 10, 23)
    private fun key(offset: Long) = today.minusDays(offset).toString()

    @Test
    fun `今天学了，从今天开始数`() {
        val days = setOf(key(0), key(1), key(2))
        assertEquals(3, PomodoroStats.computeStreak(days, today))
    }

    @Test
    fun `今天还没学，但昨天学了，不算断`() {
        val days = setOf(key(1), key(2), key(3))
        assertEquals(3, PomodoroStats.computeStreak(days, today))
    }

    @Test
    fun `昨天也没学，断`() {
        val days = setOf(key(2), key(3))
        assertEquals(0, PomodoroStats.computeStreak(days, today))
    }

    @Test
    fun `中间断一天，只数连续的那段`() {
        val days = setOf(key(0), key(1), key(3), key(4))
        assertEquals(2, PomodoroStats.computeStreak(days, today))
    }

    @Test
    fun `只有今天一条记录`() {
        assertEquals(1, PomodoroStats.computeStreak(setOf(key(0)), today))
    }

    @Test
    fun `没有任何记录`() {
        assertEquals(0, PomodoroStats.computeStreak(emptySet(), today))
    }

    // -------------------------------------------------- 单日卡翻页（不许翻未来）

    @Test
    fun `从今天往前翻一天`() {
        assertEquals(
            today.minusDays(1),
            PomodoroStats.shiftDayClamped(today, -1L, today)
        )
    }

    @Test
    fun `从过去某天往后翻一天`() {
        assertEquals(
            today,
            PomodoroStats.shiftDayClamped(today.minusDays(1), 1L, today)
        )
    }

    @Test
    fun `今天再往后翻被夹住，返回 null`() {
        // 用户反馈：「左右点击切换日期居然能切换到未来？」
        assertEquals(null, PomodoroStats.shiftDayClamped(today, 1L, today))
    }

    @Test
    fun `未来日期再往后翻同样被夹住`() {
        // 防御：万一 singleDay 已经落在未来（时钟漂移），也不许继续往后
        assertEquals(
            null,
            PomodoroStats.shiftDayClamped(today.plusDays(1), 1L, today)
        )
    }
}
