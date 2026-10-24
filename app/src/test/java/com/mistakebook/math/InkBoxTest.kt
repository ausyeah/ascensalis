package com.mistakebook.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 不透明像素包围盒的求解。
 *
 * ## 背景
 *
 * v0.0.30 真机事故：公式最右端被整块切掉（`\{x_n\}` 的右花括号消失、
 * `x_n > 0` 的 0 被竖向切成两半）。根因是 math.html 的安全 padding
 * 在批量路径上不生效 + 字体 block 期度量竞态。
 *
 * 修复分两半：JS 侧加四边安全 padding + 等字体就绪后再测量（防被切），
 * Kotlin 侧 [inkBoxOf] / `trimTransparent` 裁掉透明边（防撑高位图）。
 * 这个测试守的是后半截——**裁得准**：
 * 少裁一个像素，公式边缘就剩一圈毛边；多裁一个，抗锯齿边缘被啃掉。
 *
 * ## 为什么这里能测
 *
 * `Bitmap` 在 JVM 单测里是空壳，造不出来。所以包围盒求解被拆成
 * 只收 `IntArray` 的纯函数（`getPixels` 的产物），测试直接造数组驱动，
 * 语义（ARGB、alpha 在高 8 位）在测试里自己实现，
 * 而不是复制一份被测代码的逻辑。
 */
class InkBoxTest {

    /** ARGB：只设 alpha 通道，颜色分量固定一个非零值，确保判定看的是 alpha。 */
    private fun px(alpha: Int): Int = (alpha shl 24) or 0x00ABCDEF

    /** 画一块实心矩形墨水，其余透明。 */
    private fun canvas(
        width: Int,
        height: Int,
        ink: (x: Int, y: Int) -> Boolean
    ): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        if (ink(x, y)) px(255) else px(0)
    }

    private fun box(width: Int, height: Int, ink: (x: Int, y: Int) -> Boolean): InkBox? =
        inkBoxOf(canvas(width, height, ink), width, height)

    // ------------------------------------------------------------------
    // 一、语义：透明 = 没有墨水
    // ------------------------------------------------------------------

    @Test
    fun `全透明返回null`() {
        assertNull("整张图没有一个不透明像素", box(200, 80) { _, _ -> false })
    }

    @Test
    fun `数组装不下这张图时返回null`() {
        // width×height 超出了数组长度：宁可不裁，也不能按错位的行列去算
        val short = IntArray(10)
        assertNull(inkBoxOf(short, 100, 100))
    }

    @Test
    fun `零尺寸和负尺寸返回null`() {
        assertNull(inkBoxOf(IntArray(100), 0, 100))
        assertNull(inkBoxOf(IntArray(100), 100, 0))
        assertNull(inkBoxOf(IntArray(100), -10, 100))
        assertNull(inkBoxOf(IntArray(100), 100, -10))
    }

    /**
     * alpha = 0 就是没有墨水，**与 RGB 分量无关**。
     *
     * 全透明区域在抖动后照样能出一堆非黑颜色，
     * 若按 RGB 判会把一大片噪点也圈进包围盒。
     */
    @Test
    fun `颜色非零但alpha为零不算墨水`() {
        val pixels = IntArray(4 * 4) { 0x00FFFFFF } // 全白但全透明
        assertNull(inkBoxOf(pixels, 4, 4))
    }

    /**
     * alpha > 0 就算墨水，**不是 alpha == 255**。
     *
     * 抗锯齿边缘只有几个 alpha，圈进来才能把毛边裁干净；
     * 漏掉的话裁完还是有一圈若隐若现的边框。
     */
    @Test
    fun `极低alpha也算墨水`() {
        val pixels = IntArray(4 * 4).also { it[5] = px(1) } // 单像素 alpha=1
        assertEquals(InkBox(1, 1, 1, 1), inkBoxOf(pixels, 4, 4))
    }

    // ------------------------------------------------------------------
    // 二、精度：包围盒必须紧贴墨水（这是这个测试存在的理由）
    // ------------------------------------------------------------------

    @Test
    fun `单像素墨水的包围盒就是它自己`() {
        val w = 60
        val h = 40
        assertEquals(
            InkBox(left = 37, top = 21, right = 37, bottom = 21),
            box(w, h) { x, y -> x == 37 && y == 21 }
        )
    }

    @Test
    fun `实心矩形的包围盒等于矩形`() {
        assertEquals(
            InkBox(left = 10, top = 5, right = 49, bottom = 34),
            box(60, 40) { x, y -> x in 10..49 && y in 5..34 }
        )
    }

    /**
     * 分散的多块墨水取**并集**包围盒。
     *
     * 真实公式就是这样：主体 + 右上角的上标 + 右下角的下标，
     * 中间大片透明。取并集才框得住全部笔画。
     */
    @Test
    fun `分散墨迹取并集包围盒`() {
        assertEquals(
            InkBox(left = 3, top = 2, right = 55, bottom = 38),
            box(60, 40) { x, y ->
                (x in 3..20 && y in 2..12) || (x in 50..55 && y in 30..38)
            }
        )
    }

    /** 贴住四条边的墨水：闭区间，边界值必须被算进来。 */
    @Test
    fun `贴边墨水的闭区间边界`() {
        assertEquals(
            InkBox(left = 0, top = 0, right = 59, bottom = 39),
            box(60, 40) { x, y ->
                (y == 0 && x == 0) || (y == 39 && x == 59) // 只画两个对角
            }
        )
    }

    /**
     * 细到只有一列/一行的墨水也要找到。
     *
     * 分式的横线就是一根 1px 的横线，根号的钩是一小段折线——
     * 漏掉它们，裁完的位图会把横线切掉一半。
     */
    @Test
    fun `单列和单行墨水`() {
        // 只有第 33 列有墨水（竖直 1px 线）
        assertEquals(
            InkBox(left = 33, top = 8, right = 33, bottom = 31),
            box(60, 40) { x, y -> x == 33 && y in 8..31 }
        )
        // 只有第 17 行有墨水（水平 1px 线）
        assertEquals(
            InkBox(left = 12, top = 17, right = 47, bottom = 17),
            box(60, 40) { x, y -> y == 17 && x in 12..47 }
        )
    }

    /** 1×1 图：最小可裁剪单位。 */
    @Test
    fun `单像素图`() {
        assertNull(inkBoxOf(IntArray(1), 1, 1))
        assertEquals(InkBox(0, 0, 0, 0), inkBoxOf(intArrayOf(px(255)), 1, 1))
    }

    /**
     * 非方图、且墨水不在几何中心：
     * 行列索引不能搞反（按行主序存，错了会整体转置）。
     */
    @Test
    fun `行列主序不能搞反`() {
        // 200 宽 × 20 高的扁图，墨水在左上角一小块
        assertEquals(
            InkBox(left = 5, top = 3, right = 25, bottom = 15),
            box(200, 20) { x, y -> x in 5..25 && y in 3..15 }
        )
    }
}
