package com.mistakebook.math

/**
 * 函数绘图用的表达式求值器（issue #54：「画图生图里面最好有一个专用的数学
 * 函数画图软件」）。
 *
 * ## 为什么不复用 calculator 的 ExpressionParser
 *
 * 那个求值器**故意拒绝变量**——`x` 会报「不是已支持的名称」，而且
 * `MathToolsTest` 把这条错误信息钉死了：它是给「算一个数」用的，严格
 * 才是它的价值。画函数图像要的恰恰是**带变量**，还要接受学生最自然的
 * 写法（`2x`、`3sin(x)`、`(x+1)(x-1)` 这种隐式乘法）。两者支持面不同，
 * 硬合并只会把计算器的严格性搞坏，所以这里单写一个。
 *
 * ## 支持面
 *
 * - 变量 `x`（大小写不敏感）、常量 `pi` / `e`；
 * - 运算符 `+ - * / % ^`：`^` 右结合，且优先级高于一元负号
 *   （`-x^2` = `-(x^2)`，与数学写法一致）；
 * - 隐式乘法：`2x`、`3sin(x)`、`2(x+1)`、`(x+1)(x-1)`、`2pi`；
 * - 函数：sqrt abs sin cos tan asin acos atan sinh cosh tanh exp
 *   ln log(以 10 为底，与 calculator 一致) log2 floor ceil round sign；
 * - 三角函数用**弧度制**（与 calculator 一致）。
 *
 * ## 求值失败的约定
 *
 * 语法错（无法识别的字符、括号不配对、不认识的名称）抛
 * [IllegalArgumentException] 并带位置信息——这类错要让人看见，模型可以
 * 据此改正表达式；**定义域之外与除零返回非有限值（NaN / ±Inf）**，
 * 交给绘图器断线处理（`ln(-1)`、`1/0` 只是曲线的一个断点，
 * 不是「表达式写错了」）。
 */
class ExpressionEvaluator private constructor(private val root: Node) {

    /** 在 x 处求值。非有限结果表示这一点没有定义（曲线在此断开）。 */
    fun eval(x: Double): Double = root.eval(x)

    companion object {
        /** 解析表达式；语法错抛 [IllegalArgumentException]。 */
        fun parse(expression: String): ExpressionEvaluator =
            ExpressionEvaluator(Parser(expression).parseAll())
    }

    // ---- 语法树 ----

    private sealed interface Node {
        fun eval(x: Double): Double
    }

    private class ConstNode(private val value: Double) : Node {
        override fun eval(x: Double): Double = value
    }

    private class VarNode : Node {
        override fun eval(x: Double): Double = x
    }

    private class UnaryNode(private val negate: Boolean, private val child: Node) : Node {
        override fun eval(x: Double): Double = if (negate) -child.eval(x) else child.eval(x)
    }

    private class BinaryNode(
        private val op: Char,
        private val left: Node,
        private val right: Node
    ) : Node {
        override fun eval(x: Double): Double {
            val a = left.eval(x)
            val b = right.eval(x)
            return when (op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> a / b
                '%' -> a % b
                '^' -> power(a, b)
                else -> Double.NaN
            }
        }
    }

    private class CallNode(private val fn: (Double) -> Double, private val arg: Node) : Node {
        override fun eval(x: Double): Double = fn(arg.eval(x))
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseAll(): Node {
            val node = parseExpr()
            skipSpace()
            if (pos < src.length) {
                throw IllegalArgumentException("第 ${pos + 1} 个字符「${src[pos]}」看不懂")
            }
            return node
        }

        private fun skipSpace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipSpace()
            return src.getOrNull(pos)
        }

        private fun parseExpr(): Node {
            var node = parseTerm()
            while (true) {
                val c = peek() ?: return node
                if (c == '+' || c == '-') {
                    pos++
                    node = BinaryNode(c, node, parseTerm())
                } else {
                    return node
                }
            }
        }

        private fun parseTerm(): Node {
            var node = parseUnary()
            while (true) {
                val c = peek() ?: return node
                when {
                    c == '*' || c == '/' || c == '%' -> {
                        pos++
                        node = BinaryNode(c, node, parseUnary())
                    }
                    // 隐式乘法：2x、2(x+1)、(x+1)(x-1)、3sin(x)。
                    // 注意函数名后面的括号在 parsePrimary 里已经吃掉，
                    // 所以这里不会把 sin(x) 拆成 sin * (x)。
                    startsAtom(c) -> node = BinaryNode('*', node, parseUnary())
                    else -> return node
                }
            }
        }

        /** 该字符能否作为一个「原子」的开头（数字、x、函数名、左括号）。 */
        private fun startsAtom(c: Char): Boolean =
            c == '(' || c.isDigit() || c == '.' || c.isLetter()

        private fun parseUnary(): Node {
            val c = peek() ?: throw IllegalArgumentException("表达式在这里就结束了")
            if (c == '+') {
                pos++
                return parseUnary()
            }
            if (c == '-') {
                pos++
                // -x^2 必须是 -(x^2)：负号作用在一个「幂」上，而不是 ( -x )^2
                return UnaryNode(negate = true, child = parseUnary())
            }
            return parsePower()
        }

        private fun parsePower(): Node {
            val base = parsePrimary()
            if (peek() == '^') {
                pos++
                // 右结合：2^3^2 = 2^(3^2)
                return BinaryNode('^', base, parseUnary())
            }
            return base
        }

        private fun parsePrimary(): Node {
            val c = peek() ?: throw IllegalArgumentException("表达式在这里就结束了")
            if (c == '(') {
                pos++
                val inner = parseExpr()
                if (peek() != ')') throw IllegalArgumentException("括号没有配对")
                pos++
                return inner
            }
            if (c.isDigit() || c == '.') return ConstNode(parseNumber())
            if (c.isLetter()) return parseName()
            throw IllegalArgumentException("第 ${pos + 1} 个字符「$c」看不懂")
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            // 科学计数法：1e-3、2.5E6
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                val after = src.getOrNull(pos + 1)
                val afterSign = src.getOrNull(pos + 2)
                if (after != null && (after.isDigit() || ((after == '+' || after == '-') &&
                            afterSign != null && afterSign.isDigit()))
                ) {
                    pos++
                    if (src[pos] == '+' || src[pos] == '-') pos++
                    while (pos < src.length && src[pos].isDigit()) pos++
                }
            }
            val text = src.substring(start, pos)
            return text.toDoubleOrNull()
                ?: throw IllegalArgumentException("「$text」不是一个数")
        }

        private fun parseName(): Node {
            val start = pos
            // 名字里允许带数字：`log2` 是一个函数名，不能读成 `log` 后面跟个 2。
            // （数字开头的 `2e3` 走 parseNumber 的科学计数法分支，与这里无关。）
            while (pos < src.length && (src[pos].isLetter() || src[pos].isDigit())) pos++
            val raw = src.substring(start, pos)
            val name = raw.lowercase()
            // 常量可以直接跟运算符；函数必须带括号
            when (name) {
                "pi" -> return ConstNode(Math.PI)
                "e" -> return ConstNode(Math.E)
                "x" -> return VarNode()
            }
            if (peek() != '(') {
                throw IllegalArgumentException(
                    "不认识的名称「$raw」（只支持变量 x、常量 pi/e 与函数名，函数名后面要跟括号）"
                )
            }
            val fn = FUNCTIONS[name]
                ?: throw IllegalArgumentException("不支持的函数「$raw」")
            pos++
            val arg = parseExpr()
            if (peek() != ')') throw IllegalArgumentException("函数 $raw 的括号没有配对")
            pos++
            return CallNode(fn, arg)
        }
    }
}

/**
 * 函数表。
 *
 * 与 [power] 同理放文件级：语法树节点是嵌套类，读不到外层 companion 的成员。
 */
private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
    "sqrt" to { v: Double -> Math.sqrt(v) },
    "abs" to { v: Double -> Math.abs(v) },
    "sin" to { v: Double -> Math.sin(v) },
    "cos" to { v: Double -> Math.cos(v) },
    "tan" to { v: Double -> Math.tan(v) },
    "asin" to { v: Double -> Math.asin(v) },
    "acos" to { v: Double -> Math.acos(v) },
    "atan" to { v: Double -> Math.atan(v) },
    "sinh" to { v: Double -> Math.sinh(v) },
    "cosh" to { v: Double -> Math.cosh(v) },
    "tanh" to { v: Double -> Math.tanh(v) },
    "exp" to { v: Double -> Math.exp(v) },
    "ln" to { v: Double -> Math.log(v) },
    "log" to { v: Double -> Math.log10(v) },
    "log2" to { v: Double -> Math.log(v) / Math.log(2.0) },
    "floor" to { v: Double -> Math.floor(v) },
    "ceil" to { v: Double -> Math.ceil(v) },
    "round" to { v: Double -> Math.round(v).toDouble() },
    "sign" to { v: Double -> Math.signum(v) }
)

private fun power(base: Double, exponent: Double): Double =
    if (exponent == Math.floor(exponent) && !exponent.isInfinite() &&
        Math.abs(exponent) <= 64
    ) {
        var result = 1.0
        val times = Math.abs(exponent).toInt()
        repeat(times) { result *= base }
        if (exponent < 0) 1.0 / result else result
    } else {
        Math.pow(base, exponent)
    }
