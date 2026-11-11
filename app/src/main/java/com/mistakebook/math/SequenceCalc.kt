package com.mistakebook.math

/**
 * 等差 / 等比数列的通项与求和。
 *
 * 高中数列题的公式代 入是弱模型的高频翻车点（等比求和的 `r^n - 1`
 * 与 `1 - r^n` 混写、`n-1` 与 `n` 错位）。全部用 [Rational] 精确计算：
 * 分数首项、负公比、分数公比都精确，等比求和的分式不丢精度。
 */
object SequenceCalc {

    /** 公比的幂上限：n 太大时 Sn 的分分子会爆炸，10000 步足够教学场景。 */
    private const val MAX_N = 10_000

    /**
     * @param kind `arithmetic`（等差）或 `geometric`（等比）
     * @param firstTerm 首项 a₁
     * @param common 等差的公差 d 或等比的公比 r
     * @param n 项数（第 n 项 + 前 n 项和）
     */
    fun describe(kind: String, firstTerm: String, common: String, n: Int): String {
        if (n < 1) return "n 必须从 1 开始"
        if (n > MAX_N) return "n 太大了（上限 $MAX_N）——超出教学场景，请让模型用通项公式自己推"
        val a1 = try {
            Rational.parse(firstTerm)
        } catch (error: IllegalArgumentException) {
            return "首项「$firstTerm」解析失败：${error.message}"
        }
        val param = try {
            Rational.parse(common)
        } catch (error: IllegalArgumentException) {
            return "公差/公比「$common」解析失败：${error.message}"
        }

        return when (kind.lowercase()) {
            "arithmetic", "等差" -> describeArithmetic(a1, param, n)
            "geometric", "等比" -> describeGeometric(a1, param, n)
            else -> "kind 必须是 arithmetic（等差）或 geometric（等比）"
        }
    }

    private fun describeArithmetic(a1: Rational, d: Rational, n: Int): String {
        val an = a1 + d * Rational.of((n - 1).toLong())
        val sn = Rational.of(n.toLong()) * (a1 * Rational.of(2L) + d * Rational.of((n - 1).toLong()))
            .div(Rational.of(2L))
        return buildString {
            appendLine("等差数列：a₁ = ${a1.display()}，d = ${d.display()}，n = $n")
            appendLine("第 n 项 aₙ = a₁ + (n-1)d = ${an.displayWithDecimal()}")
            appendLine("前 n 项和 Sₙ = n(a₁ + aₙ)/2 = ${sn.displayWithDecimal()}")
        }.trimEnd()
    }

    private fun describeGeometric(a1: Rational, r: Rational, n: Int): String {
        if (r.isZero() && n > 1) {
            return "公比 r = 0 时从第二项起全为 0——通常题目不是这个意思，请确认公比。"
        }
        val an = a1 * r.pow(n - 1)
        val sn = if (r == Rational.ONE) {
            a1 * Rational.of(n.toLong())
        } else {
            a1 * (r.pow(n) - Rational.ONE).div(r - Rational.ONE)
        }
        return buildString {
            appendLine("等比数列：a₁ = ${a1.display()}，r = ${r.display()}，n = $n")
            appendLine("第 n 项 aₙ = a₁·r^(n-1) = ${an.displayWithDecimal()}")
            appendLine(
                if (r == Rational.ONE) {
                    "r = 1：前 n 项和 Sₙ = n·a₁ = ${sn.displayWithDecimal()}"
                } else {
                    "前 n 项和 Sₙ = a₁(1-rⁿ)/(1-r) = ${sn.displayWithDecimal()}"
                }
            )
        }.trimEnd()
    }
}
