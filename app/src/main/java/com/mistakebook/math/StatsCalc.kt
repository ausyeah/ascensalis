package com.mistakebook.math

/**
 * 统计量计算（精确有理数 + 双精度补充）。
 *
 * 平均数、中位数、方差这类「照公式算」的活，弱模型一步错就是整题错。
 * 全部用 [Rational] 精确计算——小数输入按十进制精确转分数——只有
 * 标准差的开方可能落到小数近似（能开出精确根时照样精确）。
 */
object StatsCalc {

    /**
     * 输入逗号 / 顿号 / 分号 / 空白分隔的一串数，输出全套统计量的
     * 多行人话。样本方差（除以 n-1）在 n=1 时说明 unavailable。
     */
    fun describe(listText: String): String {
        val values = listText.split(',', '，', ';', '；', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Rational.parse(it) }
        if (values.isEmpty()) return "没有解析到任何数——请用逗号分隔，例如 88,92,75,90"

        val n = values.size
        val sum = values.fold(Rational.ZERO) { acc, v -> acc + v }
        val mean = sum.div(Rational.of(n.toLong()))

        val sorted = values.sorted()
        val median = if (n % 2 == 1) {
            sorted[n / 2]
        } else {
            sorted[n / 2 - 1].plus(sorted[n / 2]).div(Rational.of(2L))
        }

        // 众数：出现次数最多的值（可能并列多个，也可能都不重复）
        val frequency = values.groupingBy { it.display() }.eachCount()
        val maxFrequency = frequency.values.max()
        val modes = frequency.filterValues { it == maxFrequency }.keys
        val modeText = if (maxFrequency == 1) {
            "没有众数（每个数都只出现一次）"
        } else {
            modes.joinToString("、")
        }

        val variancePopulation = values.fold(Rational.ZERO) { acc, v ->
            acc + (v - mean) * (v - mean)
        }.div(Rational.of(n.toLong()))

        val varianceSampleText = if (n > 1) {
            val varianceSample = values.fold(Rational.ZERO) { acc, v ->
                acc + (v - mean) * (v - mean)
            }.div(Rational.of((n - 1).toLong()))
            sqrtDisplay(varianceSample)
        } else {
            "样本方差需要至少 2 个数"
        }

        return buildString {
            appendLine("个数 n = $n")
            appendLine("总和 = ${sum.displayWithDecimal()}")
            appendLine("平均数 = ${mean.displayWithDecimal()}")
            appendLine("中位数 = ${median.displayWithDecimal()}")
            appendLine("众数 = $modeText（出现 $maxFrequency 次）")
            appendLine("最大 = ${sorted.last().display()}，最小 = ${sorted.first().display()}")
            appendLine("方差（总体，除以 n）= ${sqrtDisplay(variancePopulation).removePrefix("≈")}")
            appendLine("方差（样本，除以 n-1）= $varianceSampleText")
        }.trimEnd()
    }

    /**
     * 方差展示：能开出精确根就给精确标准差；否则给方差精确值 +
     * 标准差小数。返回形如 `25/4（标准差 = 5/2）` 或 `≈2.160247`。
     */
    private fun sqrtDisplay(variance: Rational): String {
        val std = Rational.sqrtExact(variance)
        return if (std != null) {
            "${variance.display()}（标准差 = ${std.display()}）"
        } else {
            val approx = kotlin.math.sqrt(variance.toDouble())
            "${variance.display()}（标准差 ≈ ${"%.6f".format(approx).trimEndZeros()}）"
        }
    }

    private fun String.trimEndZeros(): String =
        stripTrailingZerosIfPresent()

    private fun String.stripTrailingZerosIfPresent(): String =
        if (contains('.') && endsWith("0")) {
            dropLastWhile { it == '0' }.let { if (it.endsWith(".")) it.dropLast(1) else it }
        } else {
            this
        }
}
