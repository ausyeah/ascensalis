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
    fun `两种手动模式加一种自动休息`() {
        // T2 休息循环引入 BREAK：它是到点自动进入的模式，不算手动模式
        assertEquals(3, FocusMode.entries.size)
        assertTrue(FocusMode.entries.contains(FocusMode.POMODORO))
        assertTrue(FocusMode.entries.contains(FocusMode.STOPWATCH))
        assertTrue(FocusMode.entries.contains(FocusMode.BREAK))
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
    fun `长按循环跳过休息`() {
        // BREAK 只能由到点自动进入，绝不能被长按切出来
        assertEquals(FocusMode.POMODORO, FocusMode.next(FocusMode.BREAK))
        // 任何起点循环一圈都回得到自己，且中途不会出现 BREAK
        for (mode in listOf(FocusMode.POMODORO, FocusMode.STOPWATCH)) {
            var cur = mode
            repeat(4) { cur = FocusMode.next(cur) }
            assertEquals("从 ${'$'}mode 循环应回到自身", mode, cur)
        }
    }

    @Test
    fun `休息有目标时长`() {
        assertTrue(FocusMode.BREAK.hasTarget)
        assertEquals(10L * 60_000L, FocusMode.BREAK.defaultTargetMs())
        assertEquals("休息", FocusMode.BREAK.label)
    }

    @Test
    fun `旧数据的 BREAK 名字能被识别`() {
        // BREAK 会落库（「最近专注」可追溯），恢复/展示路径必须认得它
        assertEquals(FocusMode.BREAK, FocusMode.fromName("BREAK"))
    }

    @Test
    fun `只有番茄有目标时长`() {
        assertTrue(FocusMode.POMODORO.hasTarget)
        assertEquals(45L * 60_000L, FocusMode.POMODORO.defaultTargetMs())
        assertTrue(!FocusMode.STOPWATCH.hasTarget)
        assertEquals(0L, FocusMode.STOPWATCH.defaultTargetMs())
    }
}
