package com.mistakebook.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横向手势定向判定 [shouldClaimCardSwipe] 的边界单测（v0.0.89 重写）。
 *
 * 纯 JVM：函数本体只是两个绝对值比大小，不依赖 Android。
 *
 * v0.0.89 修的是「错题左滑删除没了」：v0.0.84~0.0.88 按速度给门户 Pager
 * 让道，阈值 slop/90ms ≈ 270 px/s 低于常人左滑的速度，删除永远划不出来。
 * 现在规则只有方向一条——下面每条用例都钉住这条规则的边界。
 */
class HomeScreenSwipeClaimTest {

    @Test
    fun `横向占优认领为展开删除`() {
        assertTrue(shouldClaimCardSwipe(30f, 0f))
        assertTrue(shouldClaimCardSwipe(30f, 20f))
    }

    @Test
    fun `向左向右都能认领方向只是符号`() {
        // 判定只看绝对值：从右往左（露出删除）和从左往右（收回）都归卡片管
        assertTrue(shouldClaimCardSwipe(-30f, 0f))
        assertTrue(shouldClaimCardSwipe(-30f, -10f))
    }

    @Test
    fun `纵向占优让给列表滚动`() {
        assertFalse(shouldClaimCardSwipe(0f, 60f))
        assertFalse(shouldClaimCardSwipe(20f, 60f))
    }

    @Test
    fun `横竖位移相等时保守让给滚动`() {
        // |accY| >= |accX| 的边界取等号：不让卡片认领，
        // 斜着拖 45° 时按滚动处理，用户感知更接近「手指往下走」
        assertFalse(shouldClaimCardSwipe(30f, 30f))
        assertFalse(shouldClaimCardSwipe(-30f, 30f))
    }

    @Test
    fun `判定与速度无关慢滑快滑一致认领`() {
        // 同一位移，慢拖与快甩的结论必须相同——速度分叉正是旧实现的病灶
        assertTrue(shouldClaimCardSwipe(30f, 0f))
        assertTrue(shouldClaimCardSwipe(300f, 0f))
    }
}
