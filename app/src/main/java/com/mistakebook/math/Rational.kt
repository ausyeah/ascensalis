package com.mistakebook.math

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * 精确有理数（v0.0.151 理科工具链的地基）。
 *
 * ## 为什么不用 Double
 *
 * 大模型算分数靠「背」：`1/3 + 1/6` 写成 0.5 是常见幻觉，`0.1 + 0.2`
 * 的浮点尾数更是灾难。有理数用 BigInteger 存分子分母、约分到最简，
 * **加减乘除与幂全部精确**——弱模型把计算外包给这里，答案不可能错。
 *
 * ## 约定
 *
 * - 分母恒为正、分子带符号、已约分（[of] 里保证）；
 * - 小数按十进制**精确**转为分数：`0.25` → `1/4`，不丢精度；
 * - [sqrtExact] 只在分子分母都是完全平方数时给出精确根，否则返回 null
 *   （一元二次方程的判别式判定要用）；
 * - 除零抛 [IllegalArgumentException]，调用方转成人话文案。
 */
class Rational private constructor(val num: BigInteger, val den: BigInteger) : Comparable<Rational> {

    companion object {

        val ZERO = of(0L)
        val ONE = of(1L)

        fun of(value: Long): Rational = of(BigInteger.valueOf(value), BigInteger.ONE)

        fun of(num: Long, den: Long): Rational =
            of(BigInteger.valueOf(num), BigInteger.valueOf(den))

        /** 已约分构造；分母必须非零。 */
        fun of(num: BigInteger, den: BigInteger): Rational {
            if (den.signum() == 0) throw IllegalArgumentException("分母不能为 0")
            var n = num
            var d = den
            // 分母恒正：负号全部搬到分子上，相等比较与显示才稳定
            if (d.signum() < 0) {
                n = n.negate()
                d = d.negate()
            }
            val g = n.gcd(d)
            return if (g == BigInteger.ONE) Rational(n, d) else Rational(n.divide(g), d.divide(g))
        }

        /**
         * 解析 `3` / `-2.5` / `3/4`。小数走 [BigDecimal] 精确转分数，
         * `0.1` 就是 `1/10`——与 Double 的 0.1000000000000000055 无关。
         */
        fun parse(text: String): Rational {
            val t = text.trim()
            if (t.isEmpty()) throw IllegalArgumentException("空字符串不是数")
            if (t.count { it == '/' } == 1) {
                val (a, b) = t.split('/')
                val n = a.trim().toBigIntegerOrNull()
                    ?: throw IllegalArgumentException("「${a.trim()}」不是整数")
                val d = b.trim().toBigIntegerOrNull()
                    ?: throw IllegalArgumentException("「${b.trim()}」不是整数")
                return of(n, d)
            }
            val bd = t.toBigDecimalOrNull()
                ?: throw IllegalArgumentException("「$t」不是可识别的数（支持 3、-2.5、3/4）")
            val scale = bd.scale()
            return of(bd.scaleByPowerOfTen(scale).toBigInteger(), BigInteger.TEN.pow(scale))
        }

        /**
         * 完全平方数开方：分子分母都能开出整数才有精确根。
         * 判别式开不出精确根时返回 null，调用方退到小数近似。
         */
        fun sqrtExact(value: Rational): Rational? {
            if (value.num.signum() < 0) return null
            val n = value.num.sqrtOrNull() ?: return null
            val d = value.den.sqrtOrNull() ?: return null
            return of(n, d)
        }

        private fun BigInteger.sqrtOrNull(): BigInteger? {
            if (this.signum() == 0) return BigInteger.ZERO
            val root = this.sqrt()
            return if (root * root == this) root else null
        }
    }

    /** 双精度近似：只用于开方这类无理展示，精确路径一律走 [display]。 */
    fun toDouble(): Double =
        BigDecimal(num).divide(BigDecimal(den), MathContext.DECIMAL64).toDouble()

    fun isZero(): Boolean = num.signum() == 0

    fun isNegative(): Boolean = num.signum() < 0

    operator fun plus(other: Rational): Rational = of(num * other.den + other.num * den, den * other.den)

    operator fun minus(other: Rational): Rational = of(num * other.den - other.num * den, den * other.den)

    operator fun times(other: Rational): Rational = of(num * other.num, den * other.den)

    operator fun div(other: Rational): Rational {
        if (other.isZero()) throw IllegalArgumentException("除数为 0")
        return of(num * other.den, den * other.num)
    }

    fun negate(): Rational = of(num.negate(), den)

    /** 整数次幂；负指数取倒数（0 的负指数抛除零）。 */
    fun pow(exponent: Int): Rational = when {
        exponent > 0 -> of(num.pow(exponent), den.pow(exponent))
        exponent == 0 -> ONE
        else -> of(den.pow(-exponent), num.pow(-exponent))
    }

    override fun compareTo(other: Rational): Int =
        (num * other.den).compareTo(other.num * den)

    /** 紧凑显示：整数不带分母，分数 `a/b`。 */
    fun display(): String = if (den == BigInteger.ONE) num.toString() else "$num/$den"

    /** 显示 + 小数参考值：`47/12（≈3.916667）`。整数只显示整数。 */
    fun displayWithDecimal(digits: Int = 6): String =
        if (den == BigInteger.ONE) num.toString() else "$num/$den（≈${toDecimalString(digits)}）"

    /**
     * 小数近似：固定 [digits] 位小数、去尾零。直接 BigDecimal 除法，
     * 不经过 Double（3.916666666… 之类由这里统一截断）。
     */
    fun toDecimalString(digits: Int = 6): String {
        val bd = BigDecimal(num).divide(BigDecimal(den), MathContext(digits + 2, RoundingMode.HALF_UP))
            .setScale(digits, RoundingMode.HALF_UP)
            .stripTrailingZeros()
        var text = bd.toPlainString()
        if (text == "-0") text = "0"
        return text
    }

    override fun equals(other: Any?): Boolean =
        other is Rational && num == other.num && den == other.den

    override fun hashCode(): Int = num.hashCode() * 31 + den.hashCode()

    override fun toString(): String = display()
}
