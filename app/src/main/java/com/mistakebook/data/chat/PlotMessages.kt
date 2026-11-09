package com.mistakebook.data.chat

import com.mistakebook.math.FunctionPlotter

/**
 * 手动函数画图（v0.0.144）时，替这次「一问一答」准备两条消息的正文。
 *
 * 放在 data 层而不是 UI 层：正文是要**写进数据库**的（进历史、能被导出、
 * 将来可能被模型读），它属于内容而不属于界面——单元测试能直接钉住，
 * 不必渲染界面就知道写出来的是什么。
 */
object PlotMessages {

    /** 用户那一句：写清楚画的是什么，以后翻会话时一眼看懂。 */
    fun userText(request: FunctionPlotter.Request): String {
        val expressions = request.curves.joinToString("、") { it.expression }
        return "画一下 $expressions 的函数图像（x ∈ [${short(request.xMin ?: -10.0)}, " +
            "${short(request.xMax ?: 10.0)}]）"
    }

    /**
     * 助手那一条：图 + 取用范围 + 一句「这是算出来的」。
     *
     * 说明范围是有必要的：图上 y 轴上下界是按取值自动定的（极点函数会改用
     * 分位数），用户得知道这张图看的是哪一段。
     */
    fun assistantContent(
        request: FunctionPlotter.Request,
        plotted: FunctionPlotter.Result.Ok
    ): String {
        val expressions = request.curves.joinToString("、") { it.expression }
        val range = "x ∈ [${short(plotted.xMin)}, ${short(plotted.xMax)}]，" +
            "y ∈ [${short(plotted.yMin)}, ${short(plotted.yMax)}]"
        return "这是 $expressions 的函数图像（$range），由本机精确采样算出：\n\n" +
            PlotTools.fence(plotted.svg) + "\n\n" +
            "坐标轴、刻度与曲线都是算出来的（渐近线处会自动断开）。" +
            "想换范围或再画一条，点左上角的菜单进「函数画图」。"
    }

    /** 数字的短写法：整数不带小数点，小数最多两位（会话里要人读）。 */
    private fun short(value: Double): String = if (value == Math.floor(value) &&
        Math.abs(value) < 1e6
    ) {
        value.toLong().toString()
    } else {
        java.lang.String.format(java.util.Locale.US, "%.2f", value)
            .trimEnd('0')
            .trimEnd('.')
    }
}
