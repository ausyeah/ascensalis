package com.mistakebook.poster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * 海报渲染：**纯本地 Canvas**，不需要任何生图 API。
 *
 * ## 为什么不用 Compose Canvas
 *
 * 海报是**离屏导出**（1080×1620 的 Bitmap，不进屏幕），Compose 的
 * Canvas 依赖真实的 density / layout 节点才好用；导出场景要的就是
 * 一块 Bitmap，直接拿 android.graphics.Canvas 画最直接，
 * 也不引入任何新依赖。
 *
 * ## 配色为什么不跟主题
 *
 * 海报是要发出去给人看的（微信 / 小红书）。跟主题走的话，
 * 深色模式下会画出一张黑底海报——在别人的浅色聊天流里是一块突兀的
 * 黑方块。所以固定用纸色底 + 靛蓝主色，任何主题下观感一致。
 *
 * ## 背景图模式（v0.0.49）
 *
 * [background] 非空时：先把背景图**等比铺满**画上去，再叠一层半透明
 * 纸色蒙版（保证文字可读），最后画文字。文字仍然是本地画的——
 * 中文不会像生图模型那样写错。
 */
object PosterRenderer {

    const val WIDTH = 1080
    const val HEIGHT = 1620

    // 固定浅色纸感配色（不跟主题，见文件头注释）。
    private val BG_COLOR = Color.parseColor("#F6F4EE")
    private val PRIMARY_COLOR = Color.parseColor("#3B5BDB")
    private val INK_COLOR = Color.parseColor("#1F2430")
    private val MUTED_COLOR = Color.parseColor("#6B7280")
    private val LINE_COLOR = Color.parseColor("#DAD5C9")
    private val OK_COLOR = Color.parseColor("#087F5B")
    private val BAND_TEXT_COLOR = Color.parseColor("#CCFFFFFF")

    /**
     * 渲染一张 1080×1620 海报。
     *
     * @param background 背景图（生图模型出的）。为空时用纯纸色底。
     */
    fun render(content: PosterContent, background: Bitmap? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_COLOR)

        if (background != null) {
            drawBackground(canvas, background)
            // 半透明蒙版：背景图再好看，文字也得读得清。
            canvas.drawColor(Color.argb(150, 246, 244, 238))
        }

        val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

        // ---- 顶部主色带：海报的第一眼，把主标题托住 ----
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 260f, fillPaint(PRIMARY_COLOR))

        canvas.drawTextCentered(
            text = content.dateText,
            cx = WIDTH / 2f,
            y = 96f,
            paint = textPaint(36f, BAND_TEXT_COLOR, sans)
        )
        canvas.drawTextCentered(
            text = content.headline,
            cx = WIDTH / 2f,
            y = 190f,
            paint = textPaint(76f, Color.WHITE, bold)
        )

        // ---- 中间：统计行（大字号 + 分隔线）----
        val midTop = 380f
        canvas.drawTextCentered(
            text = content.statsHeader,
            cx = WIDTH / 2f,
            y = midTop,
            paint = textPaint(34f, MUTED_COLOR, sans)
        )

        val rowH = 150f
        content.stats.take(MAX_STAT_ROWS).forEachIndexed { index, stat ->
            val baseline = midTop + rowH * (index + 1)
            drawStatRow(
                canvas = canvas,
                label = stat.label,
                value = stat.value,
                unit = stat.unit,
                baselineY = baseline,
                bold = bold,
                sans = sans,
                valueColor = if (stat.emphasis) OK_COLOR else INK_COLOR
            )
            if (index < content.stats.take(MAX_STAT_ROWS).lastIndex) {
                drawDivider(canvas, baseline + 44f)
            }
        }

        // ---- 底部：页脚 + 装饰线条 ----
        val bottomTop = HEIGHT - 300f
        drawDivider(canvas, bottomTop - 40f)
        canvas.drawTextCentered(
            text = content.footerText,
            cx = WIDTH / 2f,
            y = bottomTop + 210f,
            paint = textPaint(34f, MUTED_COLOR, sans)
        )
        // 淡色装饰线：左右对称的两道，给底部一点「卡片」的收口感，
        // 又不抢中间数据的视觉重心。
        canvas.drawLine(
            160f, bottomTop + 130f, WIDTH - 160f, bottomTop + 130f,
            strokePaint(4f, LINE_COLOR)
        )
        return bitmap
    }

    /** 背景图等比**铺满**（center-crop），不留黑边也不拉伸变形。 */
    private fun drawBackground(canvas: Canvas, background: Bitmap) {
        val scale = maxOf(
            WIDTH.toFloat() / background.width,
            HEIGHT.toFloat() / background.height
        )
        val scaledW = background.width * scale
        val scaledH = background.height * scale
        val left = (WIDTH - scaledW) / 2f
        val top = (HEIGHT - scaledH) / 2f
        canvas.drawBitmap(
            background,
            Rect(0, 0, background.width, background.height),
            Rect(left.toInt(), top.toInt(), (left + scaledW).toInt(), (top + scaledH).toInt()),
            fillPaint(Color.WHITE)
        )
    }

    /** 一行「标签 + 数值 + 单位」：标签与单位小字，数值大字。 */
    private fun drawStatRow(
        canvas: Canvas,
        label: String,
        value: String,
        unit: String,
        baselineY: Float,
        bold: Typeface,
        sans: Typeface,
        valueColor: Int
    ) {
        val cx = WIDTH / 2f
        val labelPaint = textPaint(32f, MUTED_COLOR, sans)
        val valuePaint = textPaint(64f, valueColor, bold)
        val unitPaint = textPaint(32f, MUTED_COLOR, sans)
        val labelW = labelPaint.measureText(label)
        val valueW = valuePaint.measureText(value)
        val unitW = if (unit.isBlank()) 0f else unitPaint.measureText(unit)
        val total = labelW + 24f + valueW + if (unit.isBlank()) 0f else 12f + unitW
        var x = cx - total / 2f
        canvas.drawText(label, x, baselineY, labelPaint)
        x += labelW + 24f
        canvas.drawText(value, x, baselineY, valuePaint)
        if (unit.isNotBlank()) {
            x += valueW + 12f
            canvas.drawText(unit, x, baselineY, unitPaint)
        }
    }

    private fun drawDivider(canvas: Canvas, y: Float) {
        canvas.drawLine(140f, y, WIDTH - 140f, y, strokePaint(2f, LINE_COLOR))
    }

    private fun fillPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    private fun strokePaint(width: Float, color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            this.color = color
        }

    private fun textPaint(size: Float, color: Int, typeface: Typeface): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            this.typeface = typeface
        }

    /** 水平居中绘制（Compose Canvas 有 center 概念，native Canvas 只能自己算宽度）。 */
    private fun Canvas.drawTextCentered(text: String, cx: Float, y: Float, paint: Paint) {
        drawText(text, cx - paint.measureText(text) / 2f, y, paint)
    }

    /** 最多画三行统计（版式就按三行设计）。 */
    private const val MAX_STAT_ROWS = 3
}
