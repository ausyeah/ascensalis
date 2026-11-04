package com.mistakebook.data.chat

import com.mistakebook.net.llm.LlmTool
import com.mistakebook.net.llm.ToolFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 数学计算工具（v0.0.114）：让模型把**算术**外包给确定性求值器。
 *
 * ## 为什么需要
 * 大模型算 `1847 × 293` 这类多步算术靠「背」，错一位就是整题全错；
 * 解方程组、验算代入这类活也一样。把计算交给确定性求值器，
 * 模型只负责列式与解释——这是所有数学 Copilot 的标准分工。
 *
 * ## 求值器为什么手写
 * 仓库硬规则「零新依赖」——脚本引擎（javax.script）/ 大数库都引不得。
 * 递归下降解析器 150 行覆盖：四则运算、幂、括号、一元负号、
 * 常用函数（sqrt/sin/cos/tan/log/ln/abs/round/floor/ceil）与常量（pi/e）。
 * 双精度求值对学习场景绰绰有余；**没有任何 eval**——表达式字符逐个消费，
 * 非法输入明确报错，不存在注入面。
 *
 * ## 角度制
 * 三角函数按弧度（数学惯例）。模型需要角度制时自己先做 `x * pi / 180`
 * 的换算——工具描述里写明。
 */
object MathTools {

    /** 工具名。回传的 tool 消息与调用匹配都靠它。 */
    const val CALCULATOR = "calculator"

    fun handles(name: String): Boolean = name == CALCULATOR

    /** calculator 工具的 function calling 定义。 */
    fun calculatorTool(): LlmTool = LlmTool(
        type = "function",
        function = ToolFunction(
            name = CALCULATOR,
            description = "精确计算数学表达式（确定性求值器，绝不算错）。" +
                "遇到任何多步算术、大数乘除、开方、三角/对数函数求值、" +
                "解方程过程中的代入验算时，都应该调用本工具而不是心算。" +
                "表达式支持：+ - * / % ^ ( )、一元负号、" +
                "函数 sqrt/sin/cos/tan/asin/acos/atan/log(以10为底)/ln/abs/round/floor/ceil、" +
                "常量 pi 与 e。三角函数使用弧度制。",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "expression",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "数学表达式，例如 (1847*293+12)/7 或 sqrt(2)^2。" +
                                        "等号右边的最终式，不要带「=」与单位。"
                                )
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("expression")) })
            }
        )
    )

    /** 从工具调用参数里取出表达式。 */
    fun parseExpression(arguments: String): String? = runCatching {
        Json.parseToJsonElement(arguments).jsonObject["expression"]
            ?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * 求值入口：非法输入 / 除零 / 未知名字一律返回带原因的错误文案
     * （模型会把原因转述给用户），绝不抛异常打断生成。
     */
    fun evaluate(expression: String): String = try {
        val value = ExpressionParser(expression).parse()
        format(value)
    } catch (error: IllegalArgumentException) {
        "计算失败：${error.message}"
    } catch (t: Throwable) {
        "计算失败：表达式无法解析"
    }

    /**
     * 结果格式化：整数值去掉小数点；小数保留 12 位有效数字并去尾零。
     * 直接 Double.toString 会暴露浮点尾数（0.1+0.2 = 0.30000000000000004），
     * 用户看到的就该是 0.3。
     */
    private fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "结果不是有限数"
        if (abs(value - Math.rint(value)) < 1e-9 && abs(value) < 1e15) {
            return Math.rint(value).toLong().toString()
        }
        return java.math.BigDecimal(value)
            .round(java.math.MathContext(12))
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun abs(v: Double): Double = if (v < 0) -v else v

    /**
     * 递归下降求值器。
     *
     * 文法：expr = term (('+'|'-') term)*；term = factor (('*'|'/'|'%') factor)*；
     * factor = unary ('^' factor)*（右结合）；unary = '-' unary | primary；
     * primary = number | constant | function '(' expr ')' | '(' expr ')'
     */
    internal class ExpressionParser(private val src: String) {
        private var pos = 0

        fun parse(): Double {
            val value = parseExpr()
            skipSpace()
            if (pos < src.length) {
                fail("位置 ${pos + 1} 有无法识别的字符「${src[pos]}」")
            }
            return value
        }

        private fun fail(reason: String): Nothing = throw IllegalArgumentException(reason)

        private fun skipSpace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? = src.getOrNull(pos)

        private fun eat(expected: Char): Boolean {
            skipSpace()
            if (peek() == expected) {
                pos++
                return true
            }
            return false
        }

        private fun parseExpr(): Double {
            var value = parseTerm()
            while (true) {
                skipSpace()
                when (peek()) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                skipSpace()
                when (peek()) {
                    '*' -> { pos++; value *= parseFactor() }
                    '/' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) fail("除数为 0")
                        value /= divisor
                    }
                    '%' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) fail("取模的除数为 0")
                        value %= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            skipSpace()
            // 一元负号：数学惯例 -x^2 = -(x^2)，负号作用于**含幂**的整个 factor
            when (peek()) {
                '-' -> {
                    pos++
                    return -parseFactor()
                }
                '+' -> {
                    pos++
                    return parseFactor()
                }
            }
            val base = parsePrimary()
            skipSpace()
            if (peek() == '^') {
                pos++
                // 幂右结合：2^3^2 = 2^9
                return Math.pow(base, parseFactor())
            }
            return base
        }

        private fun parsePrimary(): Double {
            skipSpace()
            val start = pos
            val ch = peek() ?: fail("表达式意外结束")
            // 括号
            if (ch == '(') {
                pos++
                val value = parseExpr()
                if (!eat(')')) fail("缺少右括号")
                return value
            }
            // 数字（含小数）
            if (ch.isDigit()) {
                while (peek()?.isDigit() == true) pos++
                if (peek() == '.') {
                    pos++
                    while (peek()?.isDigit() == true) pos++
                }
                val number = src.substring(start, pos).toDoubleOrNull()
                    ?: fail("「${src.substring(start, pos)}」不是合法数字")
                return number
            }
            // 名字：常量或函数
            if (ch.isLetter()) {
                while (peek()?.isLetter() == true) pos++
                val name = src.substring(start, pos)
                when (name) {
                    "pi" -> return Math.PI
                    "e" -> return Math.E
                }
                // 函数：必须跟括号
                skipSpace()
                if (!eat('(')) fail("「$name」不是已支持的名称（函数后需要括号）")
                val arg = parseExpr()
                if (!eat(')')) fail("「$name」缺少右括号")
                return applyFunction(name, arg)
            }
            fail("位置 ${pos + 1} 有无法识别的字符「$ch」")
        }

        private fun applyFunction(name: String, arg: Double): Double = when (name) {
            "sqrt" -> Math.sqrt(arg)
            "sin" -> Math.sin(arg)
            "cos" -> Math.cos(arg)
            "tan" -> Math.tan(arg)
            "asin" -> Math.asin(arg)
            "acos" -> Math.acos(arg)
            "atan" -> Math.atan(arg)
            "log" -> Math.log10(arg)
            "ln" -> if (arg <= 0) fail("ln 的定义域是正数") else Math.log(arg)
            "abs" -> Math.abs(arg)
            "round" -> Math.rint(arg)
            "floor" -> Math.floor(arg)
            "ceil" -> Math.ceil(arg)
            else -> fail("不支持的函数「$name」")
        }
    }
}
