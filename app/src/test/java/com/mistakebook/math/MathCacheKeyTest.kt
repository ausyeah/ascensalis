package com.mistakebook.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证 [MathRenderer] 的前景色命名空间缓存键（[MathRenderer.formatCacheKey]）及隔离特性。
 *
 * ## 背景与契约
 * - 抠出来的公式是透明背景，墨水颜色取决于前景色（浅色 #1B1F26 vs 深色 #ECE9E2）。
 * - 缓存键必须包含颜色信息，确保深浅两套位图独立共存，切换主题时无需 `evictAll()`。
 * - 纯 JVM 测试（不依赖 Android 渲染驱动）。
 */
class MathCacheKeyTest {

    private class FakeRendered(val color: String, val content: String)

    @Test
    fun `缓存键格式正确包含前景色与原始键`() {
        val lightKey = MathRenderer.formatCacheKey(MathRenderer.FG_CSS_LIGHT, "d:\\frac{1}{2}")
        val darkKey = MathRenderer.formatCacheKey(MathRenderer.FG_CSS_DARK, "d:\\frac{1}{2}")

        assertEquals("#1B1F26:d:\\frac{1}{2}", lightKey)
        assertEquals("#ECE9E2:d:\\frac{1}{2}", darkKey)
        assertNotEquals("相同公式在不同前景色下必须生成互异缓存键", lightKey, darkKey)
    }

    @Test
    fun `不同公式类型均保持格式一致`() {
        val inlineKey = MathRenderer.formatCacheKey("#112233", "i:x_i")
        val displayKey = MathRenderer.formatCacheKey("#112233", "d:\\sum x")
        val runKey = MathRenderer.formatCacheKey("#112233", "r:\\begin{aligned}y\\end{aligned}")

        assertEquals("#112233:i:x_i", inlineKey)
        assertEquals("#112233:d:\\sum x", displayKey)
        assertEquals("#112233:r:\\begin{aligned}y\\end{aligned}", runKey)
    }

    @Test
    fun `前景色隔离测试_同一公式深浅双套独立共存`() {
        // 模拟全局 LRU 缓存，键为 formatCacheKey 产出的完整 key
        val globalCache = mutableMapOf<String, FakeRendered>()

        val formulaKey = "d:\\alpha + \\beta"
        val lightCacheKey = MathRenderer.formatCacheKey(MathRenderer.FG_CSS_LIGHT, formulaKey)
        val darkCacheKey = MathRenderer.formatCacheKey(MathRenderer.FG_CSS_DARK, formulaKey)

        // 浅色模式渲染写入
        globalCache[lightCacheKey] = FakeRendered(MathRenderer.FG_CSS_LIGHT, "alpha+beta-light")
        // 深色模式渲染写入
        globalCache[darkCacheKey] = FakeRendered(MathRenderer.FG_CSS_DARK, "alpha+beta-dark")

        val req = listOf(Triple(formulaKey, "\\alpha + \\beta", true))

        // 模拟在浅色主题下取缓存
        val lightHit = cachedOf(
            cache = { key -> globalCache[MathRenderer.formatCacheKey(MathRenderer.FG_CSS_LIGHT, key)] },
            items = req
        )
        assertEquals("alpha+beta-light", lightHit[formulaKey]?.content)
        assertEquals(MathRenderer.FG_CSS_LIGHT, lightHit[formulaKey]?.color)

        // 模拟切换到深色主题下取缓存（不清空表）
        val darkHit = cachedOf(
            cache = { key -> globalCache[MathRenderer.formatCacheKey(MathRenderer.FG_CSS_DARK, key)] },
            items = req
        )
        assertEquals("alpha+beta-dark", darkHit[formulaKey]?.content)
        assertEquals(MathRenderer.FG_CSS_DARK, darkHit[formulaKey]?.color)

        // 模拟再切回浅色主题，原浅色缓存依然完好无损命中，无需重新光栅化
        val lightHitAgain = cachedOf(
            cache = { key -> globalCache[MathRenderer.formatCacheKey(MathRenderer.FG_CSS_LIGHT, key)] },
            items = req
        )
        assertEquals("alpha+beta-light", lightHitAgain[formulaKey]?.content)
    }

    @Test
    fun `未渲染颜色的公式正确报未命中而非串色`() {
        val globalCache = mutableMapOf<String, FakeRendered>()
        val formulaKey = "i:E=mc^2"
        // 只有浅色渲染过
        globalCache[MathRenderer.formatCacheKey(MathRenderer.FG_CSS_LIGHT, formulaKey)] =
            FakeRendered(MathRenderer.FG_CSS_LIGHT, "emc2")

        val req = listOf(Triple(formulaKey, "E=mc^2", false))

        // 在深色下查缓存，应为 miss
        val darkHit = cachedOf(
            cache = { key -> globalCache[MathRenderer.formatCacheKey(MathRenderer.FG_CSS_DARK, key)] },
            items = req
        )
        assertNull("深色模式下不可误命中浅色位图", darkHit[formulaKey])
    }
}
