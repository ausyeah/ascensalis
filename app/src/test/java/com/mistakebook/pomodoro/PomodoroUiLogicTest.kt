package com.mistakebook.pomodoro

import com.mistakebook.pomodoro.stats.PomodoroStats
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 番茄钟 UI 状态与通知格式的纯逻辑测试。
 *
 * 覆盖三处容易写错又不显眼的地方：
 * - 完成率：0 次专注时不能除零（会出 NaN，界面显示「NaN%」）；
 * - 轮盘目标上界：与小时位 0–12 的表达范围严格一致，
 *   界面设得出、底层存不下是最难排查的一类 bug；
 * - 通知/表盘共用的时钟格式：跨小时必须补零成 h:mm:ss。
 */
class PomodoroUiLogicTest {

    @Test
    fun `一次都没记时完成率为 0 而不是 NaN`() {
        val state = PomodoroUiState()
        assertEquals(0f, state.completionRate, 0.0001f)
    }

    @Test
    fun `完成率按会话总数算`() {
        val state = PomodoroUiState(sessionCount = 8, totalCompleted = 3)
        assertEquals(0.375f, state.completionRate, 0.0001f)
    }

    @Test
    fun `全部完成时完成率为 1`() {
        val state = PomodoroUiState(sessionCount = 5, totalCompleted = 5)
        assertEquals(1f, state.completionRate, 0.0001f)
    }

    @Test
    fun `轮盘最大目标 12 小时 59 分`() {
        assertEquals(779, PomodoroViewModel.MAX_TARGET_MINUTES)
    }

    @Test
    fun `统计快照默认值不炸`() {
        val snapshot = PomodoroStats.Snapshot()
        assertEquals(7, snapshot.last7.size)
        assertEquals(30, snapshot.last30.size)
        assertEquals(0f, snapshot.completionRate, 0.0001f)
    }

    @Test
    fun `时钟格式跨小时补零`() {
        assertEquals("00:00", PomodoroService.formatClock(0L))
        assertEquals("00:09", PomodoroService.formatClock(9_000L))
        assertEquals("25:00", PomodoroService.formatClock(25 * 60_000L))
        assertEquals("1:00:00", PomodoroService.formatClock(3_600_000L))
        assertEquals("2:05:09", PomodoroService.formatClock(2 * 3_600_000L + 5 * 60_000L + 9_000L))
        // 负数夹到 0：倒计时归零后 remaining 可能算出极小负值
        assertEquals("00:00", PomodoroService.formatClock(-500L))
    }
}
