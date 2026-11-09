package com.mistakebook.data.chat

import com.mistakebook.math.FunctionPlotter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plot_function 工具（issue #54）的参数解析单测。
 *
 * 模型给的 JSON 不会永远标准：表达式可能写成数组也可能只给一条、
 * 数字可能是 number 也可能是字符串。这些都得认——认不出来就白瞎一次
 * 工具调用；但**表达式为空时必须拒绝**，否则会画出一张没有曲线的空图，
 * 用户以为功能坏了。
 */
class PlotToolsTest {

    @Test
    fun `标准写法：表达式数组 + 范围 + 标题`() {
        val request = PlotTools.parse(
            """
            {"expressions":["x^2+2x","3x-1"],"labels":["y=x^2+2x","y=3x-1"],
             "x_min":-3,"x_max":1,"y_min":-2,"y_max":5,"title":"对比"}
            """.trimIndent()
        )
        assertNotNull(request)
        assertEquals(2, request!!.curves.size)
        assertEquals("x^2+2x", request.curves[0].expression)
        assertEquals("y=3x-1", request.curves[1].label)
        assertEquals(-3.0, request.xMin!!, 1e-9)
        assertEquals(5.0, request.yMax!!, 1e-9)
        assertEquals("对比", request.title)
    }

    @Test
    fun `只给一条表达式（字符串或单元素数组）都认`() {
        val single = PlotTools.parse("""{"expression":"sin(x)"}""")
        assertEquals(1, single!!.curves.size)
        assertEquals("sin(x)", single.curves[0].expression)

        val array = PlotTools.parse("""{"expressions":["sin(x)"]}""")
        assertEquals(1, array!!.curves.size)
    }

    @Test
    fun `数值写成字符串也认`() {
        val request = PlotTools.parse("""{"expressions":["x"],"x_min":"-1.5","x_max":"2.5"}""")
        assertEquals(-1.5, request!!.xMin!!, 1e-9)
        assertEquals(2.5, request.xMax!!, 1e-9)
    }

    @Test
    fun `没给范围就是自动（null 交给绘图器兜底）`() {
        val request = PlotTools.parse("""{"expressions":["x^2"]}""")
        assertNull(request!!.xMin)
        assertNull(request.xMax)
        assertNull(request.yMin)
        assertNull(request.yMax)
    }

    @Test
    fun `没有表达式就拒绝（不许画空图）`() {
        assertNull(PlotTools.parse("""{"x_min":-1}"""))
        assertNull(PlotTools.parse("""{"expressions":[]}"""))
        assertNull(PlotTools.parse("""{"expressions":["   "]}"""))
        assertNull(PlotTools.parse("不是 JSON"))
    }

    @Test
    fun `工具声明与名字对得上`() {
        val tool = PlotTools.tool()
        assertEquals("plot_function", tool.function.name)
        assertEquals("function", tool.type)
        assertTrue(PlotTools.handles("plot_function"))
        assertFalse(PlotTools.handles("calculator"))
        assertTrue(
            "描述里必须写清「这是精确绘图、别手写 SVG、别用生图」——模型按描述做决策",
            tool.function.description.contains("必须调用本工具") &&
                tool.function.description.contains("生图")
        )
    }

    @Test
    fun `围栏标记必须是 svg（否则客户端当纯文本显示）`() {
        val fenced = PlotTools.fence("<svg></svg>")
        assertTrue(fenced.startsWith("```svg\n"))
        assertTrue(fenced.endsWith("\n```"))
    }

    @Test
    fun `摘要给出曲线与取用范围，供模型复述`() {
        val request = FunctionPlotter.Request(
            listOf(FunctionPlotter.Curve("x^2+2x")),
            xMin = -3.0,
            xMax = 1.0
        )
        val plotted = FunctionPlotter.plot(request) as FunctionPlotter.Result.Ok
        val summary = PlotTools.summary(request.curves, plotted)
        assertTrue("要带上表达式，实际：$summary", summary.contains("x^2+2x"))
        assertTrue("要带上 x 范围，实际：$summary", summary.contains("-3") && summary.contains("1"))
        assertTrue("要带上 y 范围，实际：$summary", summary.contains("y ∈"))
    }
}
