package com.mistakebook.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 番茄会话跨天归属（T7）：day 必须按**开始**时刻算。
 *
 * 23:30 开始、00:15 结束的番茄属于开始那天——落库的 day 若取结束时刻，
 * startedAt 与 day 分属两天，按日聚合的图表和「今日时长」口径互相打架。
 */
class SessionDayTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `跨午夜会话归属开始那天`() {
        val startedAt = LocalDate.of(2026, 10, 23)
            .atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        // 结束时刻是次日 00:15，但 day 仍应是 10-23
        assertEquals("2026-10-23", PomodoroRecorder.sessionDay(startedAt, zone))
    }

    @Test
    fun `同日会话归属当天`() {
        val startedAt = LocalDate.of(2026, 10, 24)
            .atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-10-24", PomodoroRecorder.sessionDay(startedAt, zone))
    }

    @Test
    fun `整点边界 归属不漂移`() {
        val startedAt = LocalDate.of(2026, 10, 24)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals("2026-10-24", PomodoroRecorder.sessionDay(startedAt, zone))
    }
}
