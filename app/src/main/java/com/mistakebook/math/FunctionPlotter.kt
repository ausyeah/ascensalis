package com.mistakebook.math

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * 数学函数图像绘制（issue #54：「画图生图里面最好有一个专用的数学函数画图
 * 软件」）。
 *
 * ## 为什么必须自己算
 *
 * 在此之前「画函数图像」有两条路，都不准：
 * - 让模型手写 ```svg 曲线——它是**看着像**画的，单调性、极值、交点位置全靠
 *   猜，学生拿去核对答案会被带偏；
 * - 让生图模型画——画出来是一张「有坐标轴的插画」，连刻度都对不上。
 *
 * 这里改成：表达式交给 [ExpressionEvaluator] 精确求值 → 采样点 → 生成
 * 坐标系 SVG（真刻度、真采样）。图是**算出来的**，不是画个大概。
 *
 * ## 纯函数
 *
 * 不碰 Android、不碰文件、不碰网络，输入输出都是数据，JVM 单测直接覆盖
 * （`FunctionPlotterTest`）。返回的 SVG 只用到基本形状与文本，能通过
 * [com.mistakebook.markdown.SvgSanitizer] 的清洗，也能被 AndroidSVG 解码。
 *
 * ## 断线而不是连线
 *
 * `ln(x)` 在 x<0 没有定义、`1/x` 在 0 处是无穷、`tan(x)` 有极点——这些点上
 * 采样值是非有限值或跨窗口的跳变。**必须断线**：连起来就会出现一条假的
 * 竖直渐近线，学生会把它当成函数的一部分。
 */
object FunctionPlotter {

    /** 一次最多画几条曲线（再多图就看不清了，工具描述里也这么写）。 */
    const val MAX_CURVES = 4

    /** 画布尺寸（px）。宽度按手机聊天区缩放显示，纵横比接近 16:10。 */
    private const val WIDTH = 720
    private const val HEIGHT = 440

    private const val MARGIN_LEFT = 62
    private const val MARGIN_RIGHT = 20
    private const val MARGIN_BOTTOM = 44
    /** 无标题时的上边距；有标题时给标题留一行。 */
    private const val MARGIN_TOP = 28
    private const val MARGIN_TOP_TITLED = 58

    /** 采样点数：太密 SVG 巨大，太疏曲线发折。480 在 720px 宽下足够顺滑。 */
    private const val SAMPLES = 480

    /** 相邻采样跳变超过「窗口高度 × 这个倍数」就判定为极点，断开路径。 */
    private const val POLE_JUMP_FACTOR = 6.0

    /**
     * 自动 y 窗口时，极值跨度超过「中间 90% 样本跨度」的多少倍就改用分位数。
     *
     * 4 倍这条线是实测调出来的：`1/x` 在 x∈[-2,2] 上的极值跨度是中间样本的
     * 6 倍左右，用 8 倍会漏判 → 窗口被拉到 ±120 → 极点两侧的跳变反而"不显眼"，
     * 于是左右两支被一条假竖线连起来（单测 `极点处断线而不是连一条假的竖线`
     * 就是抓这个）。抛物线（跨度 ≈ 2 倍）不受影响，仍走完整范围。
     */
    private const val OUTLIER_FACTOR = 4.0

    /** 曲线配色：与应用的学科色系同族，四条以内足够区分。 */
    private val COLORS = listOf("#1B4ED8", "#C62828", "#2E7D32", "#6A1B9A")

    private const val BG = "#FFFFFF"
    private const val PLOT_BG = "#FCFCFD"
    private const val GRID = "#E7EAF0"
    private const val AXIS = "#37474F"
    private const val LABEL = "#546E7A"
    private const val TITLE_COLOR = "#263238"

    /** 一条曲线：表达式 +（可选的）图例名。 */
    data class Curve(val expression: String, val label: String = "")

    /** 一次绘图请求。xMin/xMax/yMin/yMax 为 null 表示自动。 */
    data class Request(
        val curves: List<Curve>,
        val xMin: Double? = null,
        val xMax: Double? = null,
        val yMin: Double? = null,
        val yMax: Double? = null,
        val title: String? = null
    )

    /** 绘图结果：成功带 SVG 与最终取用的范围，失败带人话原因。 */
    sealed interface Result {
        data class Ok(
            val svg: String,
            val xMin: Double,
            val xMax: Double,
            val yMin: Double,
            val yMax: Double
        ) : Result

        data class Failed(val message: String) : Result
    }

    fun plot(request: Request): Result {
        val curves = request.curves.take(MAX_CURVES)
        if (curves.isEmpty()) return Result.Failed("没有给出要画的函数表达式")

        // 解析全部表达式：一条写错就整张图不画，并把它指名道姓地说出来
        // （模型据此改正重试，用户也能一眼看出是哪个式子有问题）。
        val compiled = curves.map { curve ->
            try {
                curve to ExpressionEvaluator.parse(curve.expression)
            } catch (error: IllegalArgumentException) {
                return Result.Failed("表达式「${curve.expression}」看不懂：${error.message}")
            }
        }

        val xMin = request.xMin ?: -10.0
        val xMax = request.xMax ?: 10.0
        if (xMax <= xMin) {
            return Result.Failed("x 的范围不对：x_min（$xMin）必须小于 x_max（$xMax）")
        }

        // 采样：一条曲线一份序列，NaN/Inf 原样保留（断线判定要用）。
        val sampled: List<List<Double>> = compiled.map { (_, evaluator) ->
            (0 until SAMPLES).map { i ->
                val x = xMin + (xMax - xMin) * i / (SAMPLES - 1.0)
                evaluator.eval(x)
            }
        }

        val finiteAll = sampled.flatten().filter { it.isFinite() }
        if (finiteAll.isEmpty()) {
            return Result.Failed(
                "在 x ∈ [$xMin, $xMax] 上这些表达式都没有取值，换个区间再画"
            )
        }

        val yRange = resolveYRange(
            finite = finiteAll,
            requestedMin = request.yMin,
            requestedMax = request.yMax
        ) ?: return Result.Failed(
            "y 的范围不对：y_min（${request.yMin}）必须小于 y_max（${request.yMax}）"
        )

        val svg = render(
            curves = compiled.map { it.first },
            sampled = sampled,
            xMin = xMin,
            xMax = xMax,
            yMin = yRange.first,
            yMax = yRange.second,
            title = request.title?.trim()?.takeIf { it.isNotEmpty() }
        )
        return Result.Ok(svg, xMin, xMax, yRange.first, yRange.second)
    }

    /**
     * y 轴窗口：用户给了就用用户的；否则自动。
     *
     * 自动取值分两种情况（这是「函数图像好不好看」的关键）：
     * - **正常函数**（抛物线、指数、正弦）：取全部采样点的最小/最大值，
     *   曲线完整落在框内，不被截掉；
     * - **有极点的函数**（`1/x`、`tan`）：极值可以到 1e16，直接取 min/max
     *   会把整张图压成一条水平线。判定标准是「极值跨度远大于中间 90%
     *   样本的跨度」，此时改用 5%~95% 分位，让双曲线/正切在框内看起来正常。
     */
    private fun resolveYRange(
        finite: List<Double>,
        requestedMin: Double?,
        requestedMax: Double?
    ): Pair<Double, Double>? {
        if (requestedMin != null && requestedMax != null) {
            return if (requestedMax > requestedMin) requestedMin to requestedMax else null
        }
        val sorted = finite.sorted()
        val full = sorted.first() to sorted.last()
        val coreLo = sorted[(sorted.size * 0.05).toInt().coerceIn(0, sorted.size - 1)]
        val coreHi = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]
        val useCore = (full.second - full.first) > OUTLIER_FACTOR * (coreHi - coreLo) &&
            coreHi > coreLo

        var lo = if (useCore) coreLo else full.first
        var hi = if (useCore) coreHi else full.second
        requestedMin?.let { lo = it }
        requestedMax?.let { hi = it }
        if (hi <= lo) {
            // 常数函数（y=3）：给一个以它为中心的窗口，否则「高度 0」没法画
            val center = lo
            return center - 1.0 to center + 1.0
        }
        val pad = (hi - lo) * 0.1
        return lo - pad to hi + pad
    }

    private fun render(
        curves: List<Curve>,
        sampled: List<List<Double>>,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        title: String?
    ): String {
        val marginTop = if (title != null) MARGIN_TOP_TITLED else MARGIN_TOP
        val plotLeft = MARGIN_LEFT.toDouble()
        val plotRight = (WIDTH - MARGIN_RIGHT).toDouble()
        val plotTop = marginTop.toDouble()
        val plotBottom = (HEIGHT - MARGIN_BOTTOM).toDouble()
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        fun sx(x: Double): Double = plotLeft + (x - xMin) / (xMax - xMin) * plotW
        fun sy(y: Double): Double = plotBottom - (y - yMin) / (yMax - yMin) * plotH

        val sb = StringBuilder(16 * 1024)
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(WIDTH)
            .append("\" height=\"").append(HEIGHT)
            .append("\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(HEIGHT)
            .append("\" font-family=\"sans-serif\">\n")
        sb.append("<rect x=\"0\" y=\"0\" width=\"").append(WIDTH).append("\" height=\"")
            .append(HEIGHT).append("\" fill=\"").append(BG).append("\"/>\n")
        sb.append("<rect x=\"").append(num(plotLeft)).append("\" y=\"").append(num(plotTop))
            .append("\" width=\"").append(num(plotW)).append("\" height=\"").append(num(plotH))
            .append("\" fill=\"").append(PLOT_BG).append("\" stroke=\"").append(GRID)
            .append("\"/>\n")

        // ---- 刻度与网格 ----
        val xStep = niceStep(xMax - xMin, target = 8)
        val yStep = niceStep(yMax - yMin, target = 6)
        val xTicks = ticks(xMin, xMax, xStep)
        val yTicks = ticks(yMin, yMax, yStep)

        xTicks.forEach { x ->
            val px = sx(x)
            sb.append("<line x1=\"").append(num(px)).append("\" y1=\"").append(num(plotTop))
                .append("\" x2=\"").append(num(px)).append("\" y2=\"").append(num(plotBottom))
                .append("\" stroke=\"").append(GRID).append("\" stroke-width=\"1\"/>\n")
        }
        yTicks.forEach { y ->
            val py = sy(y)
            sb.append("<line x1=\"").append(num(plotLeft)).append("\" y1=\"").append(num(py))
                .append("\" x2=\"").append(num(plotRight)).append("\" y2=\"").append(num(py))
                .append("\" stroke=\"").append(GRID).append("\" stroke-width=\"1\"/>\n")
        }

        // ---- 坐标轴：0 在窗口内就画在 0 处，否则贴边（保证始终有轴可读） ----
        val axisY = if (yMin <= 0.0 && yMax >= 0.0) sy(0.0) else plotBottom
        val axisX = if (xMin <= 0.0 && xMax >= 0.0) sx(0.0) else plotLeft
        sb.append("<line x1=\"").append(num(plotLeft)).append("\" y1=\"").append(num(axisY))
            .append("\" x2=\"").append(num(plotRight)).append("\" y2=\"").append(num(axisY))
            .append("\" stroke=\"").append(AXIS).append("\" stroke-width=\"1.6\"/>\n")
        sb.append("<line x1=\"").append(num(axisX)).append("\" y1=\"").append(num(plotTop))
            .append("\" x2=\"").append(num(axisX)).append("\" y2=\"").append(num(plotBottom))
            .append("\" stroke=\"").append(AXIS).append("\" stroke-width=\"1.6\"/>\n")

        // ---- 刻度数字 ----
        xTicks.forEach { x ->
            if (abs(x) < 1e-9 && xMin <= 0.0 && xMax >= 0.0) return@forEach
            sb.append("<text x=\"").append(num(sx(x))).append("\" y=\"").append(num(axisY + 18))
                .append("\" font-size=\"13\" fill=\"").append(LABEL)
                .append("\" text-anchor=\"middle\">").append(esc(number(x)))
                .append("</text>\n")
        }
        yTicks.forEach { y ->
            if (abs(y) < 1e-9 && yMin <= 0.0 && yMax >= 0.0) return@forEach
            sb.append("<text x=\"").append(num(axisX - 8)).append("\" y=\"").append(num(sy(y) + 4))
                .append("\" font-size=\"13\" fill=\"").append(LABEL)
                .append("\" text-anchor=\"end\">").append(esc(number(y)))
                .append("</text>\n")
        }
        // 原点一个 0 就够，别写两遍
        if (xMin <= 0.0 && xMax >= 0.0 && yMin <= 0.0 && yMax >= 0.0) {
            sb.append("<text x=\"").append(num(axisX - 8)).append("\" y=\"").append(num(axisY + 18))
                .append("\" font-size=\"13\" fill=\"").append(LABEL)
                .append("\" text-anchor=\"end\">0</text>\n")
        }
        // 轴名
        sb.append("<text x=\"").append(num(plotRight - 4)).append("\" y=\"").append(num(axisY - 8))
            .append("\" font-size=\"14\" font-style=\"italic\" fill=\"").append(AXIS)
            .append("\" text-anchor=\"end\">x</text>\n")
        sb.append("<text x=\"").append(num(axisX + 10)).append("\" y=\"").append(num(plotTop + 14))
            .append("\" font-size=\"14\" font-style=\"italic\" fill=\"").append(AXIS)
            .append("\">y</text>\n")

        // ---- 曲线 ----
        sampled.forEachIndexed { index, values ->
            val path = curvePath(values, xMin, xMax, yMin, yMax, ::sx, ::sy)
            if (path.isNotEmpty()) {
                sb.append("<path d=\"").append(path)
                    .append("\" fill=\"none\" stroke=\"").append(COLORS[index % COLORS.size])
                    .append("\" stroke-width=\"2.4\" stroke-linecap=\"round\"")
                    .append(" stroke-linejoin=\"round\"/>\n")
            }
        }

        // ---- 图例：多于一条曲线、或有自定义名字时才需要 ----
        val named = curves.filter { it.label.isNotBlank() }
        if (curves.size > 1 || named.isNotEmpty()) {
            curves.forEachIndexed { index, curve ->
                val label = curve.label.ifBlank { curve.expression }
                val rowY = plotTop + 18 + index * 21
                sb.append("<line x1=\"").append(num(plotLeft + 12)).append("\" y1=\"")
                    .append(num(rowY)).append("\" x2=\"").append(num(plotLeft + 34))
                    .append("\" y2=\"").append(num(rowY)).append("\" stroke=\"")
                    .append(COLORS[index % COLORS.size])
                    .append("\" stroke-width=\"2.8\" stroke-linecap=\"round\"/>\n")
                sb.append("<text x=\"").append(num(plotLeft + 42)).append("\" y=\"")
                    .append(num(rowY + 5))
                    .append("\" font-size=\"13\" fill=\"").append(TITLE_COLOR)
                    .append("\">").append(esc(label)).append("</text>\n")
            }
        }

        if (title != null) {
            sb.append("<text x=\"").append(WIDTH / 2).append("\" y=\"28\" font-size=\"17\"")
                .append(" font-weight=\"bold\" fill=\"").append(TITLE_COLOR)
                .append("\" text-anchor=\"middle\">").append(esc(title)).append("</text>\n")
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * 采样点 → SVG 路径。
     *
     * 断线三种情况，缺一不可：
     * 1. 非有限值（`ln(-1)`、`1/0`）——这一点没有定义；
     * 2. 相邻两点跳变超过窗口高度的 [POLE_JUMP_FACTOR] 倍——`tan(x)` 跨极点，
     *    连起来就是一条假竖直渐近线；
     * 3. 超出窗口的点先**夹到边框**（曲线看起来顶到框沿），此时若紧接着
     *    跳到另一侧，同样按 2 断开。
     */
    private fun curvePath(
        values: List<Double>,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        sx: (Double) -> Double,
        sy: (Double) -> Double
    ): String {
        val sb = StringBuilder(values.size * 8)
        val span = yMax - yMin
        var started = false
        var lastDrawnY = Double.NaN
        var prevValue = Double.NaN

        values.forEachIndexed { index, value ->
            val x = xMin + (xMax - xMin) * index / (values.size - 1.0)
            val finite = value.isFinite()
            val jump = finite && prevValue.isFinite() &&
                (
                    abs(value - prevValue) > span * POLE_JUMP_FACTOR ||
                        // 两侧都远在窗口之外、符号还相反：中间必然跨过一个极点
                        // （`1/x` 在 0 两侧、`tan(x)` 在 π/2 两侧都是这种形态）。
                        // 只看跳变幅度不够——跳变会被夹到框内，看起来"不远"。
                        (value * prevValue < 0.0 &&
                            abs(value) > span && abs(prevValue) > span)
                    )
            if (!finite || jump) {
                started = false
                prevValue = value
                return@forEachIndexed
            }
            prevValue = value
            val clamped = value.coerceIn(yMin, yMax)
            val px = sx(x)
            val py = sy(clamped)
            // 亚像素级重复点丢掉：路径能小一半，视觉上无差别
            if (started && abs(py - lastDrawnY) < 0.4 && index != values.lastIndex) return@forEachIndexed
            sb.append(if (started) " L" else " M")
                .append(num(px)).append(' ').append(num(py))
            started = true
            lastDrawnY = py
        }
        return sb.toString().trim()
    }

    /** 落在 [min, max] 内的刻度值（含端点附近的整步长点）。 */
    private fun ticks(min: Double, max: Double, step: Double): List<Double> {
        if (step <= 0 || !step.isFinite()) return emptyList()
        val first = ceil(min / step - 1e-9) * step
        val out = ArrayList<Double>()
        var v = first
        var guard = 0
        while (v <= max + step * 1e-6 && guard < 64) {
            out += v
            v += step
            guard++
        }
        return out
    }

    /** 1 / 2 / 2.5 / 5 × 10^k 里挑一个让刻度数接近 target 的步长。 */
    private fun niceStep(span: Double, target: Int): Double {
        if (!span.isFinite() || span <= 0) return 1.0
        val raw = span / target
        val magnitude = 10.0.pow(floor(log10(raw)))
        val normalized = raw / magnitude
        val multiplier = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 2.5 -> 2.5
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return multiplier * magnitude
    }

    /** 刻度数字：去掉浮点尾巴（0.30000000000000004 → 0.3），大数走科学计数。 */
    private fun number(value: Double): String = when {
        value == 0.0 -> "0"
        abs(value) >= 1e5 || abs(value) < 1e-4 -> String.format(Locale.US, "%.3g", value)
        else -> String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
    }

    /** 坐标保留一位小数：省一半体积，视觉上分不出来。 */
    private fun num(value: Double): String = String.format(Locale.US, "%.1f", value)

    /** XML 转义：表达式里可能出现 `<`、`&`（如 `x<2` 的图例），不转就是坏 XML。 */
    private fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
