package com.mistakebook.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 划开进度与淡入曲线的边界单测（v0.0.89）。
 *
 * 纯 JVM：两个函数都只是浮点换算，不依赖 Android。
 * 钉住的是**手感**：透明度不能在刚起手时就出现（会看到图标被卡片切一半），
 * 也不能到死都不出现（划满了还是空的）。
 */
class SwipeRevealProgressTest {

    private val width = 100f

    @Test
    fun `未划动时进度与透明度都为零`() {
        assertEquals(0f, swipeRevealProgress(0f, width), 0f)
        assertEquals(0f, swipeRevealContentAlpha(swipeRevealProgress(0f, width)), 0f)
    }

    @Test
    fun `划满一整块时进度到一且完全可见`() {
        assertEquals(1f, swipeRevealProgress(-width, width), 0f)
        assertEquals(1f, swipeRevealContentAlpha(1f), 0f)
    }

    @Test
    fun `划过头按一整块收口不溢出`() {
        // 手指划得比块宽更远（或回弹过程中的越界值）都不能让透明度超过 1
        assertEquals(1f, swipeRevealProgress(-width * 3f, width), 0f)
        assertEquals(1f, swipeRevealContentAlpha(swipeRevealProgress(-width * 3f, width)), 0f)
    }

    @Test
    fun `进度与方向无关`() {
        // 向左（露出）与向右（收回）是同一个量的两种符号
        assertEquals(swipeRevealProgress(-width / 2f, width), swipeRevealProgress(width / 2f, width), 0f)
    }

    @Test
    fun `前四分之一不淡入`() {
        // 0.25 是起始线：不到这里透明度必须是 0，避免刚起手图标闪一下
        assertEquals(0f, swipeRevealContentAlpha(0.24f), 0f)
        assertEquals(0f, swipeRevealContentAlpha(swipeRevealProgress(-25f, width)), 0f)
    }

    @Test
    fun `过半时淡入到一半`() {
        // (0.5 - 0.25) / 0.75 = 1/3
        assertEquals(1f / 3f, swipeRevealContentAlpha(0.5f), 0.001f)
    }

    @Test
    fun `块宽为零时不出进度也不除零`() {
        // 理论上的防御：宽度取 0 时返回 0，而不是 NaN 让整层透明度变成不可预期
        assertEquals(0f, swipeRevealProgress(-50f, 0f), 0f)
        assertEquals(0f, swipeRevealContentAlpha(swipeRevealProgress(-50f, 0f)), 0f)
    }
}
