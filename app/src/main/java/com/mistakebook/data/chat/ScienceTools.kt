package com.mistakebook.data.chat

import com.mistakebook.math.EquationSolver
import com.mistakebook.math.Rational
import com.mistakebook.math.PolyExpr
import com.mistakebook.math.SequenceCalc
import com.mistakebook.math.StatsCalc
import com.mistakebook.math.UnitConvert
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
 * 理科工具链（v0.0.151）：把**结构化的理科计算**外包给确定性引擎。
 *
 * ## 为什么要有一整箱，而不止 calculator
 *
 * ToRA / PoT 这类工具集成推理的研究结论一致：把计算外包给确定性工具，
 * **越弱的模型收益越大**。但 `calculator` 只覆盖「给一个表达式求一个数」；
 * 理科题的确定性步骤远不止算术——分数要精确不要 0.3333、方程要判别式
 * 与验根、多项式要展开求导、统计量要按公式、单位要换算。这些结构化的
 * 活每一个都值得单独一把工具：描述里写清「何时必须调用」，弱模型照着
 * 描述选工具的正确率远高于自己挑参数。
 *
 * ## 每把工具都带验算反馈
 *
 * 求解器的输出里直接写「验根成立 ✓」——模型把答案与验算一起转述给
 * 用户，等于把 ToRA 的工具反馈回路搬进了对话。
 *
 * ## 全部零依赖
 *
 * 引擎都在 `com.mistakebook.math`（精确有理数 + 多项式），手写、无 eval、
 * 非法输入返回人话错误文案，绝不抛异常打断生成。
 */
object ScienceTools {

    const val EXACT_FRACTION = "exact_fraction"
    const val SOLVE_EQUATION = "solve_equation"
    const val SOLVE_SYSTEM = "solve_system"
    const val DERIVATIVE = "derivative"
    const val STATS = "stats"
    const val SEQUENCE = "sequence"
    const val UNIT_CONVERT = "unit_convert"

    private val names = setOf(
        EXACT_FRACTION, SOLVE_EQUATION, SOLVE_SYSTEM,
        DERIVATIVE, STATS, SEQUENCE, UNIT_CONVERT
    )

    fun handles(name: String): Boolean = name in names

    /**
     * `in ScienceTools` 的语法糖：`when (call.name)` 的分支条件不能用
     * 布尔表达式（K2），但 `in` 合法——分发处靠它把整箱工具接进一个分支。
     */
    operator fun contains(name: String): Boolean = handles(name)

    /** 界面 stage 文案：执行中短暂显示，让用户知道正在算什么。 */
    fun stageLabel(name: String): String = when (name) {
        EXACT_FRACTION -> "正在精确计算分数…"
        SOLVE_EQUATION -> "正在解方程…"
        SOLVE_SYSTEM -> "正在解方程组…"
        DERIVATIVE -> "正在求导…"
        STATS -> "正在算统计量…"
        SEQUENCE -> "正在算数列…"
        UNIT_CONVERT -> "正在换算单位…"
        else -> "正在用理科工具计算…"
    }

    fun all(): List<LlmTool> = listOf(
        exactFractionTool(), solveEquationTool(), solveSystemTool(),
        derivativeTool(), statsTool(), sequenceTool(), unitConvertTool()
    )

    /** 按工具名执行；参数缺失 / 非法一律返回人话错误文案。 */
    fun execute(name: String, arguments: String): String = when (name) {
        EXACT_FRACTION -> {
            val expression = stringArg(arguments, "expression")
                ?: return "计算失败：参数缺少 expression 字段"
            safe { ExactFraction.evaluate(expression) }
        }
        SOLVE_EQUATION -> {
            val equation = stringArg(arguments, "equation")
                ?: return "求解失败：参数缺少 equation 字段"
            safe { EquationSolver.solveOneVariable(equation) }
        }
        SOLVE_SYSTEM -> {
            val first = stringArg(arguments, "equation1")
                ?: return "求解失败：参数缺少 equation1 字段"
            val second = stringArg(arguments, "equation2")
                ?: return "求解失败：参数缺少 equation2 字段"
            safe { EquationSolver.solveSystem(first, second) }
        }
        DERIVATIVE -> {
            val expression = stringArg(arguments, "expression")
                ?: return "求导失败：参数缺少 expression 字段"
            safe { derivative(expression) }
        }
        STATS -> {
            val numbers = stringArg(arguments, "numbers")
                ?: return "统计失败：参数缺少 numbers 字段"
            safe { StatsCalc.describe(numbers) }
        }
        SEQUENCE -> {
            val kind = stringArg(arguments, "kind") ?: ""
            val first = stringArg(arguments, "first_term")
                ?: return "数列失败：参数缺少 first_term 字段"
            val common = stringArg(arguments, "common")
                ?: return "数列失败：参数缺少 common 字段（等差是公差 d，等比是公比 r）"
            val n = intArg(arguments, "n")
                ?: return "数列失败：参数缺少 n 字段"
            safe { SequenceCalc.describe(kind, first, common, n) }
        }
        UNIT_CONVERT -> {
            val value = stringArg(arguments, "value")
                ?: return "换算失败：参数缺少 value 字段"
            val from = stringArg(arguments, "from")
                ?: return "换算失败：参数缺少 from 字段"
            val to = stringArg(arguments, "to")
                ?: return "换算失败：参数缺少 to 字段"
            safe { UnitConvert.convert(value, from, to) }
        }
        else -> "未知工具：$name"
    }

    private inline fun safe(block: () -> String): String = try {
        block()
    } catch (error: IllegalArgumentException) {
        "计算失败：${error.message}"
    } catch (t: Throwable) {
        "计算失败：${t.message ?: "无法处理该输入"}"
    }

    /** 多项式求导：解析 → 求导 → 格式化。 */
    private fun derivative(expression: String): String {
        val poly = PolyExpr.parse(expression)
        if (poly.degree() > 12) return "次数太高（>12）——导数请逐次求或先化简"
        return "(${expression.trim()})' = ${poly.derivative().format()}"
    }

    // ------------------------------------------------------------ 参数提取

    private fun stringArg(arguments: String, field: String): String? = runCatching {
        Json.parseToJsonElement(arguments).jsonObject[field]
            ?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun intArg(arguments: String, field: String): Int? = runCatching {
        val element = Json.parseToJsonElement(arguments).jsonObject[field] ?: return null
        element.jsonPrimitive.content.trim().toIntOrNull()
    }.getOrNull()

    // ------------------------------------------------------------ 工具定义

    private fun tool(name: String, description: String, params: Map<String, Pair<String, String>>, required: List<String>): LlmTool =
        LlmTool(
            type = "function",
            function = ToolFunction(
                name = name,
                description = description,
                parameters = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            params.forEach { (key, value) ->
                                put(
                                    key,
                                    buildJsonObject {
                                        put("type", value.first)
                                        put("description", value.second)
                                    }
                                )
                            }
                        }
                    )
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                }
            )
        )

    private fun exactFractionTool(): LlmTool = tool(
        name = EXACT_FRACTION,
        description = "精确分数运算（结果是最简分数，不是近似小数）。" +
            "凡是涉及分数、比例、比较大小、通分约分的计算都必须调用本工具：" +
            "大模型心算 1/3+1/6 这类分数几乎必错。支持 + - * / ^ ( )、" +
            "整数、小数（0.25）、分数（3/4），例如 1/3+1/6、(2/3)/(4/9)、(1/2)^3、" +
            "0.25+1/2。比较两个分数大小时各算一遍看结果。三角/开方等函数请用 calculator。",
        params = mapOf(
            "expression" to Pair(
                "string",
                "分数表达式，例如 1/3+1/6、(2/3)*(9/4)、0.25+1/2。不要带「=」与单位。"
            )
        ),
        required = listOf("expression")
    )

    private fun solveEquationTool(): LlmTool = tool(
        name = SOLVE_EQUATION,
        description = "解一元一次 / 二次方程（精确解 + 自动验根）。" +
            "凡是「解方程 / 求根 / x 等于几」都要调用本工具，不要自己配方或因式分解——" +
            "弱模型在这两步的符号错误率最高。返回化简后的方程、判别式、精确根" +
            "（分数）与小数近似、以及把根代回原方程的验根结论。变量支持 x / y / t；" +
            "支持括号与隐式乘法：2(x-3)=x+1、(x+1)(x-2)=0。高次方程会提示先降次。",
        params = mapOf(
            "equation" to Pair(
                "string",
                "完整方程，例如 2x+3=11、x^2-5x+6=0、2(x-3)=x+1。必须恰好一个「=」。"
            )
        ),
        required = listOf("equation")
    )

    private fun solveSystemTool(): LlmTool = tool(
        name = SOLVE_SYSTEM,
        description = "解二元一次方程组（精确解 + 逐条验算）。" +
            "凡是「两个未知数、两条方程」都必须调用本工具，不要自己消元。" +
            "返回 x、y 的精确分数解与小数近似，并把解代回两条方程逐条验算。" +
            "支持缺项与 y=2x+1 这类形式；无解 / 无穷多解会明确说明。",
        params = mapOf(
            "equation1" to Pair("string", "第一条方程，例如 2x+3y=12"),
            "equation2" to Pair("string", "第二条方程，例如 x-y=1")
        ),
        required = listOf("equation1", "equation2")
    )

    private fun derivativeTool(): LlmTool = tool(
        name = DERIVATIVE,
        description = "多项式求导（精确系数）。" +
            "凡是「求导数 / f'(x) / 导函数」且被导式是多项式时必须调用本工具，" +
            "不要自己背求导公式逐项算——幂法则的系数与指数错位是弱模型高频错误。" +
            "支持括号展开：求 (x+1)(x-2) 的导数直接传 (x+1)(x-2)。输入不带「=」与 f(x) 包装。" +
            "三角 / 指数 / 对数函数的求导超出本工具范围，模型按公式自己写。",
        params = mapOf(
            "expression" to Pair(
                "string",
                "关于 x 的多项式，例如 3x^2-2x+1、(x+1)(x-2)、2x^3-x/2"
            )
        ),
        required = listOf("expression")
    )

    private fun statsTool(): LlmTool = tool(
        name = STATS,
        description = "计算一组数的统计量：个数、总和、平均数、中位数、众数、" +
            "最大最小、总体方差 / 样本方差、标准差（全部按公式精确计算）。" +
            "凡是「平均数 / 中位数 / 众数 / 方差 / 标准差」的题目都必须调用本工具，" +
            "不要自己排顺序算平均——中位数的奇偶分支与方差公式是最常见的翻车点。",
        params = mapOf(
            "numbers" to Pair(
                "string",
                "用逗号分隔的一串数，例如 88,92,75,90,88"
            )
        ),
        required = listOf("numbers")
    )

    private fun sequenceTool(): LlmTool = tool(
        name = SEQUENCE,
        description = "等差 / 等比数列的第 n 项与前 n 项和（精确计算）。" +
            "凡是「数列的通项公式数值 / 第几项是多少 / 前 n 项和」都调用本工具，" +
            "等比求和公式（1-rⁿ 与 rⁿ-1 的方向、除以 1-r 还是 r-1）是弱模型高频错误。" +
            "kind=arithmetic 时 common 是公差 d；kind=geometric 时 common 是公比 r。" +
            "需要反推 a₁ / d / r 时先用本工具验证假设。",
        params = mapOf(
            "kind" to Pair("string", "arithmetic（等差）或 geometric（等比）"),
            "first_term" to Pair("string", "首项 a₁，例如 3 或 -2/5"),
            "common" to Pair("string", "等差填公差 d，等比填公比 r，例如 4 或 1/2 或 -3"),
            "n" to Pair("integer", "项数 n（要求第 n 项与前 n 项和）")
        ),
        required = listOf("kind", "first_term", "common", "n")
    )

    private fun unitConvertTool(): LlmTool = tool(
        name = UNIT_CONVERT,
        description = "常见物理单位换算（精确）。" +
            "凡是涉及单位换算（km/h↔m/s、L↔m³、kWh↔J、亩↔m²、atm↔Pa 等）" +
            "都必须调用本工具，不要自己乘进制——弱模型 36km/h 换 m/s 写 3.6 是常见错误。" +
            "支持：长度 mm/cm/m/km/in/ft/mile；质量 mg/g/kg/t；时间 ms/s/min/h；" +
            "速度 m/s、km/h、km/min、mile/h；面积 cm²/m²/km²/公顷/亩；" +
            "体积 mL/L/m³；能量 J/kJ/Wh/kWh；压强 Pa/kPa/MPa/bar/atm。" +
            "同一类单位之间互换；跨类别会拒绝。",
        params = mapOf(
            "value" to Pair("string", "要换算的数值，例如 36 或 1/2"),
            "from" to Pair("string", "原单位，例如 km/h"),
            "to" to Pair("string", "目标单位，例如 m/s")
        ),
        required = listOf("value", "from", "to")
    )
}

/**
 * 精确分数表达式求值器（[ScienceTools.EXACT_FRACTION] 的引擎）。
 *
 * 复用 [PolyExpr] 的解析骨架会把分数表达式也当多项式——分数运算只
 * 涉及常数，这里直接用 [Rational] 的四则写一个窄解析器：数字（整数 /
 * 小数 / a/b）、+ - * / ^、括号、隐式乘法（`1/2 3`? 不支持——分数必须
 * 显式写清楚，弱模型自己写表达式时含糊的写法就该报错）。
 */
private object ExactFraction {

    fun evaluate(text: String): String = Parser(text).parseAll().displayWithDecimal()

    private class Parser(private val src: String) {

        private var pos = 0
        private val maxExponent = 64

        fun parseAll(): Rational {
            val result = parseExpr()
            skipSpace()
            if (pos < src.length) {
                throw IllegalArgumentException("位置 ${pos + 1} 有无法识别的字符「${src[pos]}」")
            }
            return result
        }

        fun parseExpr(): Rational {
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

        private fun parseTerm(): Rational {
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
                        value /= parseUnary()
                    }
                    else -> return value
                }
            }
        }

        private fun parseUnary(): Rational {
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
                skipSpace()
                val exponent = parseSignedInt()
                if (kotlin.math.abs(exponent) > maxExponent) {
                    throw IllegalArgumentException("指数太大了（上限 $maxExponent）")
                }
                return base.pow(exponent)
            }
            return base
        }

        /** 指数允许负数：分数的负次幂（倒数）在分数运算里很常用。 */
        private fun parseSignedInt(): Int {
            val negative = peek() == '-'
            if (negative) pos++
            skipSpace()
            val start = pos
            while (peek()?.isDigit() == true) pos++
            val digits = src.substring(start, pos)
            if (digits.isEmpty()) throw IllegalArgumentException("指数必须是整数")
            val value = digits.toIntOrNull()
                ?: throw IllegalArgumentException("指数太大")
            return if (negative) -value else value
        }

        private fun parsePrimary(): Rational {
            skipSpace()
            val ch = peek() ?: throw IllegalArgumentException("表达式意外结束")
            if (ch == '(') {
                pos++
                val inner = parseExpr()
                skipSpace()
                if (peek() != ')') throw IllegalArgumentException("缺少右括号")
                pos++
                return inner
            }
            if (ch.isDigit()) {
                val start = pos
                while (peek()?.isDigit() == true) pos++
                if (peek() == '.') {
                    pos++
                    while (peek()?.isDigit() == true) pos++
                }
                // 分数：整数后面紧跟「/ 数字」（中间不能有空格，2/3 是一个数）
                if (peek() == '/' && src.getOrNull(pos + 1)?.isDigit() == true) {
                    pos++
                    while (peek()?.isDigit() == true) pos++
                }
                return Rational.parse(src.substring(start, pos))
            }
            throw IllegalArgumentException("位置 ${pos + 1} 有无法识别的字符「$ch」")
        }

        private fun skipSpace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? = src.getOrNull(pos)
    }
}
