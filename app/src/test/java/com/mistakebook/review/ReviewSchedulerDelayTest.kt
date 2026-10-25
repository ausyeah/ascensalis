package com.mistakebook.review

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * F8 每日复习提醒时间个性化的 initialDelay 语义验证。
 *
 * 设置页改时间到 21:00、现在 10:00，首次延迟必须是 660 分钟
 * （当晚 21:00 触发）而不是默认的 24 小时；踩点/已过必须顺延次日，
 * 否则 initialDelay=0 会让 WorkManager 启动即触发。
 */
class ReviewSchedulerDelayTest {

    @Test
    fun `默认 20 00 从上午算延迟 10 小时`() {
        val now = LocalDateTime.of(2026, 10, 25, 10, 0, 0)
        assertEquals(600L, ReviewScheduler.initialDelayMinutes(now, 20, 0))
    }

    @Test
    fun `改到 21 00 从上午算延迟 11 小时`() {
        val now = LocalDateTime.of(2026, 10, 25, 10, 0, 0)
        assertEquals(660L, ReviewScheduler.initialDelayMinutes(now, 21, 0))
    }

    @Test
    fun `改到 21 30 含分钟精度`() {
        val now = LocalDateTime.of(2026, 10, 25, 21, 0, 0)
        assertEquals(30L, ReviewScheduler.initialDelayMinutes(now, 21, 30))
    }

    @Test
    fun `时刻已过顺延次日`() {
        val now = LocalDateTime.of(2026, 10, 25, 21, 30, 0)
        // 次日 20:00 = 22.5 小时后
        assertEquals(1350L, ReviewScheduler.initialDelayMinutes(now, 20, 0))
    }

    @Test
    fun `恰好踩点也顺延次日且至少 1 分钟`() {
        val now = LocalDateTime.of(2026, 10, 25, 20, 0, 0)
        assertEquals(1440L, ReviewScheduler.initialDelayMinutes(now, 20, 0))
    }

    @Test
    fun `踩点后 30 秒同样顺延且钳到至少 1 分钟`() {
        val now = LocalDateTime.of(2026, 10, 25, 20, 0, 30)
        // 到次日 20:00 是 23 小时 59 分 30 秒，toMinutes() 截断为 1439
        assertEquals(1439L, ReviewScheduler.initialDelayMinutes(now, 20, 0))
    }

    @Test
    fun `默认常量保持 20 00`() {
        assertEquals(20, ReviewScheduler.DEFAULT_HOUR)
        assertEquals(0, ReviewScheduler.DEFAULT_MINUTE)
    }
}
