package com.mistakebook.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横滑让道判定 [shouldYieldToPager] 的边界单测（v0.0.85）。
 *
 * 纯 JVM：函数本体是位移/用时/阈值上的纯计算，不依赖 Android。
 * slop 取 24f ≈ 8dp @ xxhdpi，速度阈值即 24/90 ≈ 0.267 px/ms。
 */
class HomeScreenYieldTest {

    private val slop = 24f

    @Test
    fun `纯纵向让给列表滚动`() {
        assertTrue(shouldYieldToPager(0f, 60f, 100L, slop))
        assertTrue(shouldYieldToPager(5f, 80f, 300L, slop))
    }

    @Test
    fun `横竖位移相等时保守让道`() {
        // |accY| >= |accX| 的边界取等号：让道
        assertTrue(shouldYieldToPager(30f, 30f, 100L, slop))
    }

    @Test
    fun `未越过 slop 时保守让道`() {
        assertTrue(shouldYieldToPager(10f, 0f, 40L, slop))
    }

    @Test
    fun `正好等于速度阈值视为甩动让道`() {
        // 位移恰好为 slop、用时恰好为窗口 90ms：速度 == slop/90 → 让道（>= 取等）
        assertTrue(shouldYieldToPager(slop, 0f, 90L, slop))
    }

    @Test
    fun `快速甩动让给 Pager 翻页`() {
        assertTrue(shouldYieldToPager(slop, 0f, 40L, slop))
        // 位移超过 slop 的更快甩动同样让道
        assertTrue(shouldYieldToPager(40f, 0f, 60L, slop))
    }

    @Test
    fun `慢速横向拖拽认领为展开删除`() {
        assertFalse(shouldYieldToPager(slop, 0f, 200L, slop))
        assertFalse(shouldYieldToPager(slop, 0f, 400L, slop))
    }

    @Test
    fun `超过超时上限后一律认领防止极端输入误判`() {
        // 速度很高（1.0 px/ms）但用时超过 400ms 上限：上限优先，仍认领
        assertFalse(shouldYieldToPager(1000f, 0f, 1000L, slop))
    }
}
