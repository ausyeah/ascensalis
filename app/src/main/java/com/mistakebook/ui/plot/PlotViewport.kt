package com.mistakebook.ui.plot

/**
 * 函数计算器页的视口（v0.0.144）。
 *
 * ## 为什么单独抽出来
 *
 * 拖动、缩放、复位这三件事看着是「手势」，其实是**纯几何**：把像素位移
 * 换算成数据范围、以某个锚点为中心缩放。抽成不依赖 Compose 的数据类之后
 * 能在 JVM 上直接钉住（`PlotViewportTest`），不然「双指缩放时中心漂了」
 * 这种问题只能靠手感发现。
 *
 * ## 为什么 x/y 用同一个缩放因子
 *
 * 数学上「y=x 是 45 度直线」「单位圆是个圆」都依赖横纵轴**等比例**。
 * 归一化到「一个数据单位 = 同样多的像素」之后，缩放只需要按同一个倍数
 * 收放 x 和 y 两个跨度，等比例就自然保持住了。
 */
data class PlotViewport(
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double
) {

    val xSpan: Double get() = xMax - xMin
    val ySpan: Double get() = yMax - yMin

    /**
     * 平移。
     *
     * 手指（或捏合中心）往右拖，画面跟着往右走 —— 也就是**窗口往左移**，
     * 所以 x 是减；屏幕 y 向下，手指往下拖时窗口要往上移，所以 y 是加。
     */
    fun pan(dxPx: Float, dyPx: Float, widthPx: Float, heightPx: Float): PlotViewport {
        if (widthPx <= 0f || heightPx <= 0f) return this
        val dx = dxPx / widthPx * xSpan
        val dy = dyPx / heightPx * ySpan
        return PlotViewport(xMin - dx, xMax - dx, yMin + dy, yMax + dy)
    }

    /**
     * 以屏幕上的 ([focusXPx], [focusYPx]) 为锚点缩放，[factor] > 1 是放大。
     *
     * 锚点下的那个数据点保持不动——这是双指缩放手感的关键：锚点漂移会让
     * 用户觉得「越缩越往一边跑」。
     */
    fun zoom(
        factor: Float,
        focusXPx: Float,
        focusYPx: Float,
        widthPx: Float,
        heightPx: Float
    ): PlotViewport {
        if (widthPx <= 0f || heightPx <= 0f) return this
        val f = factor.toDouble()
        if (!f.isFinite() || f <= 0.0) return this
        // 先按 x 把缩放倍数夹到可用的跨度区间，再把**同一个倍数**用到 y 上：
        // 分别夹的话两个轴的跨度比例会在边界附近被改掉，等比例就没了。
        val effective = xSpan / (xSpan / f).coerceIn(MIN_SPAN, MAX_SPAN)
        val k = 1.0 / effective
        val focusX = xMin + focusXPx / widthPx * xSpan
        // 屏幕 y 向下，数据 y 向上：锚点要从上边界往下量
        val focusY = yMax - focusYPx / heightPx * ySpan
        return PlotViewport(
            xMin = focusX - (focusX - xMin) * k,
            xMax = focusX + (xMax - focusX) * k,
            yMin = focusY - (focusY - yMin) * k,
            yMax = focusY + (yMax - focusY) * k
        )
    }

    /** 数据 x → 画布像素 x（画布即绘图区，四周不留边）。 */
    fun xToPx(x: Double, widthPx: Float): Double = (x - xMin) / xSpan * widthPx

    /** 数据 y → 画布像素 y（屏幕 y 向下，所以是减）。 */
    fun yToPx(y: Double, heightPx: Float): Double = heightPx - (y - yMin) / ySpan * heightPx

    companion object {

        /** 复位时 x 轴的半宽：-10..10，与工具路径的默认区间同一个口径。 */
        const val DEFAULT_X_HALF_SPAN = 10.0

        /** 缩放下限/上限（数据跨度）：再小就只剩一个点，再大刻度也数不清。 */
        const val MIN_SPAN = 1e-3
        const val MAX_SPAN = 1e6

        /**
         * 复位视口：x ∈ [-10, 10]，y 按画布纵横比**等比例**铺开。
         *
         * y 不是也写死 ±10：手机上的画布不是正方形，照抄会让单位圆变椭圆。
         * 画布尺寸还没量出来（宽或高为 0）时退回正方形，等量到再复位一次。
         */
        fun default(widthPx: Float, heightPx: Float): PlotViewport {
            val half = DEFAULT_X_HALF_SPAN
            if (widthPx <= 0f || heightPx <= 0f) {
                return PlotViewport(-half, half, -half, half)
            }
            val perPixel = (2 * half) / widthPx
            val yHalf = perPixel * heightPx / 2.0
            return PlotViewport(-half, half, -yHalf, yHalf)
        }
    }
}
