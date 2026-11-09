package com.mistakebook.math

/**
 * 一条待画的曲线。用户的写法千奇百怪，先归类再算：
 *
 * | 用户写的 | 类型 | 怎么画 |
 * |---|---|---|
 * | `x^2+2x` | [Explicit]（默认） | 逐 x 采样求 y |
 * | `y = sin(x)` | [Explicit] | 同上，`y=` 只是声明 |
 * | `x = y^2` | [Implicit] | 有 `=` 且左边不是 y/r → 隐函数 |
 * | `x^2+y^2=1`、`x^2-y^2=1` | [Implicit] | Marching Squares 求等值线 |
 * | `r = 1+cos(θ)`、`r=sin(3θ)` | [Polar] | 逐 θ 采样，转成 (r cosθ, r sinθ) |
 * | `x^2+y^2`（裸的、含 y） | [Implicit] | 当作 `=0`；但 `x^2+y^2=0` 只有一个点 |
 *
 * ## 关于「裸的含 y 表达式」
 *
 * 只敲 `x^2+y^2` 时按 `=0` 处理。要说清楚：`x^2+y^2=0` 在实数域**只有一个点**
 * (0,0)，所以这种写法画出来是空的（调用方会报「窗口里找不到解」）。
 * 想画圆得写 `x^2+y^2=1`。这么处理不是为了猜用户意图，而是为了不把
 * 「含 y」直接判成语法错误——报错里带着「网格求等值线」的说明，
 * 用户就知道该补上等号右边。
 *
 * ## 极坐标的 θ 范围
 *
 * 默认 `[0, 2π]`（玫瑰线、心形线、圆都在这段里闭合）。要画更长的
 * （如 `r=cos(θ/3)` 需要 6π），把 x 轴范围当作 θ 范围传进来
 * ——见 [com.mistakebook.math.FunctionPlotter] 的 xMin/xMax 处理。
 */
sealed interface PlotCurve {

    /** 用户原文：图例与报错都要用它，不要用改写后的形式。 */
    val raw: String

    /** y = f(x)：最普通的一种。 */
    data class Explicit(
        override val raw: String,
        val expression: String
    ) : PlotCurve

    /** r = f(θ)：极坐标。 */
    data class Polar(
        override val raw: String,
        val expression: String
    ) : PlotCurve

    /** F(x, y) = 0：由 `left - right` 得到。 */
    data class Implicit(
        override val raw: String,
        val left: String,
        val right: String
    ) : PlotCurve

    companion object {

        /**
         * 归类并**验证**（语法错在这里就抛，带人话原因）。
         *
         * 判断顺序是有讲究的：先看 `y =` / `r =` 这两个显式声明，再看
         * 有没有别的 `=`（隐函数），最后才按用到的变量猜——顺序反了会把
         * `y=x^2` 判成隐函数（多花几百倍算力去求等值线，形状还更毛糙）。
         */
        fun parse(text: String): PlotCurve {
            val raw = text.trim()
            if (raw.isEmpty()) throw IllegalArgumentException("表达式是空的")

            val eq = raw.indexOf('=')
            if (eq >= 0) {
                val lhs = raw.substring(0, eq).trim()
                val rhs = raw.substring(eq + 1).trim()
                if (rhs.isEmpty()) throw IllegalArgumentException("「$raw」等号右边是空的")
                if (lhs.isEmpty()) throw IllegalArgumentException("「$raw」等号左边是空的")
                when (lhs.lowercase()) {
                    // 先校验再原样使用：Explicit 要的是「等号右边的原文」，
                    // 而 validated() 返回的是求值器（它还带变量信息）。
                    "y" -> {
                        validated(rhs)
                        return Explicit(raw, rhs)
                    }
                    "r" -> return Polar(raw, validatedTheta(rhs))
                }
                // 其它任何带等号的都当方程：x=y^2、x^2+y^2=1、sin(x)=cos(y)…
                validated(lhs)
                validated(rhs)
                return Implicit(raw, lhs, rhs)
            }

            val evaluator = validated(raw)
            return when {
                evaluator.usesY -> Implicit(raw, raw, "0")
                evaluator.usesTheta && !evaluator.usesX -> Polar(raw, raw)
                else -> Explicit(raw, raw)
            }
        }

        /** 显函数/隐函数：解析一遍把语法错误暴露出来。 */
        private fun validated(expression: String): ExpressionEvaluator =
            try {
                ExpressionEvaluator.parse(expression)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("「$expression」看不懂：${error.message}")
            }

        /**
         * 极坐标的校验：**允许常数 r**（`r=1` 就是单位圆，很常用），
         * 但 r 不能混用 x / y——那两个是直角坐标的变量，混进来只会画出
         * 一条看不懂的曲线，不如直接说清楚。
         */
        private fun validatedTheta(expression: String): String {
            val evaluator = validated(expression)
            if (evaluator.usesX || evaluator.usesY) {
                throw IllegalArgumentException("极坐标的 r 只能用 θ，不能用 x / y")
            }
            return expression
        }
    }
}
