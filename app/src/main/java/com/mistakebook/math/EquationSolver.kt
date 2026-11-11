package com.mistakebook.math

/**
 * 方程求解器：一元一次 / 二次方程与二元一次方程组。
 *
 * ## 设计原则（来自 ToRA 一类工具反馈回路的实践）
 *
 * 求解器不只给答案，还**给验算**：根代回原方程逐条验证并写进结果。
 * 弱模型拿到「答案 + 验算成立」的反馈后，向用户转述时几乎不会再
 * 出现符号搬运错误；验算不成立时（实现 bug 或解析偏差）也会暴露出来。
 *
 * 全程精确有理数：判别式、根、验根都是精确计算，只有开不出精确根的
 * 二次方程才退到小数近似。
 */
object EquationSolver {

    // ------------------------------------------------------------ 一元方程

    /**
     * 解一元一次 / 二次方程（自动按次数分类）。
     *
     * 输入形如 `2x+3=11`、`x^2-5x+6=0`、`2(x-3)=x+1`；变量 x / y / t 均可。
     * 结果是给模型转述的多行人话：化简后的方程、判别式（二次）、
     * 精确根 + 小数近似、验根结论。
     */
    fun solveOneVariable(text: String): String {
        val clean = text.trim()
        val parts = clean.split('=')
        if (parts.size != 2) {
            return "解析失败：方程必须恰好有一个「=」，例如 2x+3=11"
        }
        val poly = try {
            PolyExpr.parse(parts[0]) - PolyExpr.parse(parts[1])
        } catch (error: IllegalArgumentException) {
            return "解析失败：${error.message}"
        }

        if (poly.isZero()) return "恒等式：化简后两边完全相同，任意实数都是解。"
        return when (val degree = poly.degree()) {
            0 -> "无解：化简后得到 0 = ${poly.coeffAt(0).display()}（两边矛盾）。"
            1 -> solveLinear(poly)
            2 -> solveQuadratic(poly)
            else -> "这是 $degree 次方程，超出本工具范围（只支持一次 / 二次）。" +
                "试试先因式分解降次；数值验证可以用 calculator 代入检查。"
        }
    }

    private fun solveLinear(poly: Poly): String {
        val b = poly.coeffAt(1)
        val c = poly.coeffAt(0)
        // bx + c = 0 → x = -c/b（b 非 0 由 degree==1 保证）
        val root = c.negate().div(b)
        val verify = verifyRoot(poly, root)
        return buildString {
            appendLine("化简：${poly.format()} = 0")
            appendLine("解：x = ${root.displayWithDecimal()}")
            appendLine(verify)
        }.trimEnd()
    }

    private fun solveQuadratic(poly: Poly): String {
        val a = poly.coeffAt(2)
        val b = poly.coeffAt(1)
        val c = poly.coeffAt(0)
        val discriminant = b * b - a * c * Rational.of(4L)

        val head = "化简：${poly.format()} = 0（a=${a.display()}，b=${b.display()}，c=${c.display()}）\n" +
            "判别式 Δ = b² - 4ac = ${discriminant.display()}"

        if (discriminant.isNegative()) {
            return "$head\nΔ < 0：没有实数解。"
        }

        val rootA = Rational.sqrtExact(discriminant)
        val doubleRoot = b.negate().div(a * Rational.of(2L))

        if (discriminant.isZero()) {
            val verify = verifyRoot(poly, doubleRoot)
            return "$head\nΔ = 0：二重根 x = ${doubleRoot.displayWithDecimal()}\n$verify"
        }

        if (rootA != null) {
            val root1 = b.negate().plus(rootA).div(a * Rational.of(2L))
            val root2 = b.negate().minus(rootA).div(a * Rational.of(2L))
            val verify1 = verifyRoot(poly, root1)
            val verify2 = verifyRoot(poly, root2)
            return "$head\nΔ > 0：两个不相等的实根\n" +
                "x₁ = ${root1.displayWithDecimal()}\n" +
                "x₂ = ${root2.displayWithDecimal()}\n" +
                "$verify1；$verify2"
        }

        // 开不出精确根：给小数近似（Double 足够展示），并如实说明
        val sqrtDelta = kotlin.math.sqrt(discriminant.toDouble())
        val doubleA = a.toDouble()
        val doubleB = b.toDouble()
        val root1 = (-doubleB + sqrtDelta) / (2 * doubleA)
        val root2 = (-doubleB - sqrtDelta) / (2 * doubleA)
        return "$head\nΔ > 0 且 Δ 不是完全平方数：根为无理数\n" +
            "x₁ ≈ ${format(root1)}\n" +
            "x₂ ≈ ${format(root2)}\n" +
            "（精确形式：x = (-b ± √Δ) / 2a，Δ = ${discriminant.display()}）"
    }

    /** 验根：把根代回多项式，精确算术下必须是 0——不为 0 就是实现有 bug，如实报告。 */
    private fun verifyRoot(poly: Poly, root: Rational): String {
        val remainder = poly.evaluateAt(root)
        return if (remainder.isZero()) {
            "验根：代入 x = ${root.display()} 成立 ✓"
        } else {
            "验根：代入 x = ${root.display()} 得 ${remainder.display()} ≠ 0 ✗"
        }
    }

    private fun format(value: Double): String =
        java.math.BigDecimal(value).round(java.math.MathContext(8))
            .stripTrailingZeros().toPlainString()

    // ------------------------------------------------------------ 二元一次方程组

    /**
     * 解二元一次方程组（Cramer 法则）。
     *
     * 两行方程形如 `2x+3y=12`、`x-y=1`，也接受缺项（`x=2`、`y=2x+1`）。
     * 结果给出 x、y 的精确解 + 逐条验算。
     */
    fun solveSystem(equation1: String, equation2: String): String {
        val first = try {
            scanLinear(equation1)
        } catch (error: IllegalArgumentException) {
            return "第一条解析失败：${error.message}"
        }
        val second = try {
            scanLinear(equation2)
        } catch (error: IllegalArgumentException) {
            return "第二条解析失败：${error.message}"
        }
        val (a1, b1, c1) = first
        val (a2, b2, c2) = second

        val determinant = a1 * b2 - a2 * b1
        if (determinant.isZero()) {
            val consistent = a1 * c2 == a2 * c1 && b1 * c2 == b2 * c1
            return if (consistent) {
                "两条方程化简后等价（D = 0）：有无穷多组解，取任意一个 x 都能算出对应的 y。"
            } else {
                "无解（D = 0 且方程矛盾）：两条直线平行。"
            }
        }

        val deltaX = c1 * b2 - c2 * b1
        val deltaY = a1 * c2 - a2 * c1
        val x = deltaX.div(determinant)
        val y = deltaY.div(determinant)

        // 逐条验算：代入原方程两边比对（精确）
        val check1 = a1 * x + b1 * y
        val check2 = a2 * x + b2 * y
        val verify1 = if (check1 == c1) "第一条 ✓" else "第一条 ✗（代入得 ${check1.display()} ≠ ${c1.display()}）"
        val verify2 = if (check2 == c2) "第二条 ✓" else "第二条 ✗（代入得 ${check2.display()} ≠ ${c2.display()}）"

        return buildString {
            appendLine("解：x = ${x.displayWithDecimal()}，y = ${y.displayWithDecimal()}")
            appendLine("验算：$verify1；$verify2")
        }.trimEnd()
    }

    /**
     * 把一条二元一次方程扫成 `ax + by = c` 的标准形。
     *
     * 支持隐式系数（`x`、`-y`、`2x`）、小数与分数系数（`1/2x`?——不，
     * 分数系数写成 `0.5x` 或 `x/2` 这种交给模型先整理；这里接受小数），
     * 等号两侧任意放置，缺项视为系数 0。
     */
    private fun scanLinear(text: String): Triple<Rational, Rational, Rational> {
        val clean = text.trim()
        if (clean.count { it == '=' } != 1) {
            throw IllegalArgumentException("必须恰好有一个「=」，例如 2x+3y=12")
        }
        val (lhs, rhs) = clean.split('=').map { it.trim() }
        var a = Rational.ZERO
        var b = Rational.ZERO
        var c = Rational.ZERO

        // 两侧分别扫：等号左边系数累加为正贡献，右边为负贡献（移项）
        scanSide(lhs) { coefX, coefY, constant ->
            a += coefX; b += coefY; c -= constant
        }
        scanSide(rhs) { coefX, coefY, constant ->
            a -= coefX; b -= coefY; c += constant
        }
        return Triple(a, b, c)
    }

    private fun fail(reason: String): Nothing = throw IllegalArgumentException(reason)

    /**
     * 逐项扫一行二元线性表达式（如 `2x+3y-5`），累加进回调。
     *
     * 支持隐式系数（`x`、`-y`）、整数 / 小数 / 分数系数（`1/2x`）；
     * 未知数只认 x 与 y（大小写均可），其他字母明确报错而不是猜。
     */
    private inline fun scanSide(
        text: String,
        emit: (coefX: Rational, coefY: Rational, constant: Rational) -> Unit
    ) {
        var pos = 0
        var pendingSign = Rational.ONE
        while (true) {
            if (pos >= text.length) break
            when (val ch = text[pos]) {
                ' ' -> pos++
                '+' -> {
                    pendingSign = Rational.ONE
                    pos++
                }
                '-' -> {
                    pendingSign = Rational.ONE.negate()
                    pos++
                }
                else -> {
                    val start = pos
                    // 数字系数：整数、小数或 a/b 分数
                    val numStart = pos
                    while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
                    if (pos < text.length && text[pos] == '/' &&
                        pos > numStart && text.getOrNull(pos + 1)?.isDigit() == true
                    ) {
                        pos++
                        while (pos < text.length && text[pos].isDigit()) pos++
                    }
                    val hasCoef = pos > numStart
                    val coef = if (hasCoef) {
                        Rational.parse(text.substring(numStart, pos)) * pendingSign
                    } else {
                        pendingSign
                    }
                    if (pos < text.length && text[pos].lowercaseChar() in "xy") {
                        when (text[pos].lowercaseChar()) {
                            'x' -> emit(coef, Rational.ZERO, Rational.ZERO)
                            'y' -> emit(Rational.ZERO, coef, Rational.ZERO)
                        }
                        pos++
                    } else {
                        if (!hasCoef) {
                            fail("位置 ${start + 1} 有无法识别的字符「$ch」")
                        }
                        emit(Rational.ZERO, Rational.ZERO, coef)
                    }
                    pendingSign = Rational.ONE
                }
            }
        }
    }
}
