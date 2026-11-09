package com.mistakebook.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线归类（v0.0.145）单测：用户能写的几种形式都要落到正确的类型上。
 *
 * 用户的抱怨是「高级的有 y 的函数不能画、极坐标也不能画」——所以这里钉的
 * 是「写法 → 类型」这张表，而算法本身在 [CurveSampler] 里另有测试。
 */
class PlotCurveTest {

    @Test
    fun `普通显函数`() {
        assertEquals(PlotCurve.Explicit("x^2+2x", "x^2+2x"), PlotCurve.parse("x^2+2x"))
        assertEquals(PlotCurve.Explicit("sin(x)", "sin(x)"), PlotCurve.parse("sin(x)"))
    }

    @Test
    fun `y 等号只是声明，不是隐函数`() {
        val curve = PlotCurve.parse("y = sin(x)")
        assertTrue(curve is PlotCurve.Explicit)
        assertEquals("sin(x)", (curve as PlotCurve.Explicit).expression)
    }

    @Test
    fun `极坐标`() {
        val curve = PlotCurve.parse("r = 1+cos(θ)")
        assertTrue("r=… 要判成极坐标，实际 $curve", curve is PlotCurve.Polar)
        assertEquals("1+cos(θ)", (curve as PlotCurve.Polar).expression)
    }

    @Test
    fun `极坐标允许常数 r（那就是一个圆）`() {
        // r=1 是单位圆，很常用——不能因为「没有 θ」就判非法
        val curve = PlotCurve.parse("r = 2")
        assertTrue("实际 $curve", curve is PlotCurve.Polar)
        assertEquals("2", (curve as PlotCurve.Polar).expression)
    }

    @Test
    fun `极坐标的 r 不能混用 x 或 y`() {
        val error = runCatching { PlotCurve.parse("r = x + θ") }.exceptionOrNull()
        assertTrue("实际：$error", error?.message?.contains("只能用 θ") == true)
    }

    @Test
    fun `隐函数方程`() {
        assertEquals(
            PlotCurve.Implicit("x^2+y^2=1", "x^2+y^2", "1"),
            PlotCurve.parse("x^2+y^2=1")
        )
        // x = y^2：x 当因变量，同样走等值线
        assertEquals(PlotCurve.Implicit("x=y^2", "x", "y^2"), PlotCurve.parse("x=y^2"))
        // 两边都能含变量
        assertEquals(
            PlotCurve.Implicit("sin(x)=cos(y)", "sin(x)", "cos(y)"),
            PlotCurve.parse("sin(x)=cos(y)")
        )
    }

    @Test
    fun `裸的含 y 表达式按等于零处理`() {
        // 注意：x^2+y^2=0 只有一个点，画出来是空的——这么归类只是为了
        // 不把「含 y」判成语法错，报错里会提示要写等号右边。
        assertEquals(PlotCurve.Implicit("x^2+y^2", "x^2+y^2", "0"), PlotCurve.parse("x^2+y^2"))
    }

    @Test
    fun `裸的含 θ 表达式按极坐标处理`() {
        val curve = PlotCurve.parse("1+cos(θ)")
        assertTrue("实际 $curve", curve is PlotCurve.Polar)
    }

    @Test
    fun `等号左边右边都不能空`() {
        assertTrue(runCatching { PlotCurve.parse("=1") }.exceptionOrNull() != null)
        assertTrue(runCatching { PlotCurve.parse("y=") }.exceptionOrNull() != null)
    }

    @Test
    fun `语法错要带上原文`() {
        val error = runCatching { PlotCurve.parse("sin(x") }.exceptionOrNull()
        assertTrue("实际：$error", error?.message?.contains("sin(x") == true)
    }

    @Test
    fun `空表达式拒绝`() {
        assertTrue(runCatching { PlotCurve.parse("   ") }.exceptionOrNull() != null)
    }
}
