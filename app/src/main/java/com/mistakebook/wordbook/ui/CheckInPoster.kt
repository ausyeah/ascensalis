package com.mistakebook.wordbook.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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


// 固定浅色纸感配色（不跟主题，见文件头注释）。
// 用 Color.parseColor 而不是 0xFFxxxxxx 字面量：后者超过 Int 上界时
// Kotlin 推断成 Long，再 toInt() 回来是个负数，读代码的人得在脑子里
// 补一次补码转换才能确认颜色，纯属自找麻烦。

/**
 * 渲染一张 1080×1620 的打卡海报。
 *
 * v0.0.49 起统一走 [com.mistakebook.poster.PosterRenderer]：
 * 本地渲染、生图背景、整图生图三种模式共用同一套版式代码，
 * 免得「词库打卡」和「番茄打卡」两套画法越走越偏。
 */
fun renderCheckInPoster(
    data: CheckInPosterData,
    // 画布渲染没有 Composable 作用域：小标题与品牌行由调用方从资源解析后传入。
    statsHeader: String,
    footerText: String
): Bitmap =
    com.mistakebook.poster.PosterRenderer.render(
        com.mistakebook.poster.PosterContent(
            dateText = data.dateText,
            headline = "背单词 Day ${data.streakDays}",
            stats = listOf(
                com.mistakebook.poster.PosterStat("学过", "${data.studiedToday}", "词"),
                com.mistakebook.poster.PosterStat("正确率", data.accuracyText(), emphasis = true),
                com.mistakebook.poster.PosterStat("目标", "${data.dailyGoal}", "词")
            ),
            statsHeader = statsHeader,
            footerText = footerText
        )
    )

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
