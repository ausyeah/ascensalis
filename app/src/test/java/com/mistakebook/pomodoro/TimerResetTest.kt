package com.mistakebook.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「计时归位」的回归测试。
 *
 * 钉死两条 v0.0.21 实测出来的教训：
 * 1. 正计时点停止后**模式必须留在正计时**——早期 reset 直接 `State()`，
 *    模式被弹回番茄，界面跟着跳回番茄钟页，用户以为按错了；
 * 2. 倒计时结束后目标回到**标准默认值**（45 分钟），不继承拨轮设的
 *    自定义值，也不该是 0。
 */
class TimerResetTest {

    @Test
    fun `正计时归位后还是正计时且读数为零目标`() {
        val idle = idleStateFor(FocusMode.STOPWATCH)
        assertEquals(FocusMode.STOPWATCH, idle.mode)
        assertEquals(0L, idle.targetMs)
        assertEquals(RunStatus.IDLE, idle.status)
        assertEquals(0L, idle.baseElapsedMs)
    }

    @Test
    fun `番茄归位后回到 45 分钟标准目标`() {
        val idle = idleStateFor(FocusMode.POMODORO)
        assertEquals(FocusMode.POMODORO, idle.mode)
        assertEquals(45L * 60_000L, idle.targetMs)
        assertEquals(RunStatus.IDLE, idle.status)
        assertEquals(0L, idle.baseElapsedMs)
    }

    @Test
    fun `归位不继承上一次的自定义目标`() {
        // 拨轮设过 90 分钟也没用：结束后表盘回标准 45，不是 90
        val idle = idleStateFor(FocusMode.POMODORO)
        assertTrue(idle.targetMs < 90L * 60_000L)
    }
}
