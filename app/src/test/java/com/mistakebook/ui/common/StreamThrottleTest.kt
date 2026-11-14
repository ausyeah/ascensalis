package com.mistakebook.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式 Markdown 自适应节流（v0.0.153）。
 *
 * 移植自 deepseek-ai/chat-demo 的 useThrottleMarkdownRender：
 * `下次间隔 = 上次渲染耗时 × 2`，封顶 2000ms。量的是**本机真实的
 * 渲染成本**——旗舰机高频刷新、性能一般的设备自动降频。
 *
 * 50ms 下限的来源：与生成端发布曲线（streamPublishIntervalMs 的下限）
 * 对齐——短回答必须跟手，不能在快机上高频空转。
 */
class StreamThrottleTest {

    @Test
    fun `渲染耗时乘二`() {
        assertEquals(160L, streamThrottleIntervalMs(80L))
        assertEquals(32L * 2, streamThrottleIntervalMs(32L))
    }

    @Test
    fun `慢设备封顶两秒`() {
        // 一次 1500ms 的渲染（长公式 + KaTeX 位图），下次间隔不应等比放大
        assertEquals(2000L, streamThrottleIntervalMs(1500L))
        assertEquals(2000L, streamThrottleIntervalMs(10_000L))
    }

    @Test
    fun `快设备不低于五十毫秒`() {
        assertEquals(50L, streamThrottleIntervalMs(16L))
        assertEquals(50L, streamThrottleIntervalMs(0L))
    }

    @Test
    fun `区间单调不越界`() {
        for (cost in longArrayOf(0L, 1L, 10L, 50L, 100L, 500L, 2000L, 50_000L)) {
            val interval = streamThrottleIntervalMs(cost)
            assertTrue("$cost → $interval", interval in 50..2000)
            assertEquals(interval, streamThrottleIntervalMs(cost))
        }
    }
}
