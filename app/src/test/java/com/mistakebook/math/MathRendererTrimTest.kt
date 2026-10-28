package com.mistakebook.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.0.54 后续优化 · Task A：公式裁切的 alpha 边界。
 *
 * ## 测什么
 *
 * 裁透明边的两步被拆成两个可 JVM 单测的纯函数：
 * - [coreInkBox]：以 [INK_ALPHA_THRESHOLD] 定「核心内容」包围盒，滤掉亚阈值
 *   抗锯齿噪声（trimTransparent 的裁剪边界来源）；
 * - [trimWindow]：把核心盒外扩 [INK_MARGIN_PX] 并夹到位图边界内的纯几何。
 *
 * 同时对照 [inkBoxOf]（`alpha > 0` 的全量口径）证明分工：
 * 全量口径负责「有没有墨」（灵敏，漏判=公式消失），核心口径负责「裁到哪」
 * （稳，不被一粒远处的淡噪点带偏）。这套对照就是本任务的「数据」。
 *
 * `trimTransparent` 本身要 `Bitmap`，JVM 里造不出来，所以只测它拆出来的纯函数。
 */
class MathRendererTrimTest {

    /** ARGB：只设 alpha 通道，颜色分量固定非零，确保判定看的是 alpha。 */
    private fun px(alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or 0x00ABCDEF

    /** 按 (x,y)->alpha 造一张 ARGB_8888 的 getPixels 产物（行主序）。 */
    private fun alphaCanvas(
        width: Int,
        height: Int,
        alphaAt: (x: Int, y: Int) -> Int
    ): IntArray = IntArray(width * height) { i -> px(alphaAt(i % width, i / width)) }

    // ---------------------------------------------------------------- coreInkBox：阈值口径

    @Test
    fun `亚阈值淡点不算核心墨水`() {
        // 单像素 alpha=7（低于默认阈值 8）——旧口径会算墨，新口径滤掉
        assertNull(coreInkBox(intArrayOf(px(7)), 1, 1))
    }

    @Test
    fun `达到阈值即算核心墨水`() {
        assertEquals(InkBox(0, 0, 0, 0), coreInkBox(intArrayOf(px(8)), 1, 1))
    }

    @Test
    fun `默认阈值就是 INK_ALPHA_THRESHOLD`() {
        val below = intArrayOf(px(INK_ALPHA_THRESHOLD - 1))
        val at = intArrayOf(px(INK_ALPHA_THRESHOLD))
        assertNull("低于默认阈值应被滤掉", coreInkBox(below, 1, 1))
        assertEquals("等于默认阈值应算核心", InkBox(0, 0, 0, 0), coreInkBox(at, 1, 1))
    }

    @Test
    fun `满 alpha 单像素`() {
        assertEquals(InkBox(0, 0, 0, 0), coreInkBox(intArrayOf(px(255)), 1, 1))
    }

    @Test
    fun `自定义阈值过滤中间 alpha`() {
        val pixels = intArrayOf(px(100))
        // 阈值抬到 200：alpha=100 被滤掉
        assertNull(coreInkBox(pixels, 1, 1, alphaThreshold = 200))
        // 阈值降到 100：刚好算核心
        assertEquals(InkBox(0, 0, 0, 0), coreInkBox(pixels, 1, 1, alphaThreshold = 100))
    }

    @Test
    fun `阈值不大于0退化为全量口径`() {
        // alpha=1 在阈值 0（内部按 1 处理）下应算核心，等价于 inkBoxOf 的 alpha>0
        assertEquals(InkBox(0, 0, 0, 0), coreInkBox(intArrayOf(px(1)), 1, 1, alphaThreshold = 0))
        assertEquals(InkBox(0, 0, 0, 0), coreInkBox(intArrayOf(px(1)), 1, 1, alphaThreshold = -5))
    }

    @Test
    fun `低 alpha 噪声边框加高 alpha 中心_核心口径滤掉噪声`() {
        val pixels = alphaCanvas(60, 40) { x, y ->
            when {
                x == 0 || x == 59 || y == 0 || y == 39 -> 2   // 亚阈值噪声边框
                x in 20..39 && y in 10..29 -> 255             // 高 alpha 中心
                else -> 0
            }
        }
        // 全量口径：alpha>0，噪声边框把盒子撑到整图
        assertEquals(InkBox(0, 0, 59, 39), inkBoxOf(pixels, 60, 40))
        // 核心口径：只认中心，噪声被滤掉——裁剪更紧、公式不偏移
        assertEquals(InkBox(20, 10, 39, 29), coreInkBox(pixels, 60, 40))
    }

    @Test
    fun `全透明返回 null`() {
        assertNull(coreInkBox(alphaCanvas(50, 30) { _, _ -> 0 }, 50, 30))
    }

    @Test
    fun `全不透明返回整图包围盒`() {
        assertEquals(InkBox(0, 0, 49, 29), coreInkBox(alphaCanvas(50, 30) { _, _ -> 255 }, 50, 30))
    }

    @Test
    fun `单列核心墨水`() {
        assertEquals(
            InkBox(33, 8, 33, 31),
            coreInkBox(alphaCanvas(60, 40) { x, y -> if (x == 33 && y in 8..31) 200 else 0 }, 60, 40)
        )
    }

    @Test
    fun `单行核心墨水`() {
        assertEquals(
            InkBox(12, 17, 47, 17),
            coreInkBox(alphaCanvas(60, 40) { x, y -> if (y == 17 && x in 12..47) 200 else 0 }, 60, 40)
        )
    }

    @Test
    fun `四角核心墨水取并集`() {
        val pixels = alphaCanvas(60, 40) { x, y ->
            if ((x == 0 || x == 59) && (y == 0 || y == 39)) 255 else 0
        }
        assertEquals(InkBox(0, 0, 59, 39), coreInkBox(pixels, 60, 40))
    }

    @Test
    fun `大位图边缘仅少数半透明像素也框得住`() {
        // 300x200，只有两粒 alpha=100（>=阈值）的淡点靠在两端
        val pixels = alphaCanvas(300, 200) { x, y ->
            if ((x == 0 && y == 150) || (x == 299 && y == 20)) 100 else 0
        }
        assertEquals(InkBox(0, 20, 299, 150), coreInkBox(pixels, 300, 200))
    }

    @Test
    fun `数组装不下与零负尺寸返回 null`() {
        assertNull(coreInkBox(IntArray(10), 100, 100))
        assertNull(coreInkBox(IntArray(100), 0, 100))
        assertNull(coreInkBox(IntArray(100), 100, 0))
        assertNull(coreInkBox(IntArray(100), -10, 100))
        assertNull(coreInkBox(IntArray(100), 100, -10))
    }

    @Test
    fun `alpha 阈值扫描矩阵_单像素`() {
        // 覆盖 0,1,2,7,8,9,16,64,127,254,255：核心口径按 >=阈值，全量口径按 >0
        val alphas = intArrayOf(0, 1, 2, 7, 8, 9, 16, 64, 127, 254, 255)
        alphas.forEach { a ->
            val single = intArrayOf(px(a))
            val byCore = coreInkBox(single, 1, 1)
            val byFull = inkBoxOf(single, 1, 1)
            if (a >= INK_ALPHA_THRESHOLD) {
                assertEquals("alpha=$a 应算核心墨水", InkBox(0, 0, 0, 0), byCore)
            } else {
                assertNull("alpha=$a 不应算核心墨水", byCore)
            }
            if (a > 0) {
                assertEquals("alpha=$a 应算全量墨水", InkBox(0, 0, 0, 0), byFull)
            } else {
                assertNull("alpha=$a 不算全量墨水", byFull)
            }
        }
    }

    // ---------------------------------------------------------------- trimWindow：裁剪几何

    @Test
    fun `null 包围盒不裁`() {
        assertNull(trimWindow(null, marginPx = 2, width = 100, height = 100))
    }

    @Test
    fun `余量外扩并夹取`() {
        assertEquals(
            InkBox(8, 8, 22, 22),
            trimWindow(InkBox(10, 10, 20, 20), marginPx = 2, width = 100, height = 100)
        )
    }

    @Test
    fun `贴边时余量夹到边界`() {
        assertEquals(
            InkBox(0, 0, 7, 7),
            trimWindow(InkBox(0, 0, 5, 5), marginPx = 2, width = 50, height = 50)
        )
    }

    @Test
    fun `已铺满整图不裁`() {
        assertNull(trimWindow(InkBox(0, 0, 49, 49), marginPx = 2, width = 50, height = 50))
    }

    @Test
    fun `裁剪后尺寸不为 0`() {
        val win = trimWindow(InkBox(5, 5, 5, 5), marginPx = 2, width = 100, height = 100)!!
        assertEquals(InkBox(3, 3, 7, 7), win)
        assertTrue("宽必须 >=1", win.right - win.left + 1 >= 1)
        assertTrue("高必须 >=1", win.bottom - win.top + 1 >= 1)
    }

    @Test
    fun `余量为 0 时窗口等于包围盒`() {
        assertEquals(
            InkBox(3, 3, 9, 9),
            trimWindow(InkBox(3, 3, 9, 9), marginPx = 0, width = 100, height = 100)
        )
    }

    // ---------------------------------------------------------------- 集成：coreInkBox + trimWindow

    @Test
    fun `正常字形软边不被裁秃且尺寸非零`() {
        // 核心笔画 alpha=255，外围 2px 是 alpha=3 的抗锯齿软边（低于阈值，被核心口径滤掉）
        val pixels = alphaCanvas(40, 30) { x, y ->
            val inCore = x in 10..29 && y in 8..21
            val inFringe = !inCore && x in 8..31 && y in 6..23 // 核心外 2px 环
            when {
                inCore -> 255
                inFringe -> 3
                else -> 0
            }
        }
        val core = coreInkBox(pixels, 40, 30)
        assertEquals("核心口径只框笔画，软边被滤掉", InkBox(10, 8, 29, 21), core)
        val win = trimWindow(core, marginPx = INK_MARGIN_PX, width = 40, height = 30)!!
        // 余量把 2px 软边整个兜回来：软边落在 [8,31]x[6,23]，一点没被裁秃
        assertEquals(InkBox(8, 6, 31, 23), win)
        assertTrue(win.right - win.left + 1 >= 1 && win.bottom - win.top + 1 >= 1)
    }

    @Test
    fun `淡噪点不改变裁剪窗口`() {
        fun makeCanvas(stray: Boolean) = alphaCanvas(60, 40) { x, y ->
            when {
                x in 20..39 && y in 10..29 -> 255
                stray && x == 55 && y == 35 -> 2 // 远处一粒亚阈值噪点
                else -> 0
            }
        }
        val clean = makeCanvas(false)
        val noisy = makeCanvas(true)

        // 核心口径 + 余量：噪点被滤掉，两幅图裁出同一窗口
        val winClean = trimWindow(coreInkBox(clean, 60, 40), 2, 60, 40)
        val winNoisy = trimWindow(coreInkBox(noisy, 60, 40), 2, 60, 40)
        assertEquals("亚阈值噪点不应改变裁剪窗口", winClean, winNoisy)

        // 对照：全量口径会被这粒噪点把包围盒撑到 (55,35)——这正是要消除的偏移
        assertEquals(InkBox(20, 10, 39, 29), inkBoxOf(clean, 60, 40))
        assertEquals(InkBox(20, 10, 55, 35), inkBoxOf(noisy, 60, 40))
    }
}
