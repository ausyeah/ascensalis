package com.mistakebook.math

import java.util.Locale
import kotlin.math.abs

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
 *
 * 采样、断线、刻度与 y 窗口这些**与画布共用**的算法都在 [FunctionSampler]
 * 里（v0.0.144 抽出去的）：函数计算器页会实时画同一组表达式，画布上的线
 * 与这里的 SVG 必须是同一套断线口径，否则同一条函数会出现两张不一样的图。
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
            FunctionSampler.sample(evaluator, xMin, xMax)
        }

        val finiteAll = sampled.flatten().filter { it.isFinite() }
        if (finiteAll.isEmpty()) {
            return Result.Failed(
                "在 x ∈ [$xMin, $xMax] 上这些表达式都没有取值，换个区间再画"
            )
        }

        val yRange = FunctionSampler.resolveYRange(
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
        val xStep = FunctionSampler.niceStep(xMax - xMin, target = 8)
        val yStep = FunctionSampler.niceStep(yMax - yMin, target = 6)
        val xTicks = FunctionSampler.ticks(xMin, xMax, xStep)
        val yTicks = FunctionSampler.ticks(yMin, yMax, yStep)

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
                .append("\" text-anchor=\"middle\">").append(esc(FunctionSampler.formatTick(x)))
                .append("</text>\n")
        }
        yTicks.forEach { y ->
            if (abs(y) < 1e-9 && yMin <= 0.0 && yMax >= 0.0) return@forEach
            sb.append("<text x=\"").append(num(axisX - 8)).append("\" y=\"").append(num(sy(y) + 4))
                .append("\" font-size=\"13\" fill=\"").append(LABEL)
                .append("\" text-anchor=\"end\">").append(esc(FunctionSampler.formatTick(y)))
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
     * 断线与像素级去重的判据都在 [FunctionSampler.segments]（函数计算器页的
     * 画布用的是同一份，两边的图必须断在同一个地方）；这里只负责把折线段
     * 拼成 SVG 的 `d`。段与段之间**不连**：一整条路径里出现两个 `M`，
     * 渲染器就不会把它们连起来。
     */
    private fun curvePath(
        values: List<Double>,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        sx: (Double) -> Double,
        sy: (Double) -> Double
    ): String = FunctionSampler.segments(
        values = values,
        xMin = xMin,
        xMax = xMax,
        yMin = yMin,
        yMax = yMax,
        mapX = sx,
        mapY = sy
    ).joinToString(" ") { segment ->
        segment.withIndex().joinToString(" ") { (index, point) ->
            // 段首用 M（另起一笔，两段之间就不会被连起来），段内用 L
            (if (index == 0) "M " else "L ") + num(point.x) + " " + num(point.y)
        }
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
