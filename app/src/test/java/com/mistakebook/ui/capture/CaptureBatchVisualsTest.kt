package com.mistakebook.ui.capture

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连拍成功反馈的**几何**单测（v0.0.95）。
 *
 * Compose 的测量与绘制跑不了普通 JVM 单测，但「第 0 张该歪几度」
 * 「飞到一半矩形在哪」这类问题本来就不需要像素——所以这些计算被刻意
 * 写成不碰 Context、不碰 Composable 的纯函数，好在这里钉住具体数值。
 *
 * 最容易犯的错是**扇形不对称**或**飞行先缩后移**：
 * 前者看着像卡片随手摆歪了，后者会让动画中途「弹」一下。
 * 两条都用数值断言关掉，而不是靠肉眼验收。
 */
class CaptureBatchVisualsTest {

    @Test
    fun `堆叠最多显示三张`() {
        assertEquals(0, visibleStackCount(0))
        assertEquals(1, visibleStackCount(1))
        assertEquals(2, visibleStackCount(2))
        assertEquals(3, visibleStackCount(3))
        // 12 张也只在堆叠里摆 3 张，剩下的靠张数气泡表达
        assertEquals(3, visibleStackCount(12))
    }

    @Test
    fun `单张不歪不偏`() {
        assertEquals(0f, fanAngleDeg(0, 1), 0.001f)
        assertEquals(0f, fanOffsetXFraction(0, 1), 0.001f)
        assertEquals(0f, fanOffsetYFraction(0, 1), 0.001f)
    }

    @Test
    fun `两张左右对称各歪六度`() {
        assertEquals(-6f, fanAngleDeg(0, 2), 0.001f)
        assertEquals(6f, fanAngleDeg(1, 2), 0.001f)
        // 不对称的扇子看着就像摆歪了，必须严格互为相反数
        assertEquals(fanAngleDeg(0, 2), -fanAngleDeg(1, 2), 0.001f)
    }

    @Test
    fun `三张中间正两端各歪十二度`() {
        assertEquals(-12f, fanAngleDeg(0, 3), 0.001f)
        assertEquals(0f, fanAngleDeg(1, 3), 0.001f)
        assertEquals(12f, fanAngleDeg(2, 3), 0.001f)
    }

    @Test
    fun `扇形左右错开且左右对称`() {
        assertTrue(
            "越靠后的卡必须越靠右，否则叠在一起只剩一张",
            fanOffsetXFraction(0, 3) < fanOffsetXFraction(1, 3)
        )
        assertTrue(fanOffsetXFraction(1, 3) < fanOffsetXFraction(2, 3))
        assertEquals(fanOffsetXFraction(0, 3), -fanOffsetXFraction(2, 3), 0.001f)
        assertEquals(0f, fanOffsetXFraction(1, 3), 0.001f)
    }

    @Test
    fun `外侧卡片比中间下沉`() {
        assertEquals(0f, fanOffsetYFraction(1, 3), 0.001f)
        assertTrue("最外侧要沉一点，扇子底部才收得拢", fanOffsetYFraction(0, 3) > 0f)
        assertEquals(fanOffsetYFraction(0, 3), fanOffsetYFraction(2, 3), 0.001f)
    }

    @Test
    fun `飞行起点与终点严格等于两个矩形`() {
        val from = Rect(0f, 0f, 1080f, 1440f)
        val to = Rect(900f, 1900f, 980f, 1960f)

        // 端点必须完全重合：差 1px 都会让动画起手/落地抖一下
        assertEquals(from, flightRect(from, to, 0f))
        assertEquals(to, flightRect(from, to, 1f))
    }

    @Test
    fun `飞行中四边同时收敛不出现先缩后移`() {
        val from = Rect(0f, 0f, 1000f, 1000f)
        val to = Rect(800f, 1800f, 880f, 1860f)
        val half = flightRect(from, to, 0.5f)

        assertEquals(540f, half.width, 0.001f)   // (1000 + 80) / 2
        assertEquals(530f, half.height, 0.001f)  // (1000 + 60) / 2
        assertEquals(400f, half.left, 0.001f)
        assertEquals(900f, half.top, 0.001f)

        // 全程尺寸必须夹在两个端点之间：中途「缩过头再长大」就是两段感
        listOf(0.1f, 0.3f, 0.7f, 0.9f).forEach { t ->
            val r = flightRect(from, to, t)
            assertTrue("t=$t 宽度越界", r.width in 80f..1000f)
            assertTrue("t=$t 高度越界", r.height in 60f..1000f)
        }
    }

    @Test
    fun `飞行进度越界被钳住`() {
        val from = Rect(0f, 0f, 10f, 10f)
        val to = Rect(20f, 20f, 30f, 30f)

        // Animatable 理论上不会越界，但插值函数自己也要守边界，
        // 免得将来有人拿它算别的进度值时飞出屏幕
        assertEquals(from, flightRect(from, to, -1f))
        assertEquals(to, flightRect(from, to, 2f))
    }

    @Test
    fun `圆角从取景器收到缩略图`() {
        assertEquals(24f, flightCornerDp(0f), 0.001f)
        assertEquals(9f, flightCornerDp(1f), 0.001f)
        assertEquals(16.5f, flightCornerDp(0.5f), 0.001f)
    }

    @Test
    fun `缩略图解码长边按下采样倍数收敛`() {
        // 4032px 原图 → 220px 缩略图：sampleSize 只能取 2 的幂，
        // 32 倍后长边 126px，第一次落进 220 以内
        assertEquals(32, sampleSizeFor(4032, 3024, THUMB_STACK_EDGE))
        // 飞行用的 900px：8 倍后 504px
        assertEquals(8, sampleSizeFor(4032, 3024, THUMB_FLIGHT_EDGE))
        // 本来就不超标的图不放大
        assertEquals(1, sampleSizeFor(100, 100, THUMB_STACK_EDGE))
    }
}
