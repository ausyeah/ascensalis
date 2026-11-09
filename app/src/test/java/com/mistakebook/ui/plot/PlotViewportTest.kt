package com.mistakebook.ui.plot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画布视口的单测（v0.0.144）。
 *
 * 手势本身测不了（要真机），但手势**算出来的是什么**可以：平移多少像素
 * 等于窗口移多少、缩放锚点下的那个点有没有漂、等比例有没有被破坏。
 */
class PlotViewportTest {

    private val square = PlotViewport(-10.0, 10.0, -5.0, 5.0)

    @Test
    fun `复位视口按画布比例铺开（单位是正方格）`() {
        val viewport = PlotViewport.default(widthPx = 400f, heightPx = 300f)
        assertEquals(-10.0, viewport.xMin, 1e-12)
        assertEquals(10.0, viewport.xMax, 1e-12)
        // 一个数据单位 = 同样多的像素：否则单位圆会被画成椭圆
        assertEquals(
            viewport.xSpan / 400.0,
            viewport.ySpan / 300.0,
            1e-12
        )
    }

    @Test
    fun `画布还没量出尺寸时退回正方形`() {
        val viewport = PlotViewport.default(widthPx = 0f, heightPx = 0f)
        assertEquals(viewport.xSpan, viewport.ySpan, 1e-12)
        assertEquals(-10.0, viewport.xMin, 1e-12)
    }

    @Test
    fun `向右拖动等于窗口左移`() {
        val panned = square.pan(dxPx = 100f, dyPx = 0f, widthPx = 400f, heightPx = 200f)
        // 位移 100px = 窗口宽度的 1/4 = 数据跨度 20 的 1/4
        assertEquals(-15.0, panned.xMin, 1e-12)
        assertEquals(5.0, panned.xMax, 1e-12)
        assertEquals("上下不动", square.yMin, panned.yMin, 1e-12)
    }

    @Test
    fun `向下拖动等于窗口上移（屏幕 y 向下）`() {
        val panned = square.pan(dxPx = 0f, dyPx = 50f, widthPx = 400f, heightPx = 200f)
        // 50px = 高度的 1/4 = 跨度 10 的 1/4 = 2.5；窗口上移，y 变大
        assertEquals(-2.5, panned.yMin, 1e-12)
        assertEquals(7.5, panned.yMax, 1e-12)
    }

    @Test
    fun `尺寸未知时不认平移`() {
        assertEquals(square, square.pan(30f, 30f, widthPx = 0f, heightPx = 200f))
    }

    @Test
    fun `缩放时锚点下的那个数据点不动`() {
        val viewport = PlotViewport(-10.0, 10.0, -10.0, 10.0)
        val zoomed = viewport.zoom(2f, focusXPx = 25f, focusYPx = 25f, widthPx = 100f, heightPx = 100f)
        // 锚点在屏幕上的 1/4 处 → 数据坐标 (-5, 5)
        assertEquals(25.0, zoomed.xToPx(-5.0, 100f), 1e-9)
        assertEquals(25.0, zoomed.yToPx(5.0, 100f), 1e-9)
        assertEquals("放大两倍后跨度是一半", 10.0, zoomed.xSpan, 1e-9)
    }

    @Test
    fun `缩放保持横纵等比例`() {
        val viewport = PlotViewport.default(widthPx = 360f, heightPx = 500f)
        val ratio = viewport.xSpan / viewport.ySpan
        val zoomed = viewport.zoom(1.7f, focusXPx = 180f, focusYPx = 250f, widthPx = 360f, heightPx = 500f)
        assertEquals("缩放不能把等比例改掉", ratio, zoomed.xSpan / zoomed.ySpan, 1e-9)
    }

    @Test
    fun `缩得太狠会被夹住，窗口不会塌成零`() {
        val zoomed = square.zoom(1e12f, focusXPx = 0f, focusYPx = 0f, widthPx = 100f, heightPx = 100f)
        assertTrue("跨度要留在可用区间里，实际 ${zoomed.xSpan}", zoomed.xSpan >= PlotViewport.MIN_SPAN - 1e-12)
        assertEquals(PlotViewport.MIN_SPAN, zoomed.xSpan, 1e-12)
    }

    @Test
    fun `无效的缩放倍数不改变视口`() {
        assertEquals(square, square.zoom(0f, 1f, 1f, 100f, 100f))
        assertEquals(square, square.zoom(-2f, 1f, 1f, 100f, 100f))
        assertEquals(square, square.zoom(Float.NaN, 1f, 1f, 100f, 100f))
    }

    @Test
    fun `数据坐标与像素坐标互相对应`() {
        assertEquals(0.0, square.xToPx(-10.0, 400f), 1e-9)
        assertEquals(400.0, square.xToPx(10.0, 400f), 1e-9)
        assertEquals("数据 y 越大，屏幕 y 越小", 0.0, square.yToPx(5.0, 200f), 1e-9)
        assertEquals(200.0, square.yToPx(-5.0, 200f), 1e-9)
    }
}
