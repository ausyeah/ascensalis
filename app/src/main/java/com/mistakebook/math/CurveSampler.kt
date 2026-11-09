package com.mistakebook.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 三种曲线的采样：显函数、极坐标、隐函数方程。
 *
 * 输出统一是「像素折线」——[FunctionSampler.Pt] 由调用方给的 [mapX]/[mapY]
 * 映射过，因此聊天里的 SVG 与计算器的画布**共用同一套采样与断线规则**，
 * 不会出现「画布里断线、导出的图连起来」这种分裂。断线判据见
 * [FunctionSampler.segments]（显函数）。
 *
 * ## 隐函数为什么用 Marching Squares
 *
 * `x^2+y^2=1` 解不出 y 的显式表达式（一个 x 对应两个 y）。常规做法是在窗口
 * 上打网格、求 F(x,y)=0 的等值线：每个网格单元看四个角的 F 值符号，符号变化
 * 的边上线性插值出一个点，两个点连成一小段，再把小段按端点串成折线。
 * 160×160 的网格对手机屏幕足够了，代价是每条曲线约 2.6 万次求值——纯算术，
 * 十几毫秒。
 */
object CurveSampler {

    /** 极坐标的 θ 采样数：720 步在 2π 上约 0.5°，玫瑰线也不会出棱角。 */
    private const val POLAR_SAMPLES = 720

    /** 隐函数的网格边长（单元数）。 */
    private const val IMPLICIT_CELLS = 160

    /**
     * 一条曲线 → 像素折线集合。
     *
     * [thetaMax] 是极坐标的 θ 上界（显式/隐式忽略）；调用方把「x 轴范围」
     * 当 θ 范围传进来就能画多圈玫瑰线。
     */
    fun segments(
        curve: PlotCurve,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        mapX: (Double) -> Double,
        mapY: (Double) -> Double,
        thetaMin: Double = 0.0,
        thetaMax: Double = 2 * Math.PI,
        samples: Int = FunctionSampler.SAMPLES
    ): List<List<FunctionSampler.Pt>> = when (curve) {
        is PlotCurve.Explicit -> FunctionSampler.segments(
            values = FunctionSampler.sample(
                evaluator = ExpressionEvaluator.parse(curve.expression),
                xMin = xMin,
                xMax = xMax,
                count = samples
            ),
            xMin = xMin,
            xMax = xMax,
            yMin = yMin,
            yMax = yMax,
            mapX = mapX,
            mapY = mapY
        )

        is PlotCurve.Polar -> polarSegments(
            expression = curve.expression,
            thetaMin = thetaMin,
            thetaMax = thetaMax,
            mapX = mapX,
            mapY = mapY
        )

        is PlotCurve.Implicit -> implicitSegments(
            left = curve.left,
            right = curve.right,
            xMin = xMin,
            xMax = xMax,
            yMin = yMin,
            yMax = yMax,
            mapX = mapX,
            mapY = mapY
        )
    }

    /**
     * 自动定 y 轴窗口时参考的 y 取值。
     *
     * 隐函数返回空：它的形状由整张窗口决定，没有「一条 y=f(x) 的取值序列」
     * 可参考——调用方对隐函数改用按 x 轴比例推出的对称窗口。
     */
    fun yValues(
        curve: PlotCurve,
        xMin: Double,
        xMax: Double,
        thetaMax: Double = 2 * Math.PI,
        samples: Int = FunctionSampler.SAMPLES
    ): List<Double> = when (curve) {
        is PlotCurve.Explicit -> FunctionSampler.sample(
            ExpressionEvaluator.parse(curve.expression), xMin, xMax, samples
        )

        is PlotCurve.Polar -> {
            val evaluator = ExpressionEvaluator.parse(curve.expression)
            (0 until POLAR_SAMPLES).mapNotNull { index ->
                val theta = thetaMax * index / (POLAR_SAMPLES - 1.0)
                val r = evaluator.eval(theta = theta)
                val y = r * sin(theta)
                if (y.isFinite()) y else null
            }
        }

        is PlotCurve.Implicit -> emptyList()
    }

    /** 极坐标：逐 θ 采样，点 = (r·cosθ, r·sinθ)。negative r 照数学画在反方向。 */
    private fun polarSegments(
        expression: String,
        thetaMin: Double,
        thetaMax: Double,
        mapX: (Double) -> Double,
        mapY: (Double) -> Double
    ): List<List<FunctionSampler.Pt>> {
        if (thetaMax <= thetaMin) return emptyList()
        val evaluator = ExpressionEvaluator.parse(expression)
        val out = ArrayList<List<FunctionSampler.Pt>>()
        var current = ArrayList<FunctionSampler.Pt>()

        var prevX = Double.NaN
        var prevY = Double.NaN
        for (index in 0 until POLAR_SAMPLES) {
            val theta = thetaMin + (thetaMax - thetaMin) * index / (POLAR_SAMPLES - 1.0)
            val r = evaluator.eval(theta = theta)
            val x = r * cos(theta)
            val y = r * sin(theta)
            val finite = r.isFinite() && x.isFinite() && y.isFinite()
            // 极坐标的断线：非有限值，或相邻两点跳得比整张窗口还远（r 在极点炸了）
            val jump = finite && prevX.isFinite() && prevY.isFinite() &&
                hypot(x - prevX, y - prevY) > 4.0 * (hypot(x, y) + 1.0)
            if (!finite || jump) {
                if (current.isNotEmpty()) {
                    out += current
                    current = ArrayList()
                }
                prevX = x
                prevY = y
                continue
            }
            prevX = x
            prevY = y
            current.add(FunctionSampler.Pt(mapX(x), mapY(y)))
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /**
     * 隐函数：Marching Squares 求 F(x,y)=0 的等值线。
     *
     * 每个网格单元收集边上符号变化的交点（0/2/4 个）；两个就成一段，四个
     * （鞍点）按「相邻边配对」近似处理。小段再按端点串成折线——不然一条
     * 单位圆会变成上千条两点的线段，SVG 与画布都要多画几十倍。
     */
    private fun implicitSegments(
        left: String,
        right: String,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        mapX: (Double) -> Double,
        mapY: (Double) -> Double
    ): List<List<FunctionSampler.Pt>> {
        if (xMax <= xMin || yMax <= yMin) return emptyList()
        val l = ExpressionEvaluator.parse(left)
        val r = ExpressionEvaluator.parse(right)
        fun f(x: Double, y: Double): Double = l.eval(x, y) - r.eval(x, y)

        val nx = IMPLICIT_CELLS
        val ny = IMPLICIT_CELLS
        val dx = (xMax - xMin) / nx
        val dy = (yMax - yMin) / ny
        // 顶点值：先算一遍，单元复用，避免 4 倍重复求值
        val values = Array(ny + 1) { row ->
            DoubleArray(nx + 1) { col -> f(xMin + col * dx, yMin + row * dy) }
        }

        val segments = ArrayList<DoubleArray>()
        for (row in 0 until ny) {
            for (col in 0 until nx) {
                val x0 = xMin + col * dx
                val y0 = yMin + row * dy
                val v00 = values[row][col]
                val v10 = values[row][col + 1]
                val v01 = values[row + 1][col]
                val v11 = values[row + 1][col + 1]
                val crossings = ArrayList<DoubleArray>(4)

                fun crossing(va: Double, vb: Double, ax: Double, ay: Double, bx: Double, by: Double) {
                    if (!va.isFinite() || !vb.isFinite()) return
                    val aPositive = va > 0
                    if (aPositive == (vb > 0)) return
                    val t = va / (va - vb)
                    crossings.add(doubleArrayOf(ax + (bx - ax) * t, ay + (by - ay) * t))
                }

                // 四条边：下、右、上、左
                crossing(v00, v10, x0, y0, x0 + dx, y0)
                crossing(v10, v11, x0 + dx, y0, x0 + dx, y0 + dy)
                crossing(v01, v11, x0, y0 + dy, x0 + dx, y0 + dy)
                crossing(v00, v01, x0, y0, x0, y0 + dy)

                when (crossings.size) {
                    2 -> segments.add(
                        doubleArrayOf(
                            crossings[0][0], crossings[0][1],
                            crossings[1][0], crossings[1][1]
                        )
                    )
                    // 鞍点：四个交点两两配对（相邻边相连），近似足够
                    4 -> {
                        segments.add(
                            doubleArrayOf(
                                crossings[0][0], crossings[0][1],
                                crossings[1][0], crossings[1][1]
                            )
                        )
                        segments.add(
                            doubleArrayOf(
                                crossings[2][0], crossings[2][1],
                                crossings[3][0], crossings[3][1]
                            )
                        )
                    }
                }
            }
        }
        return chain(segments, mapX, mapY)
    }

    /** 把小段按端点串成折线（端点量化到千分之一，噪声不会把整条线切断）。 */
    private fun chain(
        segments: List<DoubleArray>,
        mapX: (Double) -> Double,
        mapY: (Double) -> Double
    ): List<List<FunctionSampler.Pt>> {
        if (segments.isEmpty()) return emptyList()
        val remaining = segments.toMutableList()
        val out = ArrayList<List<FunctionSampler.Pt>>()

        fun key(x: Double, y: Double): Long =
            (x * 1000).roundToInt().toLong() * 1_000_003L + (y * 1000).roundToInt().toLong()

        while (remaining.isNotEmpty()) {
            val chain = ArrayList<DoubleArray>()
            var current = remaining.removeAt(remaining.size - 1)
            chain.add(current)
            var grew = true
            while (grew) {
                grew = false
                // 每接上一段就**重新扫一遍**：边遍历边删会让索引整体前移、
                // 跳过相邻的候选段，一圈圆就被拆成好几段（实测拆成 8 段）。
                var index = remaining.size - 1
                while (index >= 0) {
                    val candidate = remaining[index]
                    val joinsTail = key(candidate[0], candidate[1]) == key(current[2], current[3])
                    val joinsHead = key(candidate[2], candidate[3]) == key(current[2], current[3])
                    if (joinsTail || joinsHead) {
                        current = if (joinsTail) candidate else
                            doubleArrayOf(candidate[2], candidate[3], candidate[0], candidate[1])
                        chain.add(current)
                        remaining.removeAt(index)
                        grew = true
                        break
                    }
                    index--
                }
            }
            val points = ArrayList<FunctionSampler.Pt>(chain.size + 1)
            points.add(FunctionSampler.Pt(mapX(chain[0][0]), mapY(chain[0][1])))
            chain.forEach { segment ->
                points.add(FunctionSampler.Pt(mapX(segment[2]), mapY(segment[3])))
            }
            out.add(points)
        }
        return out
    }

    /** 隐函数曲线在窗口里到底有没有东西（一条都没有时调用方该报「没画出来」）。 */
    fun hasContent(curve: PlotCurve, xMin: Double, xMax: Double, yMin: Double, yMax: Double): Boolean =
        if (curve is PlotCurve.Implicit) {
            segments(
                curve = curve,
                xMin = xMin, xMax = xMax, yMin = yMin, yMax = yMax,
                mapX = { it }, mapY = { it }
            ).isNotEmpty()
        } else {
            true
        }

    /** 供测试与调用方用：窗口对角线长度（跳变判定用）。 */
    internal fun diagonal(xMin: Double, xMax: Double, yMin: Double, yMax: Double): Double =
        abs(hypot(xMax - xMin, yMax - yMin))
}
