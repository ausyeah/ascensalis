package com.mistakebook.math

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * 函数图像的采样、断线与刻度（v0.0.144）。
 *
 * ## 为什么要单独一个文件
 *
 * 同一组表达式现在有**两个出口**：
 * - 存进会话的 SVG（[FunctionPlotter]），用户事后还要回看；
 * - 函数计算器页上那块能拖动缩放的画布（Compose 画布实时重绘，
 *   不用 SVG 字符串）。
 *
 * 两条路的画法不同（一个拼字符串、一个画 Path），但**「哪些点连得起来、
 * 哪些点必须断开」必须完全一致**。否则同一组式子会出现两张不一样的图：
 * 画布上 `1/x` 的两支被一条假竖线连起来、会话里的图却是断的，
 * 用户没法判断哪个是真的。所以采样密度、断线判据、刻度步长、y 窗口的
 * 自动定法全部收在这里，两边都从这里取。
 *
 * ## 断线而不是连线
 *
 * `ln(x)` 在 x<0 没有定义、`1/x` 在 0 处是无穷、`tan(x)` 有极点——这些点上
 * 采样值是非有限值或跨窗口的跳变。**必须断线**：连起来就会出现一条假的
 * 竖直渐近线，学生会把它当成函数的一部分。
 *
 * ## 纯函数
 *
 * 不碰 Android、不碰文件、不碰网络，输入输出都是数据，JVM 单测直接覆盖
 * （`FunctionSamplerTest`）。
 */
object FunctionSampler {

    /** 采样点数：太密图重且每帧都在算，太疏曲线发折。480 在手机宽度下足够顺滑。 */
    const val SAMPLES = 480

    /** 相邻采样跳变超过「窗口高度 × 这个倍数」就判定为极点，断开路径。 */
    const val POLE_JUMP_FACTOR = 6.0

    /** 屏幕上亚像素级的重复点直接丢掉：路径能小一半，视觉上无差别。 */
    const val DUPLICATE_PIXEL_DELTA = 0.4

    /**
     * 自动 y 窗口时，极值跨度超过「中间 90% 样本跨度」的多少倍就改用分位数。
     *
     * 4 倍这条线是实测调出来的：`1/x` 在 x∈[-2,2] 上的极值跨度是中间样本的
     * 6 倍左右，用 8 倍会漏判 → 窗口被拉到 ±120 → 极点两侧的跳变反而"不显眼"，
     * 于是左右两支被一条假竖线连起来（FunctionPlotterTest 的
     * `极点处断线而不是连一条假的竖线` 就是抓这个）。抛物线（跨度 ≈ 2 倍）
     * 不受影响，仍走完整范围。
     */
    const val OUTLIER_FACTOR = 4.0

    /** 画布上的一个点（已映射到像素）。用 Double：SVG 与画布都从这里取值。 */
    data class Pt(val x: Double, val y: Double)

    /** 第 [index] 个采样点对应的 x：采样点在整个窗口上均分。 */
    fun xAt(index: Int, count: Int, xMin: Double, xMax: Double): Double =
        xMin + (xMax - xMin) * index / (count - 1).coerceAtLeast(1).toDouble()

    /**
     * 在 [xMin, xMax] 上均分采样。
     *
     * NaN/Inf **原样保留**：它们正是断线判定的输入（见 [segments]），
     * 在这里滤掉就等于把断点信息丢了。
     */
    fun sample(
        evaluator: ExpressionEvaluator,
        xMin: Double,
        xMax: Double,
        count: Int = SAMPLES
    ): List<Double> = (0 until count).map { index ->
        evaluator.eval(xAt(index, count, xMin, xMax))
    }

    /**
     * 一条曲线的取值序列 → 若干条**可直接连线**的折线段（坐标已映射到画布）。
     *
     * [mapX]/[mapY] 是数据坐标到像素坐标的映射，由调用方按各自的画布给出
     * （SVG 的绘图区与页面的画布尺寸当然不同）；断线判据与像素级去重
     * 共用同一份，两边的图才长得一样。
     *
     * 断线三种情况，缺一不可：
     * 1. 非有限值（`ln(-1)`、`1/0`）——这一点没有定义；
     * 2. 相邻两点跳变超过窗口高度的 [POLE_JUMP_FACTOR] 倍——`tan(x)` 跨极点，
     *    连起来就是一条假竖直渐近线；
     * 3. 超出窗口的点先**夹到边框**（曲线看起来顶到框沿），此时若紧接着
     *    跳到另一侧，同样按 2 断开。
     */
    fun segments(
        values: List<Double>,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        mapX: (Double) -> Double,
        mapY: (Double) -> Double
    ): List<List<Pt>> {
        if (values.size < 2) return emptyList()
        val span = yMax - yMin
        val out = ArrayList<List<Pt>>()
        var current = ArrayList<Pt>()
        var lastDrawnY = Double.NaN
        var prevValue = Double.NaN

        values.forEachIndexed { index, value ->
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
                if (current.isNotEmpty()) {
                    out += current
                    current = ArrayList()
                }
                // 断点也要计入「上一个值」：否则下一点会拿断点之前的点来判跳变，
                // 在极点附近多断一次（两条相邻的短段看起来就是虚线）。
                prevValue = value
                return@forEachIndexed
            }
            prevValue = value
            val py = mapY(value.coerceIn(yMin, yMax))
            // 最后一点永远画：否则曲线右端会因为「和上一点太近」被削掉半像素
            if (current.isNotEmpty() && abs(py - lastDrawnY) < DUPLICATE_PIXEL_DELTA &&
                index != values.lastIndex
            ) {
                return@forEachIndexed
            }
            current.add(Pt(mapX(xAt(index, values.size, xMin, xMax)), py))
            lastDrawnY = py
        }
        if (current.isNotEmpty()) out += current
        return out
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
     *
     * 返回 null 表示给的窗口本身是反的（yMin ≥ yMax），调用方要报错。
     */
    fun resolveYRange(
        finite: List<Double>,
        requestedMin: Double?,
        requestedMax: Double?
    ): Pair<Double, Double>? {
        if (finite.isEmpty()) return null
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

    /** 落在 [min, max] 内的刻度值（含端点附近的整步长点）。 */
    fun ticks(min: Double, max: Double, step: Double): List<Double> {
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

    /** 1 / 2 / 2.5 / 5 × 10^k 里挑一个让刻度数接近 [target] 的步长。 */
    fun niceStep(span: Double, target: Int): Double {
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
    fun formatTick(value: Double): String = when {
        value == 0.0 -> "0"
        abs(value) >= 1e5 || abs(value) < 1e-4 -> String.format(Locale.US, "%.3g", value)
        else -> String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
    }
}
