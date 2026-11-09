package com.mistakebook.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 函数绘图内核（issue #54）的单测。
 *
 * 画出来的 SVG 是要进用户眼睛和错题本记录的，所以这里钉的是**图上事实**：
 * 范围取对没有、极值点落在框内没有、极点处**断线**没有、有多条曲线时有没有
 * 图例、文本有没有转义。都是纯字符串/数值断言，不需要渲染。
 */
class FunctionPlotterTest {

    private fun ok(request: FunctionPlotter.Request): FunctionPlotter.Result.Ok {
        val result = FunctionPlotter.plot(request)
        assertTrue("期望绘制成功，实际：$result", result is FunctionPlotter.Result.Ok)
        return result as FunctionPlotter.Result.Ok
    }

    private fun failed(request: FunctionPlotter.Request): String {
        val result = FunctionPlotter.plot(request)
        assertTrue("期望绘制失败，实际成功", result is FunctionPlotter.Result.Failed)
        return (result as FunctionPlotter.Result.Failed).message
    }

    @Test
    fun `画抛物线的最小值落在自动窗口里`() {
        // 题目讨论 x∈[-3,1] 的 f(x)=x^2+2x，最小值 -1 必须看得见
        val plotted = ok(
            FunctionPlotter.Request(
                curves = listOf(FunctionPlotter.Curve("x^2+2x")),
                xMin = -3.0,
                xMax = 1.0
            )
        )
        assertTrue("y 下界要包住最小值 -1，实际 ${plotted.yMin}", plotted.yMin <= -1.0)
        assertTrue("y 上界要包住 f(1)=3，实际 ${plotted.yMax}", plotted.yMax >= 3.0)
        assertEquals(-3.0, plotted.xMin, 1e-9)
        assertEquals(1.0, plotted.xMax, 1e-9)
    }

    @Test
    fun `SVG 结构完整且能被清洗器认出来`() {
        val svg = ok(FunctionPlotter.Request(listOf(FunctionPlotter.Curve("sin(x)")))).svg
        assertTrue("要带 xmlns，否则 AndroidSVG 直接解析失败", svg.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.endsWith("</svg>"))
        assertEquals("只能有一个根，不能嵌套出第二张图", 1, Regex("<svg\\b").findAll(svg).count())
        assertFalse("不要脚本（会被清洗掉，也说明我们写脏了）", svg.contains("<script"))
        assertTrue("要有曲线路径", svg.contains("<path d=\"M"))
        assertTrue("要有坐标轴", svg.contains("stroke=\"#37474F\""))
    }

    @Test
    fun `刻度数字不带浮点尾巴`() {
        val svg = ok(
            FunctionPlotter.Request(
                listOf(FunctionPlotter.Curve("x")),
                xMin = -1.0,
                xMax = 1.0
            )
        ).svg
        assertFalse("0.1 不能写成 0.30000000000000004 这种尾巴", svg.contains("000000"))
        assertTrue("要出现刻度数字", svg.contains(">-1</text>") || svg.contains(">1</text>"))
    }

    @Test
    fun `极点处断线而不是连一条假的竖线`() {
        // 1/x 在 x=0 两侧分别是 -inf 与 +inf：连起来就是一条假的竖直渐近线
        val svg = ok(
            FunctionPlotter.Request(
                listOf(FunctionPlotter.Curve("1/x")),
                xMin = -2.0,
                xMax = 2.0
            )
        ).svg
        val path = Regex("<path d=\"([^\"]*)\"").find(svg)!!.groupValues[1]
        val subpaths = Regex("M").findAll(path).count()
        assertTrue("至少要断成两段（左右两支），实际 $subpaths 段", subpaths >= 2)
        assertFalse("不能出现 NaN 坐标", path.contains("NaN"))
        assertFalse("不能出现 inf 坐标", path.contains("Infinity"))
    }

    @Test
    fun `连续函数不许被判成断开（断线不能过度）`() {
        // 与上一条成对：判据收紧到 4 倍分位之后，抛物线/正弦仍必须是一整条线，
        // 否则每段曲线都会被切成碎片，图看起来是虚线。
        val svg = ok(
            FunctionPlotter.Request(
                listOf(FunctionPlotter.Curve("x^2+2x")),
                xMin = -3.0,
                xMax = 1.0
            )
        ).svg
        val path = Regex("<path d=\"([^\"]*)\"").find(svg)!!.groupValues[1]
        assertEquals("连续函数只能是一段", 1, Regex("M").findAll(path).count())
    }

    @Test
    fun `定义域之外的部分不画`() {
        // ln(x) 在 x<0 没有定义：左侧不该有曲线，也不该连过来
        val svg = ok(
            FunctionPlotter.Request(
                listOf(FunctionPlotter.Curve("ln(x)")),
                xMin = -2.0,
                xMax = 4.0
            )
        ).svg
        val path = Regex("<path d=\"([^\"]*)\"").find(svg)!!.groupValues[1]
        assertTrue(path.startsWith("M"))
        assertFalse(path.contains("NaN"))
    }

    @Test
    fun `多条曲线各自一种颜色并带图例`() {
        val svg = ok(
            FunctionPlotter.Request(
                curves = listOf(
                    FunctionPlotter.Curve("x^2", "y=x^2"),
                    FunctionPlotter.Curve("2x+1", "y=2x+1")
                ),
                xMin = -3.0,
                xMax = 3.0
            )
        ).svg
        assertEquals("两条曲线两条 path", 2, Regex("<path d=").findAll(svg).count())
        assertTrue(svg.contains("#1B4ED8"))
        assertTrue(svg.contains("#C62828"))
        assertTrue("图例要能认出是哪条", svg.contains("y=x^2") && svg.contains("y=2x+1"))
    }

    @Test
    fun `给了 y 范围就用给的`() {
        val plotted = ok(
            FunctionPlotter.Request(
                listOf(FunctionPlotter.Curve("sin(x)")),
                xMin = -6.0,
                xMax = 6.0,
                yMin = -0.5,
                yMax = 0.5
            )
        )
        assertEquals(-0.5, plotted.yMin, 1e-9)
        assertEquals(0.5, plotted.yMax, 1e-9)
    }

    @Test
    fun `常数函数也能画（窗口不能塌成一条线）`() {
        val plotted = ok(FunctionPlotter.Request(listOf(FunctionPlotter.Curve("3"))))
        assertTrue(plotted.yMax > plotted.yMin)
        assertTrue(plotted.yMin <= 3.0 && plotted.yMax >= 3.0)
    }

    @Test
    fun `表达式写错就明确说是哪条错了`() {
        // 注意不能用 "x+y"：v0.0.147 起裸的含 y 表达式会被当成隐函数 x+y=0，
        // 那是能画的。这里用一个确实有语法错的（括号没配对）。
        val message = failed(FunctionPlotter.Request(listOf(FunctionPlotter.Curve("sin(x"))))
        assertTrue("要指名道姓说出错的表达式，实际：$message", message.contains("sin(x"))
    }

    @Test
    fun `x 范围反了要报错而不是画一张空图`() {
        val message = failed(
            FunctionPlotter.Request(
                listOf(FunctionPlotter.Curve("x")),
                xMin = 5.0,
                xMax = 1.0
            )
        )
        assertTrue(message.contains("x_min"))
    }

    @Test
    fun `整段区间都没有定义就报错`() {
        val message = failed(
            FunctionPlotter.Request(
                listOf(FunctionPlotter.Curve("ln(x)")),
                xMin = -5.0,
                xMax = -1.0
            )
        )
        assertTrue("要说明这段区间上没有取值，实际：$message", message.contains("取值"))
    }

    @Test
    fun `图例里的尖括号要转义（否则 SVG 直接坏掉）`() {
        val svg = ok(
            FunctionPlotter.Request(
                curves = listOf(
                    FunctionPlotter.Curve("x", "x<2"),
                    FunctionPlotter.Curve("2", "常函数")
                )
            )
        ).svg
        assertTrue("`<` 必须转义成 &lt;", svg.contains("x&lt;2"))
        assertFalse("未转义的裸 `<2` 会让 XML 解析失败", svg.contains(">x<2<"))
    }

    @Test
    fun `最多画四条曲线`() {
        val curves = (1..6).map { FunctionPlotter.Curve("$it*x") }
        val svg = ok(FunctionPlotter.Request(curves)).svg
        assertEquals(
            "超出的曲线要忽略（图会糊成一团）",
            FunctionPlotter.MAX_CURVES,
            Regex("<path d=").findAll(svg).count()
        )
    }

    @Test
    fun `标题会画在图里`() {
        val svg = ok(
            FunctionPlotter.Request(
                listOf(FunctionPlotter.Curve("x^2")),
                title = "f(x)=x^2 的图像"
            )
        ).svg
        assertTrue(svg.contains("f(x)=x^2 的图像"))
    }

    // ---- v0.0.147：极坐标与隐函数 ----

    @Test
    fun `极坐标心形线能画出来`() {
        // r=1+cos(θ)，θ∈[0,2π]。心形在笛卡尔坐标里的横坐标是
        // x = (1+cosθ)·cosθ = cosθ + cos²θ，最小值 -0.25（cosθ=-0.5 处），
        // 最大值 2（θ=0 处）。x 范围在这里是 θ 范围（极坐标的既定口径），
        // 窗口要按曲线自己的极值定，否则心形会被挤在 [-10,10] 窗口的右边一小块。
        val plotted = ok(
            FunctionPlotter.Request(
                curves = listOf(FunctionPlotter.Curve("r=1+cos(θ)")),
                xMin = 0.0,
                xMax = 2 * Math.PI
            )
        )
        val paths = Regex("<path d=\"([^\"]*)\"").findAll(plotted.svg)
            .map { it.groupValues[1] }.toList()
        assertEquals("一条曲线只能出一个 path", 1, paths.size)
        val path = paths.first()
        assertFalse("不能出现 NaN 坐标", path.contains("NaN"))
        assertFalse("不能出现 inf 坐标", path.contains("Infinity"))
        assertTrue(
            "θ 采样 720 步，折线点不该只有几个，实际 ${Regex("L").findAll(path).count()} 个",
            Regex("L").findAll(path).count() > 100
        )
        assertTrue("窗口要包住心形左端 -0.25，实际 ${plotted.xMin}", plotted.xMin <= -0.25)
        assertTrue("窗口要包住心形右端 2，实际 ${plotted.xMax}", plotted.xMax >= 2.0)
    }

    @Test
    fun `隐函数圆能画出多段拼成的折线`() {
        // x^2+y^2=1 解不出 y 的显式表达式，走 Marching Squares 求等值线：
        // 整张图上会有几百个小段，必须被串成**少量**折线，且只出一个 path。
        val plotted = ok(
            FunctionPlotter.Request(
                curves = listOf(FunctionPlotter.Curve("x^2+y^2=1")),
                xMin = -2.0,
                xMax = 2.0
            )
        )
        val paths = Regex("<path d=\"([^\"]*)\"").findAll(plotted.svg)
            .map { it.groupValues[1] }.toList()
        assertEquals("隐函数也只出一条 path（小段要串起来，不能一段一个 path）", 1, paths.size)
        val path = paths.first()
        assertTrue("要有 M 起点", path.contains("M "))
        assertFalse("不能出现 NaN 坐标", path.contains("NaN"))
        assertFalse("不能出现 inf 坐标", path.contains("Infinity"))
        val points = Regex("L").findAll(path).count()
        assertTrue("圆的折线点太少（$points），可能只画出零星几段", points >= 30)
    }

    @Test
    fun `写错的表达式报错里带原文`() {
        val message = failed(FunctionPlotter.Request(listOf(FunctionPlotter.Curve("x^"))))
        assertTrue("报错要带原表达式与原因，实际：$message", message.contains("x^"))
    }
}
