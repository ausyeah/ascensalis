package com.mistakebook.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 理科工具链的地基（v0.0.151）：精确有理数、多项式、方程求解、
 * 统计 / 数列 / 单位换算。
 *
 * 这里钉的是**数学事实**——弱模型把计算外包进来，答案必须不可能错。
 * 每条断言都是一个「弱模型翻车点」的特异性样本。
 */
class ScienceEngineTest {

    // ------------------------------------------------------------ Rational

    @Test
    fun `分数四则精确且约分`() {
        val third = Rational.parse("1/3")
        val sixth = Rational.parse("1/6")
        assertEquals(Rational.parse("1/2"), third + sixth)
        assertEquals(Rational.parse("7/12"), Rational.parse("1/3") + Rational.parse("1/4"))
        assertEquals(Rational.parse("1/4"), Rational.parse("0.25"))
        assertEquals(Rational.parse("3/10"), Rational.parse("0.1") + Rational.parse("0.2"))
    }

    @Test
    fun `负分母归一到分子带号`() {
        assertEquals(Rational.parse("-1/2"), Rational.of(1L, -2L))
        assertEquals("-1/2", Rational.of(1L, -2L).display())
    }

    @Test
    fun `乘除与幂`() {
        assertEquals(Rational.parse("3/8"), Rational.parse("1/2") * Rational.parse("3/4"))
        assertEquals(Rational.parse("2/3"), Rational.parse("2/3") / Rational.parse("1"))
        assertEquals(Rational.parse("9/4"), Rational.parse("3/2").pow(2))
        assertEquals(Rational.parse("2"), Rational.parse("4").pow(1).div(Rational.parse("2")))
        // 负指数 = 取倒数
        assertEquals(Rational.of(3L), Rational.parse("1/3").pow(-1))
    }

    @Test
    fun `比较大小`() {
        assertTrue(Rational.parse("3/7") < Rational.parse("1/2"))
        assertFalse(Rational.parse("2/3") < Rational.parse("2/3"))
        assertTrue(Rational.parse("-1/2") < Rational.ZERO)
    }

    @Test
    fun `完全平方开方精确 否则 null`() {
        assertEquals(Rational.parse("3/2"), Rational.sqrtExact(Rational.parse("9/4")))
        assertNull(Rational.sqrtExact(Rational.of(2L)))
        assertNull(Rational.sqrtExact(Rational.of(-4L)))
    }

    @Test
    fun `除零报错而不是崩`() {
        val error = runCatching { Rational.ONE / Rational.ZERO }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    // ------------------------------------------------------------ 多项式

    @Test
    fun `括号展开与隐式乘法`() {
        assertEquals("x^2 - 1", PolyExpr.parse("(x+1)(x-1)").format())
        assertEquals("2x - 6", PolyExpr.parse("2(x-3)").format())
        assertEquals("x^2 + x", PolyExpr.parse("x(x+1)").format())
    }

    @Test
    fun `多项式求导`() {
        assertEquals("6x - 2", PolyExpr.parse("3x^2-2x+1").derivative().format())
        assertEquals("12x + 1", PolyExpr.parse("(2x-1)(3x+2)").derivative().format())
        // 分数系数：x/2 的导数是 1/2
        assertEquals("1/2", PolyExpr.parse("x/2").derivative().format())
        assertEquals("0", PolyExpr.parse("7").derivative().format())
    }

    @Test
    fun `两个未知数明确报错`() {
        val error = runCatching { PolyExpr.parse("2x+y") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("一个变量"))
    }

    @Test
    fun `多项式求值用于验根`() {
        val poly = PolyExpr.parse("x^2-5x+6")
        assertEquals(Rational.ZERO, poly.evaluateAt(Rational.of(2L)))
        assertEquals(Rational.ZERO, poly.evaluateAt(Rational.of(3L)))
        assertEquals(Rational.of(2L), poly.evaluateAt(Rational.of(4L)))
    }

    // ------------------------------------------------------------ 一元方程

    @Test
    fun `一次方程 精确分数解`() {
        val result = EquationSolver.solveOneVariable("x/2+1/3=5/6")
        assertTrue("实际：$result", result.contains("x = 1"))
        assertTrue("实际：$result", result.contains("验根"))
    }

    @Test
    fun `二次方程 精确根`() {
        val result = EquationSolver.solveOneVariable("x^2-5x+6=0")
        assertTrue("实际：$result", result.contains("x₁ = 3"))
        assertTrue("实际：$result", result.contains("x₂ = 2"))
        assertTrue("实际：$result", result.contains("4ac = 1"))
    }

    @Test
    fun `二次方程 无实数解`() {
        val result = EquationSolver.solveOneVariable("x^2+x+1=0")
        assertTrue("实际：$result", result.contains("Δ < 0"))
        assertTrue("实际：$result", result.contains("没有实数解"))
    }

    @Test
    fun `二次方程 无理根给小数近似`() {
        val result = EquationSolver.solveOneVariable("x^2-2x-1=0")
        assertTrue("实际：$result", result.contains("不是完全平方数"))
        // 1±√2 ≈ 2.414214 / -0.414214
        assertTrue("实际：$result", result.contains("2.4142136"))
    }

    @Test
    fun `无解与恒等式`() {
        assertTrue(EquationSolver.solveOneVariable("x+1=x+2").contains("无解"))
        assertTrue(EquationSolver.solveOneVariable("2x=2x").contains("恒等式"))
    }

    @Test
    fun `括号方程`() {
        val result = EquationSolver.solveOneVariable("2(x-3)=x+1")
        assertTrue("实际：$result", result.contains("x = 7"))
    }

    // ------------------------------------------------------------ 方程组

    @Test
    fun `二元一次方程组 精确解与验算`() {
        val result = EquationSolver.solveSystem("2x+3y=12", "x-y=1")
        assertTrue("实际：$result", result.contains("x = 3"))
        assertTrue("实际：$result", result.contains("y = 2"))
        assertTrue("实际：$result", result.contains("第一条 ✓"))
        assertTrue("实际：$result", result.contains("第二条 ✓"))
    }

    @Test
    fun `y 等于式子形式的方程也能解`() {
        val result = EquationSolver.solveSystem("y=2x+1", "x+y=7")
        assertTrue("实际：$result", result.contains("x = 2"))
        assertTrue("实际：$result", result.contains("y = 5"))
    }

    @Test
    fun `平行线无解`() {
        val result = EquationSolver.solveSystem("x+y=1", "2x+2y=3")
        assertTrue("实际：$result", result.contains("无解"))
    }

    // ------------------------------------------------------------ 统计

    @Test
    fun `统计量全案`() {
        val result = StatsCalc.describe("88,92,75,90,88")
        assertTrue("实际：$result", result.contains("个数 n = 5"))
        assertTrue("实际：$result", result.contains("86.6"))
        assertTrue("实际：$result", result.contains("中位数 = 88"))
        assertTrue("实际：$result", result.contains("众数 = 88"))
    }

    @Test
    fun `偶数个的中位数取中间平均`() {
        val result = StatsCalc.describe("1,2,3,4")
        assertTrue("实际：$result", result.contains("中位数 = 5/2"))
    }

    // ------------------------------------------------------------ 数列

    @Test
    fun `等差数列通项与求和`() {
        val result = SequenceCalc.describe("arithmetic", "3", "4", 10)
        assertTrue("实际：$result", result.contains("= 39"))
        assertTrue("实际：$result", result.contains("= 210"))
    }

    @Test
    fun `等比数列 分数公比精确`() {
        val result = SequenceCalc.describe("geometric", "1/2", "1/2", 3)
        assertTrue("实际：$result", result.contains("1/8"))
        // S₃ = 1/2 + 1/4 + 1/8 = 7/8
        assertTrue("实际：$result", result.contains("7/8"))
    }

    @Test
    fun `公比为一时退化为等和`() {
        val result = SequenceCalc.describe("geometric", "5", "1", 4)
        assertTrue("实际：$result", result.contains("= 20"))
    }

    // ------------------------------------------------------------ 单位换算

    @Test
    fun `kmh 与 ms 的换算`() {
        val result = UnitConvert.convert("36", "km/h", "m/s")
        assertTrue("实际：$result", result.contains("10 m/s"))
    }

    @Test
    fun `亩与平方米`() {
        val result = UnitConvert.convert("3", "亩", "m²")
        // 3 亩 = 2000/3 * 3 = 2000 m²
        assertTrue("实际：$result", result.contains("2000 m²"))
    }

    @Test
    fun `kWh 与 J`() {
        val result = UnitConvert.convert("1", "kWh", "J")
        assertTrue("实际：$result", result.contains("3600000 J"))
    }

    @Test
    fun `跨类别拒绝`() {
        val result = UnitConvert.convert("1", "km", "kg")
        assertTrue("实际：$result", result.contains("不能互换"))
    }
}
