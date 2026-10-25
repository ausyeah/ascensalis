package com.mistakebook.wordbook.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打卡海报：**纯本地 Canvas 渲染**，不需要任何生图 API。
 *
 * ## 为什么不用 Compose Canvas
 *
 * 海报是**离屏导出**（1080×1620 的 Bitmap，不进屏幕），Compose 的
 * Canvas 依赖真实的 density / layout 节点才好用；导出场景要的就是
 * 一块 Bitmap，直接拿 android.graphics.Canvas 画最直接，
 * 也不引入任何新依赖（任务书：Canvas + Bitmap 现有能力足够）。
 *
 * ## 配色为什么不跟主题
 *
 * 海报是要发出去给人看的（微信 / 小红书）。跟主题走的话，
 * 深色模式下会画出一张黑底海报——在别人的浅色聊天流里是一块突兀的
 * 黑方块。所以固定用纸色底 + PaperPrimary 靛蓝，任何主题下观感一致。
 */

// [CheckInPosterData] 与 [buildPosterData] 在同包的 CheckInPosterData.kt：
// 那边没有 Android 依赖，海报上的数字可以 JVM 单测。

private const val POSTER_WIDTH = 1080
private const val POSTER_HEIGHT = 1620

// 固定浅色纸感配色（不跟主题，见文件头注释）。
// 用 Color.parseColor 而不是 0xFFxxxxxx 字面量：后者超过 Int 上界时
// Kotlin 推断成 Long，再 toInt() 回来是个负数，读代码的人得在脑子里
// 补一次补码转换才能确认颜色，纯属自找麻烦。
private val BG_COLOR = Color.parseColor("#F6F4EE")       // PaperBgLight
private val PRIMARY_COLOR = Color.parseColor("#3B5BDB")  // PaperPrimary
private val INK_COLOR = Color.parseColor("#1F2430")
private val MUTED_COLOR = Color.parseColor("#6B7280")
private val LINE_COLOR = Color.parseColor("#DAD5C9")
private val OK_COLOR = Color.parseColor("#087F5B")       // PaperOk
private val BAND_TEXT_COLOR = Color.parseColor("#CCFFFFFF")

/** 渲染一张 1080×1620 的打卡海报。 */
fun renderCheckInPoster(data: CheckInPosterData): Bitmap {
    val bitmap = Bitmap.createBitmap(POSTER_WIDTH, POSTER_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(BG_COLOR)

    // 顶部主色带：海报的第一眼，把「Day N」托住。
    canvas.drawRect(0f, 0f, POSTER_WIDTH.toFloat(), 260f, fillPaint(PRIMARY_COLOR))

    val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    // ---- 顶部：日期 + 背单词 Day N ----
    canvas.drawTextCentered(
        text = data.dateText,
        cx = POSTER_WIDTH / 2f,
        y = 96f,
        paint = textPaint(36f, BAND_TEXT_COLOR, sans)
    )
    canvas.drawTextCentered(
        text = "背单词 Day ${data.streakDays}",
        cx = POSTER_WIDTH / 2f,
        y = 190f,
        paint = textPaint(76f, Color.WHITE, bold)
    )

    // ---- 中间：今日三项数据（大字号 + 分隔线）----
    val midTop = 380f
    canvas.drawTextCentered(
        text = "今日学习",
        cx = POSTER_WIDTH / 2f,
        y = midTop,
        paint = textPaint(34f, MUTED_COLOR, sans)
    )

    val rowH = 150f
    drawStatRow(canvas, "学过", "${data.studiedToday}", "词", midTop + rowH, bold, sans, INK_COLOR)
    drawDivider(canvas, midTop + rowH + 44f)
    drawStatRow(canvas, "正确率", data.accuracyText(), "", midTop + rowH * 2, bold, sans, OK_COLOR)
    drawDivider(canvas, midTop + rowH * 2 + 44f)
    drawStatRow(canvas, "目标", "${data.dailyGoal}", "词", midTop + rowH * 3, bold, sans, INK_COLOR)

    // ---- 底部：累计掌握 + App 名 + 装饰线条 ----
    val bottomTop = POSTER_HEIGHT - 300f
    drawDivider(canvas, bottomTop - 40f)
    canvas.drawTextCentered(
        text = "累计掌握 ${data.masteredCount} 词",
        cx = POSTER_WIDTH / 2f,
        y = bottomTop + 60f,
        paint = textPaint(44f, INK_COLOR, bold)
    )
    // 淡色装饰线：左右对称的两道，给底部一点「卡片」的收口感，
    // 又不抢中间数据的视觉重心。
    canvas.drawLine(
        160f, bottomTop + 130f, POSTER_WIDTH - 160f, bottomTop + 130f,
        strokePaint(4f, LINE_COLOR)
    )
    canvas.drawTextCentered(
        text = "登临 · 错题本",
        cx = POSTER_WIDTH / 2f,
        y = bottomTop + 210f,
        paint = textPaint(34f, MUTED_COLOR, sans)
    )
    return bitmap
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
    val cx = POSTER_WIDTH / 2f
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
    canvas.drawLine(140f, y, POSTER_WIDTH - 140f, y, strokePaint(2f, LINE_COLOR))
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

/**
 * 把海报存进系统相册 `Pictures/登临/`，返回 content Uri。
 *
 * 用 MediaStore 而不是 app 私有目录：海报就是要能被微信/相册读取的，
 * 私有目录发出去的 File URI 在 Android 7+ 会被目标 App 拒绝。
 */
suspend fun savePosterToGallery(context: Context, bitmap: Bitmap, displayName: String): android.net.Uri? =
    withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Pictures/登临：与系统相册其它目录并列，用户找得到。
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/登临")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null
        resolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        } ?: return@withContext null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }

/** 调起系统分享面板（ACTION_SEND image/png）。 */
fun sharePoster(context: Context, uri: android.net.Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享打卡海报"))
}
