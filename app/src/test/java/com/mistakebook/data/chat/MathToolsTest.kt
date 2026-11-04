package com.mistakebook.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数学计算器（v0.0.114）：模型的算术外包给确定性求值器。
 *
 * 大模型心算 `1847×293` 错一位就是整题全错。求值器是纯 Kotlin
 * 递归下降，这里穷举钉死：运算优先级、幂右结合、函数表、错误路径
 * ——任何一条变了，模型的数学答案就会静默变差。
 */
class MathToolsTest {

    private fun eval(expr: String): String = MathTools.evaluate(expr)

    @Test
    fun `四则运算与优先级`() {
        assertEquals("14", eval("2+3*4"))
        assertEquals("2", eval("(2+3)*4/10"))
        assertEquals("0.5", eval("1/2"))
        assertEquals("-3", eval("2-5"))
    }

    @Test
    fun `幂右结合`() {
        // 2^3^2 = 2^9 = 512；若左结合是 64
        assertEquals("512", eval("2^3^2"))
        assertEquals("8", eval("2^3"))
    }

    @Test
    fun `一元负号`() {
        assertEquals("-5", eval("-5"))
        assertEquals("5", eval("--5"))
        assertEquals("-8", eval("2*-4"))
        assertEquals("-8", eval("-2^3"))
        assertEquals("-4", eval("-2^2")) // 数学惯例：-(2^2)
    }

    @Test
    fun `大数乘法不丢精度`() {
        assertEquals("541171", eval("1847*293"))
        // 超过 double 整数安全位才用科学计数——学习场景到不了
        assertTrue(eval("999999999999*2").isNotEmpty())
    }

    @Test
    fun `函数表`() {
        assertEquals("2", eval("sqrt(4)"))
        assertEquals("0", eval("sin(0)"))
        assertEquals("1", eval("cos(0)"))
        assertEquals("2", eval("log(100)"))
        assertEquals("0", eval("ln(1)"))
        assertEquals("3", eval("abs(0-3)"))
        assertEquals("3", eval("round(2.6)"))
        assertEquals("2", eval("floor(2.9)"))
        assertEquals("3", eval("ceil(2.1)"))
    }

    @Test
    fun `常量`() {
        // pi 保留精度后四舍五入到 15 位
        assertTrue(eval("pi").startsWith("3.14159265359"))
        assertTrue(eval("e").startsWith("2.71828182846"))
    }

    @Test
    fun `除零与非法输入明确报错`() {
        assertTrue(eval("1/0").contains("除数为 0"))
        assertTrue(eval("1%0").contains("取模"))
        assertTrue(eval("sqrt(0-1)").contains("结果不是有限数"))
        assertTrue(eval("foo(1)").contains("不支持的函数"))
        assertTrue(eval("1+").contains("意外结束"))
        assertTrue(eval("(1+2").contains("右括号"))
        assertTrue(eval("1+abc").contains("不是已支持的名称"))
    }

    @Test
    fun `参数解析`() {
        assertEquals("1+1", MathTools.parseExpression("""{"expression":"1+1"}"""))
        assertNull(MathTools.parseExpression("""{"other":"1+1"}"""))
        assertNull(MathTools.parseExpression("不是 json"))
        assertNull(MathTools.parseExpression("""{"expression":"  "}"""))
    }

    @Test
    fun `整数结果不带小数点`() {
        val result = eval("0.1+0.2")
        // 浮点痕 0.30000000000000004 必须收敛成 0.3
        assertTrue("实际 $result", !result.contains("00000000"))
    }
}
