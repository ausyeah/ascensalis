package com.mistakebook.math

import java.math.BigInteger

/**
 * 单变量多项式：系数是精确 [Rational]，按次数升幂存（下标 = 次数）。
 *
 * ## 它服务谁
 *
 * - [derivative]：多项式求导（高中 / 考研最常外包给工具的活）；
 * - [EquationSolver]：一元一次 / 二次方程——把等号两边都解析成多项式，
 *   移项相减后按次数分类，判别式、验根全部精确。
 *
 * ## 解析器为什么支持括号与隐式乘法
 *
 * 题目里的方程几乎从不长成 `ax^2+bx+c=0` 的标准形：`2(x-3)=x+1`、
 * `(x+1)(x-2)=0`、`3x^2` 才是模型的原始输出。所以解析器要能做
 * 多项式的加减乘（含隐式乘法 `2x`、`x(x+1)`、`3(x+1)(x-2)`），
 * 括号展开在解析阶段就完成。
 *
 * 只允许除以**常数**：多项式长除法对学习场景得不偿失，遇到就报错，
 * 让模型先因式分解。
 */
class Poly(val coeffs: List<Rational>) {

    companion object {

        val ZERO = Poly(listOf(Rational.ZERO))

        /** 按次数构造：`of(常数, 一次项, 二次项, …)`。 */
        fun of(vararg coeffs: Rational): Poly = Poly(coeffs.toList()).normalized()
    }

    /** 去掉尾部的零系数（x^3 系数为 0 就不该占着次数）。 */
    private fun normalized(): Poly {
        var last = coeffs.size
        while (last > 1 && coeffs[last - 1].isZero()) last--
        return Poly(coeffs.subList(0, last))
    }

    /** 次数；零多项式约定为 0（调用方先用 [isZero] 区分）。 */
    fun degree(): Int = coeffs.size - 1

    fun isZero(): Boolean = coeffs.all { it.isZero() }

    fun coeffAt(degree: Int): Rational =
        coeffs.getOrNull(degree) ?: Rational.ZERO

    operator fun plus(other: Poly): Poly {
        val size = maxOf(coeffs.size, other.coeffs.size)
        return Poly((0 until size).map { coeffAt(it) + other.coeffAt(it) }).normalized()
    }

    operator fun minus(other: Poly): Poly = plus(other.negate())

    fun negate(): Poly = Poly(coeffs.map { it.negate() })

    operator fun times(other: Poly): Poly {
        if (isZero() || other.isZero()) return ZERO
        val out = MutableList(coeffs.size + other.coeffs.size - 1) { Rational.ZERO }
        coeffs.forEachIndexed { i, a ->
            other.coeffs.forEachIndexed { j, b ->
                out[i + j] = out[i + j] + a * b
            }
        }
        return Poly(out).normalized()
    }

    /** 非负整数次幂（右操作数在解析层已保证）。 */
    fun pow(exponent: Int): Poly {
        if (exponent < 0) throw IllegalArgumentException("多项式的指数不能为负")
        var acc = Poly(listOf(Rational.ONE))
        repeat(exponent) { acc = acc * this }
        return acc
    }

    /**
     * 代入求值（验根用）。**必须从最高次往低次**做 Horner——coeffs 是
     * 升幂存的，直接 fold 顺序反了会把 x²-5x+6 算成 6x²-5x+1（v0.0.151
     * 单测当场抓到的真 bug）。
     */
    fun evaluateAt(x: Rational): Rational =
        coeffs.reversed().fold(Rational.ZERO) { acc, coef -> acc * x + coef }

    /** 多项式求导：`d(3x^2 - 2x + 1) = 6x - 2`。 */
    fun derivative(): Poly {
        if (coeffs.size <= 1) return ZERO
        return Poly(coeffs.drop(1).mapIndexed { i, coef -> coef * Rational.of((i + 1).toLong()) })
            .normalized()
    }

    /**
     * 降幂格式化：`3x^2 - 2x + 1`。分数系数写成 `(1/2)x`——直接 `1/2x`
     * 会被读成 `1/(2x)`；系数为 ±1 时省略数字；零系数跳过。
     */
    fun format(varName: String = "x"): String {
        if (isZero()) return "0"
        val parts = mutableListOf<String>()
        for (degree in coeffs.indices.reversed()) {
            val coef = coeffAt(degree)
            if (coef.isZero()) continue
            // 负号统一并入连接符：后续项用 " - " 保持数学排版（x^2 - 2x - 1），
            // 首项直接以 "-" 开头。body 一律取正的绝对值。
            val sign = when {
                parts.isEmpty() -> if (coef.isNegative()) "-" else ""
                coef.isNegative() -> " - "
                else -> " + "
            }
            val magnitude = if (coef.isNegative()) coef.negate() else coef
            val body = when (degree) {
                0 -> magnitude.display()
                else -> when {
                    magnitude == Rational.ONE -> varTerm(varName, degree)
                    magnitude.den == java.math.BigInteger.ONE -> "${magnitude.num}${varTerm(varName, degree)}"
                    else -> "(${magnitude.display()})${varTerm(varName, degree)}"
                }
            }
            parts.add("$sign$body")
        }
        return parts.joinToString("")
    }

    private fun varTerm(name: String, degree: Int): String = when (degree) {
        1 -> name
        else -> "$name^$degree"
    }
}

/**
 * 多项式表达式解析器（递归下降）。
 *
 * 文法与 [com.mistakebook.data.chat.MathTools] 的算术求值器同族，但值域
 * 是多项式：数字直接变常数多项式，字母是未知数，`+ - * ^` 做多项式运算，
 * `/` 只允许除以常数，括号与隐式乘法（`2x`、`x(x+1)`、`3(x+1)(x-2)`）展开。
 *
 * 只支持**一个**未知数（x / y / t 任取其一，混用第二个直接报错——
 * 二元方程走 solve_system 的线性专用解析器）。
 */
object PolyExpr {

    private const val MAX_EXPONENT = 64

    fun parse(text: String): Poly {
        val parser = Parser(text)
        val poly = parser.parseAll()
        if (parser.variables.size > 1) {
            throw IllegalArgumentException(
                "表达式里有多个未知数（${parser.variables.joinToString("、")}）——" +
                    "一元方程 / 求导只支持一个变量，方程组请用 solve_system"
            )
        }
        return poly
    }

    private class Parser(private val src: String) {

        private var pos = 0
        val variables = LinkedHashSet<String>()

        fun parseAll(): Poly {
            val poly = parseExpr()
            skipSpace()
            if (pos < src.length) {
                throw IllegalArgumentException("位置 ${pos + 1} 有无法识别的字符「${src[pos]}」")
            }
            return poly
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

        private fun parseExpr(): Poly {
            var value = parseTerm()
            while (true) {
                skipSpace()
                when (peek()) {
                    '+' -> {
                        pos++
                        value += parseTerm()
                    }
                    '-' -> {
                        pos++
                        value -= parseTerm()
                    }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Poly {
            var value = parseUnary()
            while (true) {
                skipSpace()
                when (peek()) {
                    '*' -> {
                        pos++
                        value *= parseUnary()
                    }
                    '/' -> {
                        pos++
                        val divisor = parseUnary()
                        // 只允许除以常数：多项式长除法在这里不值得
                        if (divisor.degree() > 0) {
                            fail("不支持除以含未知数的式子——先化简或因式分解")
                        }
                        if (divisor.coeffAt(0).isZero()) fail("除数为 0")
                        value = value * Poly(listOf(Rational.ONE.div(divisor.coeffAt(0))))
                    }
                    // 隐式乘法：2x、3(x+1)、x(x-1)、(x+1)(x-2)
                    null -> return value
                    else -> if (startsAtom(peek()!!)) {
                        value *= parseUnary()
                    } else {
                        return value
                    }
                }
            }
        }

        private fun startsAtom(c: Char): Boolean =
            c == '(' || c.isLetter() || c.isDigit()

        private fun parseUnary(): Poly {
            skipSpace()
            when (peek()) {
                '-' -> {
                    pos++
                    return parseUnary().negate()
                }
                '+' -> {
                    pos++
                    return parseUnary()
                }
            }
            val base = parsePrimary()
            skipSpace()
            if (peek() == '^') {
                pos++
                return base.pow(parseExponent())
            }
            return base
        }

        /** 幂指数：必须是 0..[MAX_EXPONENT] 的整数（解析层就掐掉无意义的大幂）。 */
        private fun parseExponent(): Int {
            skipSpace()
            val poly = parsePrimary()
            val value = poly.coeffAt(0)
            if (poly.degree() != 0 || value.den != BigInteger.ONE) {
                fail("指数必须是整数")
            }
            val exponent = value.num.toInt()
            if (exponent < 0) fail("指数不能为负")
            if (exponent > MAX_EXPONENT) fail("指数太大了（上限 $MAX_EXPONENT）")
            return exponent
        }

        private fun parsePrimary(): Poly {
            skipSpace()
            val ch = peek() ?: fail("表达式意外结束")
            if (ch == '(') {
                pos++
                val inner = parseExpr()
                if (!eat(')')) fail("缺少右括号")
                return inner
            }
            if (ch.isDigit()) {
                return Poly(listOf(parseNumber()))
            }
            if (ch.isLetter()) {
                // 未知数是**单个字母**：`xy` 按两个变量相乘解析（x·y），
                // 混用第二个变量会在 parseAll 里被拦下——把 `sin` 当成一个
                // 变量名反而是错的多项式。
                variables.add(ch.toString())
                pos++
                return Poly(listOf(Rational.ZERO, Rational.ONE)) // 一次项
            }
            fail("位置 ${pos + 1} 有无法识别的字符「$ch」")
        }

        private fun parseNumber(): Rational {
            val start = pos
            while (peek()?.isDigit() == true) pos++
            if (peek() == '.') {
                pos++
                while (peek()?.isDigit() == true) pos++
            }
            return Rational.parse(src.substring(start, pos))
        }
    }
}
