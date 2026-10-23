package com.mistakebook.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计时模式的纯逻辑测试。
 *
 * 重点在两处**数据兼容**上：
 * - 旧版本（v0.0.20 之前）落库的会话里可能有 mode = 'TIMER' 的记录，
 *   [FocusMode.fromName] 必须能兜住，不能抛异常让恢复流程崩掉；
 * - 模式从三种砍到两种后，长按循环不能漏掉任何一个。
 */
class FocusModeTest {

    @Test
    fun `只剩番茄与正计时两种模式`() {
        assertEquals(2, FocusMode.entries.size)
        assertTrue(FocusMode.entries.contains(FocusMode.POMODORO))
        assertTrue(FocusMode.entries.contains(FocusMode.STOPWATCH))
    }

    @Test
    fun `旧数据的 TIMER 回退为番茄而不是抛异常`() {
        assertEquals(FocusMode.POMODORO, FocusMode.fromName("TIMER"))
        assertEquals(FocusMode.POMODORO, FocusMode.fromName("完全不认识的名字"))
        assertEquals(FocusMode.STOPWATCH, FocusMode.fromName("STOPWATCH"))
    }

    @Test
    fun `长按循环覆盖两种模式`() {
        assertEquals(FocusMode.STOPWATCH, FocusMode.next(FocusMode.POMODORO))
        assertEquals(FocusMode.POMODORO, FocusMode.next(FocusMode.STOPWATCH))
    }

    @Test
    fun `只有番茄有目标时长`() {
        assertTrue(FocusMode.POMODORO.hasTarget)
        assertEquals(45L * 60_000L, FocusMode.POMODORO.defaultTargetMs())
        assertTrue(!FocusMode.STOPWATCH.hasTarget)
        assertEquals(0L, FocusMode.STOPWATCH.defaultTargetMs())
    }
}
