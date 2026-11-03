package com.mistakebook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 增强前的「光照均一化」（v0.0.95）。
 *
 * 用户反馈的原话是「很多时候都会非常失调，非常黑的一片片」。
 * 根因不在参数：旧实现是**全局 Otsu + 逐像素映射**，而一张室内拍的作业照
 * 左半边靠窗、右半边在阴影里，两边纸面亮度能差 60 级。单一阈值必然把其中
 * 一半判错——判错的那一半整片被推进暗部，就是「死黑」。
 *
 * 所以修复是结构性的：先把低频照度除掉（平场校正），再求阈值。
 * 这里钉的就是这条链路的三条性质：
 * 1. 阴影里的白纸必须回到白纸亮度，且落在阈值**之上**；
 * 2. 纯黑区域不能被除成灰白噪点（否则「去死黑」变成「把黑洗成灰」）；
 * 3. 少量高光不能把背景估计拉到 255（否则整格纸面被除成灰）。
 *
 * Bitmap 在 JVM 单测里是 mock，所以全部逻辑都跑在 IntArray 上——
 * 这也是这些函数被写成数组接口而不是直接吃 Bitmap 的原因。
 */
class ImageNormalizerIlluminationTest {

    @Test
    fun `阴影里的白纸被除回白纸亮度而不是死黑`() {
        val width = 240
        val height = 160
        // 左半靠窗（纸面 235），右半在阴影里（纸面 85）；9 格取 1 个墨点
        val gray = grayImage(width, height) { x, y ->
            val paper = if (x < width / 2) 235 else 85
            val ink = if (x < width / 2) 40 else 20
            if (x % 3 == 0 && y % 3 == 0) ink else paper
        }

        ImageNormalizer.flattenIllumination(gray, width, height, 1f)

        val shadowPaper = gray[10 * width + (width - 5)]
        val litPaper = gray[10 * width + 5]
        assertTrue("阴影里的纸面必须被提亮（原来是 85，旧版会把它压得更暗）", shadowPaper >= 240)
        assertTrue("亮处纸面同样推到接近纯白", litPaper >= 240)

        // 墨点必须仍然明显比纸暗，不能被一起提亮
        val shadowInk = gray[9 * width + (width - 6)]
        assertTrue("墨点必须留在暗部", shadowInk < 120)
        assertTrue("纸与墨的差距必须保住", shadowPaper - shadowInk > 120)
    }

    @Test
    fun `均一化后纸面落在阈值之上阴影不再被判成字迹`() {
        val width = 240
        val height = 160
        val gray = grayImage(width, height) { x, y ->
            val paper = if (x < width / 2) 235 else 85
            val ink = if (x < width / 2) 40 else 20
            if (x % 3 == 0 && y % 3 == 0) ink else paper
        }

        ImageNormalizer.flattenIllumination(gray, width, height, 1f)
        val threshold = ImageNormalizer.otsuThresholdOfGray(gray, onFallback = {})

        // 这就是用户看到的「一片死黑」的判据：阴影纸面落在阈值下方，
        // 就会被 [steepen] 当成字迹压到暗部。
        val shadowPaper = gray[10 * width + (width - 5)]
        val shadowInk = gray[9 * width + (width - 6)]
        assertTrue(
            "阴影里的纸面必须判成纸（阈值之上），否则就是一片死黑",
            shadowPaper > threshold
        )
        assertTrue("墨点必须判成墨（阈值之下）", shadowInk < threshold)
    }

    @Test
    fun `纯黑桌边不会被洗成灰白`() {
        val width = 200
        val height = 200
        // 外圈 20px 的黑色桌边，里面是纸
        val gray = grayImage(width, height) { x, y ->
            when {
                x < 20 || y < 20 || x >= width - 20 || y >= height - 20 -> 8
                x % 12 == 0 && y % 12 == 0 -> 40
                else -> 230
            }
        }

        ImageNormalizer.flattenIllumination(gray, width, height, 1f)

        // 没有中位数兜底时，背景会被估成 8 左右，8/8*255 直接把桌边洗成白
        val borderPixel = gray[10 * width + 100]
        assertTrue("桌边必须仍然是暗的，不能变成灰白噪点（实际 $borderPixel）", borderPixel < 60)
    }

    @Test
    fun `均匀纸面被推到纯白`() {
        val width = 120
        val height = 80
        val gray = IntArray(width * height) { 240 }

        ImageNormalizer.flattenIllumination(gray, width, height, 1f)

        assertEquals(255, gray[0])
    }

    @Test
    fun `少量高光不会把背景估计拉到最亮`() {
        val width = 320
        val height = 320
        // 5% 的镜面高光散落在全图：如果按最大值估计背景，整格纸面都会被除成灰
        val gray = grayImage(width, height) { x, y ->
            if ((x * 31 + y * 17) % 20 == 0) 255 else 200
        }

        val grid = ImageNormalizer.estimateBackgroundGrid(gray, width, height)

        val interior = grid.sample(160, 160)
        assertTrue("背景必须按分位估计而不是最大值（实际 $interior）", interior < 230)
        assertTrue("背景也不能被高光带偏到纸面以下", interior >= 195)
    }

    @Test
    fun `双线性采样在格与格之间连续且单调`() {
        val grid = ImageNormalizer.BackgroundGrid(
            width = 100,
            height = 10,
            cols = 2,
            rows = 1,
            cells = intArrayOf(100, 200)
        )

        assertEquals(100, grid.sample(0, 0))
        assertEquals(200, grid.sample(99, 0))
        // 不能取最近邻：50px 的台阶就是「一片片」的另一种形态
        assertEquals(151, grid.sample(50, 0))

        var previous = 0
        for (x in 0 until 100) {
            val value = grid.sample(x, 0)
            assertTrue("采样必须单调不减", value >= previous)
            previous = value
        }
    }

    @Test
    fun `网格长边收敛到上限且不越界`() {
        val wide = ImageNormalizer.estimateBackgroundGrid(IntArray(5000 * 10) { 200 }, 5000, 10)
        assertEquals(32, wide.cols)
        assertEquals(10, wide.rows)

        val tiny = ImageNormalizer.estimateBackgroundGrid(intArrayOf(120), 1, 1)
        assertEquals(1, tiny.cols)
        assertEquals(1, tiny.rows)
    }

    @Test
    fun `轻度档只做一半校正`() {
        assertEquals(0.5f, ImageNormalizer.illuminationBlend(1))
        assertEquals(1f, ImageNormalizer.illuminationBlend(2))
        assertEquals(1f, ImageNormalizer.illuminationBlend(3))

        // 85 / 170 * 255 = 127
        assertEquals(85, ImageNormalizer.flattenExposure(85, 170, 0f))
        assertEquals(127, ImageNormalizer.flattenExposure(85, 170, 1f))
        assertEquals(106, ImageNormalizer.flattenExposure(85, 170, 0.5f))
    }

    @Test
    fun `背景下限兜住极暗背景`() {
        // 背景 0 会让除法爆表；下限（32）保证输出仍是可显示的值，而不是除零
        assertEquals(0, ImageNormalizer.flattenExposure(0, 0, 1f))
        // 8 * 255 / 32 = 63：仍然偏暗，不会把纯黑洗成灰白
        assertEquals(63, ImageNormalizer.flattenExposure(8, 0, 1f))
    }

    // ===== 单像素映射（彩色 / 近灰） =====

    @Test
    fun `近灰像素输出不透明灰阶`() {
        val pixel = ImageNormalizer.mapPixel(200, 200, 200, target = 230, colorScale = 1.2f)

        assertEquals(0xFF, alphaOf(pixel))
        assertEquals(230, redOf(pixel))
        assertEquals(230, greenOf(pixel))
        assertEquals(230, blueOf(pixel))
    }

    @Test
    fun `浅色像素不再变成透明洞`() {
        // 饱和度 (180-140)/180 = 0.22，落在旧版 0.18~0.30 那一档——
        // 那一段的合成漏了最高字节，alpha 恒为 0，浅色笔迹在增强图上是窟窿。
        val pixel = ImageNormalizer.mapPixel(180, 140, 140, target = 0, colorScale = 1f)

        assertEquals("浅色像素必须不透明", 0xFF, alphaOf(pixel))
    }

    @Test
    fun `彩色像素保住色相`() {
        val pixel = ImageNormalizer.mapPixel(200, 60, 60, target = 0, colorScale = 0.5f)

        assertEquals(0xFF, alphaOf(pixel))
        assertEquals(100, redOf(pixel))
        assertEquals(30, greenOf(pixel))
        assertEquals(30, blueOf(pixel))
    }

    @Test
    fun `同一支红笔在明暗两处输出一致`() {
        // 亮处：像素 200,50,50，纸面 235 → 校正后 maxC 217
        // 阴影：像素 110,28,28（整体压暗 45%），纸面 129 → 校正后 maxC 217
        // 两者只除光照，不再过灰度陡坡，所以出来必须是同一个红
        val lit = ImageNormalizer.mapPixel(200, 50, 50, target = 0, colorScale = 217f / 200f)
        val shadow = ImageNormalizer.mapPixel(110, 28, 28, target = 0, colorScale = 217f / 110f)

        assertEquals(217, redOf(lit))
        assertTrue(
            "明暗两处的红笔必须一样红，否则就是用户说的「失调」",
            abs(redOf(lit) - redOf(shadow)) <= 2 &&
                abs(greenOf(lit) - greenOf(shadow)) <= 2
        )
    }

    // ===== 工具 =====

    private fun grayImage(width: Int, height: Int, value: (x: Int, y: Int) -> Int): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = value(x, y)
            }
        }
        return out
    }

    private fun alphaOf(pixel: Int): Int = (pixel ushr 24) and 0xFF
    private fun redOf(pixel: Int): Int = (pixel shr 16) and 0xFF
    private fun greenOf(pixel: Int): Int = (pixel shr 8) and 0xFF
    private fun blueOf(pixel: Int): Int = pixel and 0xFF
}