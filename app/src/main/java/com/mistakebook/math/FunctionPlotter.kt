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
 *
 * ## 三种曲线（v0.0.147）
 *
 * 用户不再只能写 `y=f(x)`：极坐标 `r=1+cos(θ)` 与隐式方程
 * `x^2+y^2=1` 都能画。归类交给 [PlotCurve.parse]，三种曲线的采样统一走
 * [CurveSampler.segments]，这样 SVG 与计算器画布继续共用同一套折线。
 */
object FunctionPlotter {

    /** 一次最多画几条曲线（再多图就看不清了，工具描述里也这么写）。 */
    const val MAX_CURVES = 4

    /** 画布尺寸（px）。宽度按手机聊天区缩放显示，纵横比接近 16:10。 */
    private const val WIDTH = 720
    private const val HEIGHT = 440

    /** 没给 x 范围时的默认窗口（与计算器复位视口、工具描述同一个口径）。 */
    private const val DEFAULT_X_MIN = -10.0
    private const val DEFAULT_X_MAX = 10.0

    private const val TWO_PI = 2.0 * Math.PI

    /**
     * 隐函数自动定 y 窗口时用的画布纵横比：绘图区约 638×338（720×440 减去
     * 标题与刻度边距），338 / 638 ≈ 0.53。
     *
     * 为什么是**取舍**：显函数有一条 y 取值序列可以看分布，隐函数没有——
     * `x^2+y^2=1` 只给出「哪些点满足方程」，给不出「y 大致多大」。所以
     * 按 x 轴的跨度乘这个比例铺一个以 0 为中心、上下对称的窗口。代价是
     * 曲线在窗口里偏小时得自己给 y 范围；好处是不会像 ±10 那样把单位圆
     * 压成一条细线，也不会因为某个极点把窗口拉到无穷。
     */
    private const val IMPLICIT_ASPECT = 338.0 / 638.0

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

    /**
     * 一次绘图请求。xMin/xMax/yMin/yMax 为 null 表示自动。
     *
     * 画极坐标时 xMin/xMax 表示 **θ 的上下界**（默认 0..2π）：没有单独的 θ
     * 字段，「调范围」对极坐标来说就是「θ 画多长」。
     */
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

        // 解析并归类全部表达式：一条写错就整张图不画，并把它指名道姓地说出来
        // （模型据此改正重试，用户也能一眼看出是哪个式子有问题）。
        // PlotCurve.parse 的报错已经带了人话原因，这里只补上是**哪一条**原文。
        val parsed = curves.map { curve ->
            try {
                PlotCurve.parse(curve.expression)
            } catch (error: IllegalArgumentException) {
                return Result.Failed("表达式「${curve.expression}」画不了：${error.message}")
            }
        }

        val xMin = request.xMin ?: DEFAULT_X_MIN
        val xMax = request.xMax ?: DEFAULT_X_MAX
        if (xMax <= xMin) {
            return Result.Failed("x 的范围不对：x_min（$xMin）必须小于 x_max（$xMax）")
        }

        // 极坐标的 θ 范围：**用户给的 x 范围就是 θ 范围**。学生没有单独的
        // θ 输入框，而「θ 从哪到哪」正是他调范围时想说的那句话；默认
        // [0, 2π] 能画完玫瑰线、心形线这类一个周期内闭合的曲线。
        val thetaMin = request.xMin ?: 0.0
        val thetaMax = request.xMax ?: TWO_PI
        val polarOnly = parsed.isNotEmpty() && parsed.all { it is PlotCurve.Polar }

        // x 窗口：只有极坐标时，x 范围已经被拿去当 θ 了，再拿它当窗口会把
        // `r=1+cos(θ)` 挤到窗口右边一小块（心形只落在 x∈[-1,2]）。所以此时
        // 按曲线自己的极值定窗口；混合曲线时仍用用户给的 x 范围（此时它
        // 既当窗口、又当 θ 范围，是有意的双重身份，见类注释）。
        val (winXMin, winXMax) = if (polarOnly) {
            polarXWindow(parsed.filterIsInstance<PlotCurve.Polar>(), thetaMin, thetaMax)
                ?: (xMin to xMax)
        } else {
            xMin to xMax
        }

        val allImplicit = parsed.all { it is PlotCurve.Implicit }
        val yRange = if (allImplicit) {
            implicitYWindow(winXMin, winXMax, request.yMin, request.yMax)
                ?: return Result.Failed(
                    "y 的范围不对：y_min（${request.yMin}）必须小于 y_max（${request.yMax}）"
                )
        } else {
            // 显式/极坐标：把 y 取值序列交给与画布共用的分位数逻辑
            // （极点函数会改用 5%~95% 分位，避免窗口被 1/x 拉到无穷）。
            val finite = parsed
                .flatMap { CurveSampler.yValues(it, winXMin, winXMax, thetaMax) }
                .filter { it.isFinite() }
            if (finite.isEmpty()) {
                val where = if (polarOnly) "θ ∈ [$thetaMin, $thetaMax]" else
                    "x ∈ [$winXMin, $winXMax]"
                return Result.Failed("在 $where 上这些表达式都没有取值，换个区间再画")
            }
            FunctionSampler.resolveYRange(finite, request.yMin, request.yMax)
                ?: return Result.Failed(
                    "y 的范围不对：y_min（${request.yMin}）必须小于 y_max（${request.yMax}）"
                )
        }

        // 隐函数整张窗口里一个交点都没有（例如 x^2+y^2+1=0）时，画出来是一张
        // 只有坐标轴的空图——不如直说「没有解」，用户才知道要改范围或改式子。
        val implicitEmpty = allImplicit && parsed.none { curve ->
            CurveSampler.hasContent(curve, winXMin, winXMax, yRange.first, yRange.second)
        }
        if (implicitEmpty) {
            return Result.Failed(
                "「${curves.first().expression}」在 x ∈ [$winXMin, $winXMax]、" +
                    "y ∈ [${yRange.first}, ${yRange.second}] 上找不到解；" +
                    "隐函数靠网格求等值线，可试试把范围放大或换一种写法"
            )
        }

        val svg = render(
            curves = curves,
            parsed = parsed,
            xMin = winXMin,
            xMax = winXMax,
            yMin = yRange.first,
            yMax = yRange.second,
            thetaMin = thetaMin,
            thetaMax = thetaMax,
            title = request.title?.trim()?.takeIf { it.isNotEmpty() }
        )
        return Result.Ok(svg, winXMin, winXMax, yRange.first, yRange.second)
    }

    /**
     * 只有极坐标时的 x 窗口：取整条曲线在笛卡尔坐标里的横向极值，再留 10% 边。
     *
     * 用 [CurveSampler.segments] 配恒等映射来量，而不是在这里重写一遍
     * `r·cosθ`——曲线的采样口径（多少步、负 r 怎么处理）只有一份。
     */
    private fun polarXWindow(
        polar: List<PlotCurve.Polar>,
        thetaMin: Double,
        thetaMax: Double
    ): Pair<Double, Double>? {
        val xs = polar.flatMap { curve ->
            CurveSampler.segments(
                curve = curve,
                xMin = thetaMin,
                xMax = thetaMax,
                yMin = -1.0,
                yMax = 1.0,
                mapX = { it },
                mapY = { it },
                thetaMin = thetaMin,
                thetaMax = thetaMax
            ).flatten().map { it.x }
        }.filter { it.isFinite() }
        if (xs.isEmpty()) return null
        var lo = xs.min()
        var hi = xs.max()
        if (hi - lo < 1e-9) {
            // 退化成一个点（r=0）：给一个宽 2 的窗口，否则没法画坐标轴
            lo -= 1.0
            hi += 1.0
        }
        val pad = (hi - lo) * 0.1
        return (lo - pad) to (hi + pad)
    }

    /** 隐函数的 y 窗口：按 x 跨度 × 画布纵横比，以 0 为中心上下对称。 */
    private fun implicitYWindow(
        xMin: Double,
        xMax: Double,
        requestedMin: Double?,
        requestedMax: Double?
    ): Pair<Double, Double>? {
        val half = (xMax - xMin) / 2.0 * IMPLICIT_ASPECT
        var lo = -half
        var hi = half
        requestedMin?.let { lo = it }
        requestedMax?.let { hi = it }
        return if (hi > lo) lo to hi else null
    }

    private fun render(
        curves: List<Curve>,
        parsed: List<PlotCurve>,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        thetaMin: Double,
        thetaMax: Double,
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
        // 三种曲线共用 CurveSampler：显函数逐 x 采样、极坐标逐 θ 采样、
        // 隐函数在窗口上求等值线，结果都是「像素折线」，这里只负责拼 SVG。
        // 每条曲线**只出一个 <path>**，多段折线拼成多个 M…L… 子路径：
        // 隐函数的上千个小段若各占一个 <path>，SVG 体积会翻几十倍，聊天记录
        // 和导出文件都会被撑爆（渲染器本来就允许一条路径里出现多个 M）。
        parsed.forEachIndexed { index, curve ->
            val segments = CurveSampler.segments(
                curve = curve,
                xMin = xMin,
                xMax = xMax,
                yMin = yMin,
                yMax = yMax,
                mapX = ::sx,
                mapY = ::sy,
                thetaMin = thetaMin,
                thetaMax = thetaMax
            )
            if (segments.isEmpty()) return@forEachIndexed
            val path = segmentsPath(segments)
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
     * 像素折线 → SVG 路径。
     *
     * 折线与断线的判据都在 [CurveSampler]（函数计算器页的画布用的是同一份，
     * 两边的图必须断在同一个地方）；这里只负责把折线段拼成 SVG 的 `d`。
     * 段与段之间**不连**：一整条路径里出现多个 `M`，渲染器就不会把它们连起来。
     */
    private fun segmentsPath(segments: List<List<FunctionSampler.Pt>>): String =
        segments.joinToString(" ") { segment ->
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
