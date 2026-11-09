package com.mistakebook.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 三种曲线的采样（v0.0.145）单测。
 *
 * 这里钉的是**几何事实**，不是实现细节：单位圆的点必须落在单位圆上、
 * 心形线在 θ=0 处的尖必须到 x=2、窗口里没有解的方程要返回空。
 * 用恒等映射（mapX/mapY 原样返回）拿到世界坐标，才好按数学判据断言。
 */
class CurveSamplerTest {

    private val identityX: (Double) -> Double = { it }
    private val identityY: (Double) -> Double = { it }

    private fun sample(
        expression: String,
        xMin: Double = -2.0,
        xMax: Double = 2.0,
        yMin: Double = -2.0,
        yMax: Double = 2.0,
        thetaMax: Double = 2 * Math.PI
    ): List<List<FunctionSampler.Pt>> = CurveSampler.segments(
        curve = PlotCurve.parse(expression),
        xMin = xMin,
        xMax = xMax,
        yMin = yMin,
        yMax = yMax,
        mapX = identityX,
        mapY = identityY,
        thetaMax = thetaMax
    )

    @Test
    fun `显函数仍然走原来那条路`() {
        // 显函数那条路按**像素**做去重（丢掉亚像素重复点），所以这里给真实的
        // 像素映射，不能像极坐标/隐函数那样用恒等映射——恒等映射会让整条
        // 抛物线被判定为「重复像素」而只剩几个点。
        val segments = CurveSampler.segments(
            curve = PlotCurve.parse("x^2"),
            xMin = -2.0, xMax = 2.0, yMin = -1.0, yMax = 5.0,
            mapX = { x -> (x + 2.0) / 4.0 * 600.0 },
            mapY = { y -> 400.0 - (y + 1.0) / 6.0 * 400.0 }
        )
        assertTrue(segments.isNotEmpty())
        val points = segments.flatten()
        assertTrue("抛物线应该有很多点，实际 ${points.size}", points.size > 100)
        // 映射后所有点都该落在画布内（y 被夹到窗口里）
        points.forEach { point ->
            assertTrue("越界：${point.x},${point.y}", point.x in -1.0..601.0 && point.y in -1.0..401.0)
        }
        // y=x² 在 [-2,2] 上取值 0..4 → 映射后 py 从 ≈333 一直到 ≈67。
        // （不直接断言「顶点那一点存在」：像素去重会丢掉移动极慢的中段点。）
        // 注意像素方向：py 从**顶部**量，所以 y=0 在 py≈333（靠下）、y=4 在 py≈67（靠上）。
        assertTrue("顶点 y=0 应在 py≈333（靠下），实际最大 py=${points.maxOf { it.y }}",
            points.maxOf { it.y } > 320)
        assertTrue("最高点 y=4 应在 py≈67（靠上），实际最小 py=${points.minOf { it.y }}",
            points.minOf { it.y } < 90)
    }

    @Test
    fun `极坐标 r 等于常数 画出来是圆`() {
        val segments = sample("r=1")
        val points = segments.flatten()
        assertTrue("圆应该有一圈点", points.size > 100)
        points.forEach { point ->
            assertEquals("每个点都要落在单位圆上", 1.0, hypot(point.x, point.y), 0.01)
        }
    }

    @Test
    fun `心形线 r 等于 1 加 cosθ 在 θ 等于 0 处到 x 等于 2`() {
        val segments = sample("r=1+cos(θ)")
        val points = segments.flatten()
        assertTrue(points.isNotEmpty())
        val maxX = points.maxOf { it.x }
        // θ=0 时 r=2，点是 (2, 0)：这是心形线最右边的尖
        assertEquals(2.0, maxX, 0.02)
        // 且左右对称（最大 x 与最小 x 之间跨 2 个单位以上）
        val minX = points.minOf { it.x }
        assertTrue("心形线应跨过 x 轴两侧，实际 [${minX}, $maxX]", minX < 0.0)
    }

    @Test
    fun `极坐标的 r 为负时按数学画在反方向`() {
        // r=-1 的轨迹仍是单位圆（只是「从反方向画」）
        val points = sample("r=-1").flatten()
        assertTrue(points.isNotEmpty())
        points.forEach { point ->
            assertEquals(1.0, hypot(point.x, point.y), 0.01)
        }
    }

    @Test
    fun `隐函数 单位圆 的点都落在单位圆上`() {
        val segments = sample("x^2+y^2=1", xMin = -2.0, xMax = 2.0, yMin = -2.0, yMax = 2.0)
        assertTrue("单位圆应该被找到", segments.isNotEmpty())
        val points = segments.flatten()
        assertTrue("网格越细点越多，至少几十个", points.size > 30)
        points.forEach { point ->
            // 网格线性插值的误差：160×160 网格在半径 1 的圆上误差很小
            assertEquals(
                "点 (${point.x}, ${point.y}) 不在单位圆上",
                1.0,
                hypot(point.x, point.y),
                0.05
            )
        }
    }

    @Test
    fun `隐函数被串成折线而不是上千条两点线段`() {
        val segments = sample("x^2+y^2=1", xMin = -2.0, xMax = 2.0, yMin = -2.0, yMax = 2.0)
        // 一圈圆会被串成少量折线，而不是上千条两点线段。
        // 为什么不是 1~2 段：取值恰好为 0 的网格顶点（这个窗口里 x=±1、y=±1
        // 正好压在网格线上）会让该处的符号判定落到「负」一侧，等值线在那附近
        // 断开——视觉上端点重合、仍是连续的一条圆，但折线被切成几段。
        assertTrue("折线数量应该少，实际 ${segments.size}", segments.size <= 12)
        assertTrue("每条折线要有足够多点", segments.maxOf { it.size } > 30)
    }

    @Test
    fun `隐函数 双曲线 有两支`() {
        val segments = sample("x^2-y^2=1", xMin = -3.0, xMax = 3.0, yMin = -3.0, yMax = 3.0)
        assertTrue("双曲线应被找到", segments.isNotEmpty())
        // 双曲线在 x 轴两侧各一支：必然存在正负 x 的点
        val points = segments.flatten()
        assertTrue(points.any { it.x > 1.0 })
        assertTrue(points.any { it.x < -1.0 })
    }

    @Test
    fun `窗口里没有解的方程返回空`() {
        val segments = sample("x^2+y^2=-1", xMin = -2.0, xMax = 2.0, yMin = -2.0, yMax = 2.0)
        assertTrue("实数域没有解，不该画东西", segments.isEmpty())
    }

    @Test
    fun `hasContent 判断窗口里有没有东西`() {
        assertTrue(
            CurveSampler.hasContent(
                PlotCurve.parse("x^2+y^2=1"), -2.0, 2.0, -2.0, 2.0
            )
        )
        assertTrue(
            !CurveSampler.hasContent(
                PlotCurve.parse("x^2+y^2=-1"), -2.0, 2.0, -2.0, 2.0
            )
        )
        // 非隐函数一律 true（它们总有东西可画）
        assertTrue(CurveSampler.hasContent(PlotCurve.parse("x^2"), -1.0, 1.0, -1.0, 1.0))
    }

    @Test
    fun `自动定 y 窗口的取值：显函数与极坐标有值，隐函数没有`() {
        assertTrue(CurveSampler.yValues(PlotCurve.parse("x^2"), -2.0, 2.0).any { it > 3.9 })
        val polarValues = CurveSampler.yValues(PlotCurve.parse("r=1"), -2.0, 2.0)
        assertTrue("极坐标要给出 r·sinθ 的取值", polarValues.any { it > 0.9 })
        assertTrue("隐函数没有取值序列", CurveSampler.yValues(PlotCurve.parse("x^2+y^2=1"), -2.0, 2.0).isEmpty())
    }

    @Test
    fun `θ 范围可以拉长（多圈玫瑰线）`() {
        // r=cos(θ/3) 的 |r| ≤ 1，所以 x、y 的延伸范围与 θ 上限无关；
        // 能看出「画得更长」的是轨迹总长度（θ 越大走过的弧越长）。
        fun pathLength(points: List<FunctionSampler.Pt>): Double =
            points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()

        val short = pathLength(sample("r=cos(θ/3)", thetaMax = 2 * Math.PI).flatten())
        val long = pathLength(sample("r=cos(θ/3)", thetaMax = 6 * Math.PI).flatten())
        assertTrue("θ 拉长后轨迹应更长（短=$short，长=$long）", long > short * 1.5)
    }
}