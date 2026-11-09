package com.mistakebook.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绘图用表达式求值器（issue #54）的单测。
 *
 * 两类钉子：
 * - **算得对**：优先级、右结合、隐式乘法、函数表（学生最自然的写法必须认）；
 * - **算不了时不装**：定义域外/除零返回非有限值（绘图据此断线），语法错抛
 *   带位置的异常（模型据此改正）。绝不返回一个"看起来像"的数——
 *   画错曲线比不画更糟。
 */
class ExpressionEvaluatorTest {

    private fun eval(expression: String, x: Double = 0.0): Double =
        ExpressionEvaluator.parse(expression).eval(x)

    @Test
    fun `四则运算与优先级`() {
        assertEquals(7.0, eval("1+2*3"), 1e-9)
        assertEquals(9.0, eval("(1+2)*3"), 1e-9)
        assertEquals(1.0, eval("7%3"), 1e-9)
        assertEquals(2.5, eval("5/2"), 1e-9)
    }

    @Test
    fun `幂右结合且优先于一元负号`() {
        assertEquals(512.0, eval("2^3^2"), 1e-9)          // 2^(3^2)
        assertEquals(-4.0, eval("-x^2", x = 2.0), 1e-9)   // -(2^2)，不是 (-2)^2
        assertEquals(4.0, eval("(-x)^2", x = 2.0), 1e-9)
    }

    @Test
    fun `负底数的整数次幂算得出来`() {
        // Math.pow(-8, 1.0/3) 是 NaN，但整数次幂必须能给结果：
        // 学生写 (-2)^3 时不该看到「没有定义」
        assertEquals(-8.0, eval("(-2)^3"), 1e-9)
        assertEquals(-0.125, eval("(-2)^-3"), 1e-9)
    }

    @Test
    fun `隐式乘法（学生最自然的写法）`() {
        assertEquals(6.0, eval("2x+2", x = 2.0), 1e-9)
        assertEquals(6.0, eval("2(x+1)", x = 2.0), 1e-9)
        assertEquals(0.0, eval("(x+2)(x-1)", x = 1.0), 1e-9)
        assertEquals(4.0, eval("(x+2)(x-1)", x = 2.0), 1e-9)
        assertEquals(0.0, eval("3sin(x)", x = 0.0), 1e-9)
        assertEquals(2 * Math.PI, eval("2pi"), 1e-9)
        assertEquals(2 * Math.E, eval("2e"), 1e-9)
    }

    @Test
    fun `函数表与常量`() {
        assertEquals(3.0, eval("sqrt(9)"), 1e-9)
        assertEquals(2.0, eval("abs(-2)"), 1e-9)
        assertEquals(0.0, eval("sin(0)"), 1e-9)
        assertEquals(1.0, eval("cos(0)"), 1e-9)
        assertEquals(2.0, eval("log(100)"), 1e-9)   // 以 10 为底，与 calculator 一致
        assertEquals(1.0, eval("ln(e)"), 1e-9)
        assertEquals(3.0, eval("log2(8)"), 1e-9)
        assertEquals(1.0, eval("floor(1.9)"), 1e-9)
        assertEquals(2.0, eval("ceil(1.1)"), 1e-9)
        assertEquals(1.0, eval("round(0.6)"), 1e-9)
        assertEquals(-1.0, eval("sign(-5)"), 1e-9)
    }

    @Test
    fun `科学计数法不能和常量 e 抢`() {
        assertEquals(0.001, eval("1e-3"), 1e-12)
        assertEquals(2500.0, eval("2.5E3"), 1e-9)
        assertEquals(2 * Math.E, eval("2e"), 1e-9)
    }

    @Test
    fun `变量可以是任意实数`() {
        assertEquals(-1.0, eval("x^2+2x", x = -1.0), 1e-9)
        assertEquals(8.0, eval("x^2+2x", x = 2.0), 1e-9)
    }

    @Test
    fun `定义域之外返回非有限值而不是抛异常`() {
        assertTrue(eval("ln(-1)").isNaN())
        assertTrue(eval("sqrt(-4)").isNaN())
        assertTrue(eval("1/x", x = 0.0).isInfinite())
        // 0/0 也是 NaN：绘图时按断点处理
        assertTrue(eval("0/x", x = 0.0).isNaN())
    }

    @Test
    fun `语法错误要说清是哪里错了`() {
        val unknown = runCatching { eval("x+y") }.exceptionOrNull()
        assertTrue(
            "不认识的名称要指出来（模型据此改写表达式），实际：$unknown",
            unknown?.message?.contains("y") == true
        )

        val unclosed = runCatching { eval("sin(x") }.exceptionOrNull()
        assertTrue("括号不配对要报出来，实际：$unclosed", unclosed != null)

        val garbage = runCatching { eval("x*") }.exceptionOrNull()
        assertTrue("表达式截断要报出来，实际：$garbage", garbage != null)

        val symbol = runCatching { eval("x#2") }.exceptionOrNull()
        assertTrue("看不懂的符号要报出来，实际：$symbol", symbol?.message?.contains("#") == true)
    }

    @Test
    fun `空白不影响解析`() {
        assertEquals(6.0, eval(" 2 x + 2 ", x = 2.0), 1e-9)
        assertEquals(1.0, eval(" sin ( 0 ) + 1"), 1e-9)
    }

    @Test
    fun `同一表达式可反复求值`() {
        val evaluator = ExpressionEvaluator.parse("x^2")
        val values = listOf(-2.0, -1.0, 0.0, 1.0, 2.0).map { evaluator.eval(it) }
        assertEquals(listOf(4.0, 1.0, 0.0, 1.0, 4.0), values)
        assertFalse(evaluator.eval(3.0).isNaN())
    }
}
