package com.mistakebook.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采样与断线内核（v0.0.144）的单测。
 *
 * 这一份判据现在被**两个出口**共用：会话里的 SVG（[FunctionPlotter]）
 * 和函数计算器页上可以拖动缩放的画布。两边长得不一样没关系，
 * 但「哪一段连、哪一段断」必须一模一样，所以这里钉的是**分段事实**：
 * 连续函数只能有一段、有极点的函数必须断成多段、定义域之外不许接线、
 * 坐标里不许出现 NaN/Inf。
 */
class FunctionSamplerTest {

    private fun evaluator(expression: String) = ExpressionEvaluator.parse(expression)

    /**
     * 按页面画布的方式做一遍（非正方形、纵横比随手机屏幕），
     * 好确认断线判据不依赖画布比例。
     */
    private fun segmentsOf(
        expression: String,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        widthPx: Double = 600.0,
        heightPx: Double = 380.0
    ): List<List<FunctionSampler.Pt>> {
        val values = FunctionSampler.sample(evaluator(expression), xMin, xMax)
        return FunctionSampler.segments(
            values = values,
            xMin = xMin,
            xMax = xMax,
            yMin = yMin,
            yMax = yMax,
            mapX = { x -> (x - xMin) / (xMax - xMin) * widthPx },
            mapY = { y -> heightPx - (y - yMin) / (yMax - yMin) * heightPx }
        )
    }

    @Test
    fun `采样点在整个窗口上均分且含两端`() {
        val values = FunctionSampler.sample(evaluator("x"), -2.0, 2.0, count = 5)
        assertEquals(listOf(-2.0, -1.0, 0.0, 1.0, 2.0), values)
    }

    @Test
    fun `xAt 给出端点与中点的 x`() {
        assertEquals(-3.0, FunctionSampler.xAt(0, 5, -3.0, 1.0), 1e-12)
        assertEquals(-1.0, FunctionSampler.xAt(2, 5, -3.0, 1.0), 1e-12)
        assertEquals(1.0, FunctionSampler.xAt(4, 5, -3.0, 1.0), 1e-12)
        // 只有一个点时不能除零（除零会得到 NaN，整条曲线跟着烂掉）
        assertEquals(-3.0, FunctionSampler.xAt(0, 1, -3.0, 1.0), 1e-12)
    }

    @Test
    fun `非有限值原样保留（它们是断线判定的输入）`() {
        val values = FunctionSampler.sample(evaluator("1/x"), -1.0, 1.0, count = 3)
        assertFalse("x=0 处没有定义，必须留成非有限值给断线判据", values[1].isFinite())
        assertTrue(values[0].isFinite() && values[2].isFinite())
    }

    @Test
    fun `连续函数只能是一段`() {
        val segments = segmentsOf("x^2+2x", -3.0, 1.0, yMin = -2.0, yMax = 4.0)
        assertEquals("抛物线不该被切成几段（图会看起来是虚线）", 1, segments.size)
    }

    @Test
    fun `极点两侧断成两支而不是连一条假竖线`() {
        val segments = segmentsOf("1/x", -2.0, 2.0, yMin = -10.0, yMax = 10.0)
        assertTrue("1/x 至少要断成左右两支，实际 ${segments.size} 段", segments.size >= 2)
        segments.flatten().forEach { point ->
            assertTrue("断线不彻底：坐标里有非有限值 $point", point.x.isFinite() && point.y.isFinite())
        }
    }

    @Test
    fun `正切的多条极点在窗口内各自断开`() {
        // x∈[-2,2] 里 tan(x) 有两个极点（±π/2）：跨极点的两侧绝不能被连起来，
        // 否则图上会出现两条假的竖直渐近线。段数不钉死——极点附近「爬升过快」
        // 的那一两段本来就会被判成断点（见下一条的手工构造用例），
        // 钉死数字会把判据的正常调整当成回归。
        val segments = segmentsOf("tan(x)", -2.0, 2.0, yMin = -10.0, yMax = 10.0)
        assertTrue("两个极点至少要断出三段，实际 ${segments.size} 段", segments.size >= 3)
        segments.flatten().forEach { point ->
            assertTrue("断线不彻底：坐标里有非有限值 $point", point.x.isFinite() && point.y.isFinite())
        }
    }

    @Test
    fun `跨极点的跳变必须断开（手工构造的取值）`() {
        // 前四点都在窗口内正常走动，中间突然从 -1.05 跳到 1000：这就是跨极点，
        // 必须断开——连起来学生看到的就是一条假的竖直渐近线。
        val values = listOf(-1.0, -1.2, -1.1, -1.05, 1000.0, 1.0, 1.1)
        val segments = FunctionSampler.segments(
            values = values,
            xMin = 0.0,
            xMax = 6.0,
            yMin = -2.0,
            yMax = 2.0,
            mapX = { index -> index * 10.0 },
            mapY = { y -> -y }
        )
        assertTrue("跳变必须断成多段，实际 ${segments.size} 段", segments.size >= 2)
        assertTrue(
            "断点两侧被连进同一段了",
            segments.none { segment ->
                segment.any { it.x == 10.0 } && segment.any { it.x == 60.0 }
            }
        )
    }

    @Test
    fun `两侧各自远在窗口之外且符号相反也算跨极点`() {
        // ±50 的落差只有 100（不到窗口高度 40 的 6 倍），单看幅度判不出来；
        // 但它们各自都比整个窗口还高、符号还相反，中间必然跨过一个极点
        // （`1/x` 在 0 两侧就是这个形态，只是幅度更大）。
        val values = listOf(0.0, 1.0, -50.0, 50.0, 1.0, 0.0)
        val segments = FunctionSampler.segments(
            values = values,
            xMin = 0.0,
            xMax = 5.0,
            yMin = -20.0,
            yMax = 20.0,
            mapX = { index -> index * 10.0 },
            mapY = { y -> -y }
        )
        assertEquals(2, segments.size)
        assertTrue(
            "±50 被连进同一段了（假竖直渐近线就是这么来的）",
            segments.none { segment ->
                segment.any { it.x == 20.0 } && segment.any { it.x == 30.0 }
            }
        )
    }

    @Test
    fun `定义域之外的部分不接线`() {
        // ln(x) 在 x≤0 没有定义：曲线只能从 0 右侧开始
        val segments = segmentsOf("ln(x)", -2.0, 4.0, yMin = -2.0, yMax = 3.0)
        assertEquals("左半段全是 NaN，不该连出一段来", 1, segments.size)
        val first = segments.first()
        assertTrue("不该把 x<0 的点也画上", first.all { it.x >= 0.0 })
    }

    @Test
    fun `窗口外的点夹到边框而不是丢掉`() {
        // y 窗口只有 [-1,1]，但曲线在窗口外：点要被夹到上下边框（顶到框沿），
        // 而不是被丢弃——丢掉的话曲线会在进出窗口处凭空断掉。
        val segments = segmentsOf("x^2", -3.0, 3.0, yMin = -1.0, yMax = 1.0)
        assertEquals(1, segments.size)
        val ys = segments.first().map { it.y }
        assertTrue("必须出现贴到上边框的点", ys.any { it <= 0.0 + 1e-6 })
    }

    @Test
    fun `亚像素级重复点被丢掉（但两端保留）`() {
        // 常数函数在像素上是一条水平线：中间几百个点全重合，只该留下首尾两个
        val segments = segmentsOf("3", -10.0, 10.0, yMin = -5.0, yMax = 5.0)
        assertEquals(1, segments.size)
        assertEquals("重合点要被去掉", 2, segments.first().size)
    }

    @Test
    fun `只有一两个采样点时不产生段`() {
        assertEquals(
            0,
            FunctionSampler.segments(
                values = listOf(1.0),
                xMin = 0.0,
                xMax = 1.0,
                yMin = 0.0,
                yMax = 1.0,
                mapX = { it },
                mapY = { it }
            ).size
        )
    }

    @Test
    fun `普通函数的 y 窗口包住全部取值`() {
        val finite = FunctionSampler.sample(evaluator("x^2+2x"), -3.0, 1.0)
            .filter { it.isFinite() }
        val range = FunctionSampler.resolveYRange(finite, null, null)!!
        assertTrue("y 下界要包住最小值 -1，实际 ${range.first}", range.first <= -1.0)
        assertTrue("y 上界要包住最大值 3，实际 ${range.second}", range.second >= 3.0)
    }

    @Test
    fun `有极点的函数改用分位数而不是被拉到无穷高`() {
        val finite = FunctionSampler.sample(evaluator("1/x"), -2.0, 2.0)
            .filter { it.isFinite() }
        val range = FunctionSampler.resolveYRange(finite, null, null)!!
        assertTrue(
            "窗口被极值拉到 ${range.first}..${range.second}，双曲线会被压成一条水平线",
            range.second - range.first < 100.0
        )
    }

    @Test
    fun `给了 y 范围就用给的，反了报 null`() {
        assertEquals(
            -0.5 to 0.5,
            FunctionSampler.resolveYRange(listOf(0.0, 1.0), -0.5, 0.5)
        )
        assertNull(
            "y_min 不小于 y_max 时不能硬画",
            FunctionSampler.resolveYRange(listOf(0.0, 1.0), 5.0, 1.0)
        )
    }

    @Test
    fun `常数函数给一个以它为中心的窗口`() {
        val range = FunctionSampler.resolveYRange(listOf(3.0, 3.0, 3.0), null, null)!!
        assertEquals(2.0, range.first, 1e-12)
        assertEquals(4.0, range.second, 1e-12)
    }

    @Test
    fun `没有有限值时不给窗口`() {
        assertNull(FunctionSampler.resolveYRange(emptyList(), null, null))
    }

    @Test
    fun `刻度步长落在 1-2-2_5-5 档上`() {
        assertEquals(2.5, FunctionSampler.niceStep(20.0, 8), 1e-12)
        assertEquals(2.0, FunctionSampler.niceStep(20.0, 10), 1e-12)
        assertEquals(0.5, FunctionSampler.niceStep(1.0, 2), 1e-12)
    }

    @Test
    fun `无效跨度给一个安全的默认步长`() {
        assertEquals(1.0, FunctionSampler.niceStep(0.0, 8), 1e-12)
        assertEquals(1.0, FunctionSampler.niceStep(Double.NaN, 8), 1e-12)
    }

    @Test
    fun `刻度值落在窗口内且步长有效`() {
        assertEquals(listOf(-10.0, -5.0, 0.0, 5.0, 10.0), FunctionSampler.ticks(-10.0, 10.0, 5.0))
        assertTrue("步长为 0 不能死循环", FunctionSampler.ticks(0.0, 10.0, 0.0).isEmpty())
    }

    @Test
    fun `刻度数字去掉浮点尾巴`() {
        assertEquals("0", FunctionSampler.formatTick(0.0))
        assertEquals("0", FunctionSampler.formatTick(-0.0))
        assertEquals("0.3", FunctionSampler.formatTick(0.1 + 0.2))
        assertEquals("-2.5", FunctionSampler.formatTick(-2.5))
        // 极大极小值走科学计数：否则刻度会写成一长串数字
        assertTrue(
            "大数要走科学计数，实际 ${FunctionSampler.formatTick(1_000_000.0)}",
            FunctionSampler.formatTick(1_000_000.0).contains("e+06")
        )
    }
}
