package com.mistakebook.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.util.Log
import java.io.File

/**
 * 图片归一化：把「来源各异的原始照片」统一成「方向正确、适合 OCR 的平铺 JPEG」。
 *
 * ## 为什么必须有这一步（两起真实事故）
 *
 * **1. 莫名旋转。** 原先 [ImageImporter] 对 JPEG/PNG 直接裸字节拷贝，
 * EXIF 方向标记原封不动留在文件里。而 `BitmapFactory` 不认 EXIF——
 * 于是同一个文件：有的路径（比如 WebView、某些图片库）会按 EXIF 转正，
 * 有的路径（裁剪页、缩略图）不会，用户看到的就是「我明明调正了，它又歪了」。
 * 根治办法只有一个：**把方向烘进像素，EXIF 不再携带方向**。
 *
 * **2. 照片发灰、铅笔字看不清。** 手机在室内光下拍的作业照对比度低，
 * 直接丢给 MinerU 会大量误识别。这里做灰度化 + 自适应对比度拉伸。
 *
 * ## 为什么保留彩色像素
 * 老师用红笔批注是错题本里信息量最大的部分。全转黑白会把红笔批注一起抹掉。
 * 所以策略是：**近灰像素**（纸面、铅笔、黑字）走强对比拉伸；
 * **彩色像素**（红笔、蓝笔）保留色相，只做温和的亮度对比增强。
 */
object ImageNormalizer {

    /** 归一化后的长边上限。再大对 OCR 没有增益，只会拖慢处理和占内存。 */
    private const val MAX_LONG_EDGE = 2400

    /** JPEG 质量。90 以上对文字没有可见收益，体积却线性上涨。 */
    private const val JPEG_QUALITY = 92

    /** 饱和度低于此值视为「近灰」，走强对比通道。 */
    private const val GRAY_SATURATION = 0.18f

    /** 超过此饱和度视为「彩色笔迹」，走保色通道。 */
    private const val COLOR_SATURATION = 0.30f

    /** [steepen] 的中点。映射围绕这里双向展开。 */
    private const val MID = 128f

    /**
     * 阈值以下的过渡跨度占阈值的比例。
     *
     * **已不再使用** —— 早期版本用它决定暗部映射区间的分母，结果把
     * 阈值下方一段输入钳成同一个值。保留名字是为了让旧注释里的推理可追溯。
     */
    private const val DARK_SPAN = 0.45f

    /**
     * 把 [source] 归一化后写入 [target]，返回是否成功。
     *
     * @param strength OCR 对比度增强档位（0=关闭 1=轻度 2=标准 3=强力）。
     */
    fun normalize(source: File, target: File, strength: Int): Boolean {
        if (!source.exists()) return false
        return runCatching {
            val orientation = readOrientation(source)
            val decoded = decodeBounded(source) ?: return false
            // 先把方向烘进像素，后面所有环节（裁剪、缩略图、打印、识别）都不再需要知道 EXIF
            val upright = applyOrientation(decoded, orientation)
            val final = enhanceForOcr(upright, strength)
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                final.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (final !== upright) final.recycle()
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
            target.length() > 0
        }.getOrDefault(false)
    }

    /**
     * 读取 EXIF 方向，返回 [ExifInterface.ORIENTATION_*]。
     * 读不到（PNG / 无 EXIF / 损坏）返回 NORMAL。
     */
    fun readOrientation(file: File): Int = runCatching {
        file.inputStream().use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * 边长受限解码。
     * 直接 `decodeFile` 一张 4000×3000 的照片就是 48MB，
     * 连续处理多张必被 OOM——必须先算 inSampleSize。
     */
    internal fun calculateSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val targetEdge = maxLongEdge.coerceAtLeast(1)
        val sampledEdgeLimit = targetEdge.toDouble() * 1.5
        val longEdge = maxOf(width, height).toLong()
        var sample = 1
        while (longEdge / sample > sampledEdgeLimit) sample *= 2
        return sample
    }

    fun decodeBounded(file: File, maxLongEdge: Int = MAX_LONG_EDGE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val targetEdge = maxLongEdge.coerceAtLeast(1)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= targetEdge) return bitmap
        val ratio = targetEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** 按 EXIF 方向把位图转正，返回新位图（不需要旋转时原样返回）。 */
    fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /**
     * OCR 友好的对比度增强。
     *
     * 分两路：
     * - 近灰像素：走「自适应阈值 + 陡坡」曲线，纸面压到接近纯白、字迹压到接近纯黑；
     * - 彩色像素：保留色相，只按同一套曲线提亮/压暗，避免红笔批注被抹成灰。
     *
     * 阈值不是固定的 128，而是按整图直方图取 Otsu 值——
     * 偏灰的照片和偏亮的照片需要不同的分界，固定阈值会把浅铅笔字吃掉。
     */
    fun enhanceForOcr(bitmap: Bitmap, strength: Int): Bitmap {
        if (strength <= 0) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val threshold = otsuThreshold(pixels)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val saturation = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC

            val target = steepen(luma(r, g, b), threshold, strength)
            pixels[i] = if (saturation >= COLOR_SATURATION) {
                // 彩色笔迹：按比例缩放 RGB，保住色相
                val scale = if (maxC == 0) 0f else target / maxC.toFloat()
                val nr = (r * scale).toInt().coerceIn(0, 255)
                val ng = (g * scale).toInt().coerceIn(0, 255)
                val nb = (b * scale).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            } else {
                // 近灰像素（含铅笔字、纸面）：直接映射到增强后的灰阶
                val v = if (saturation >= GRAY_SATURATION) {
                    // 轻微带色但不算彩色：折中处理，仍保一点色相
                    val scale = if (maxC == 0) 0f else target / maxC.toFloat()
                    ((r * scale).toInt().coerceIn(0, 255) shl 16) or
                        ((g * scale).toInt().coerceIn(0, 255) shl 8) or
                        (b * scale).toInt().coerceIn(0, 255)
                } else {
                    (0xFF shl 24) or (target shl 16) or (target shl 8) or target
                }
                v
            }
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun luma(r: Int, g: Int, b: Int): Int =
        (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)

    /**
     * 以 [threshold] 为界做陡坡映射。
     *
     * ## 为什么不能是无脑直线
     *
     * 原来是 `(value - threshold) * 255 / spread + 128`，斜率高达 10~21 倍。
     * 后果是**阈值以下只有 12~24 级灰度可用，而这段全被压到同一个值**：
     * 暗部纸张（luma 60~90）正好落在这段里，用户看到的就是「没光线的地方一片死黑」，
     * 而抗锯齿的浅灰边缘也被切碎，铅笔字的层次全丢了。
     *
     * ## 现在的做法：分段，且暗部留底
     *
     * - 阈值以上：`S-curve` 推到 255，斜率比原来温和，保住边缘过渡
     * - 阈值以下：**不归零**，而是映射到 `[DARK_FLOOR, 128]`，
     *   暗部仍有层次，只是被压暗——这是用户要的「提亮暗部」而不是「删掉暗部」
     *
     * 参数上，[DARK_SPAN] 取阈值的 0.45 而不是 0.12：
     * 0.12 意味着阈值下 12 级灰度就归零，而 0.45 给了将近一半的暗部范围。
     *
     * 新增档位说明：
     * - 轻度 (1): 斜率 1.6，暗部底线 48。适合本身光线好、只是想稍作灰度化的照片。
     * - 标准 (2): 斜率 4.0，暗部底线 12。原版参数：压掉大部分纸张杂色，保留主要笔迹细节。
     * - 强力 (3): 斜率 8.0，暗部底线 0。极端去底色，暗部直接归零（会丢失部分灰度层次），用于极暗图片或追求高反差。
     */
    internal fun steepen(value: Int, threshold: Int, strength: Int): Int {
        val v = value.toFloat()
        val t = threshold.toFloat()

        val upSlope: Float
        val darkFloor: Float
        when (strength) {
            1 -> { upSlope = 1.6f; darkFloor = 48f }
            3 -> { upSlope = 8f; darkFloor = 0f }
            else -> { upSlope = 4f; darkFloor = 12f } // 标准档(2) 或非法值回退
        }

        // 阈值以上：向 255 收敛。跨度 0.25 倍阈值 → 斜率 4 左右，
        // 但**不封顶**：封顶会让 240 停在 229，推不到白，纸面不够干净。
        // 跨度已经保证了斜率不会失控，不需要第二道保险。
        if (v >= t) {
            val upSpan = ((255f - t) / upSlope).coerceAtLeast(1f)
            val scaled = (v - t) * (255f - MID) / upSpan + MID
            return scaled.toInt().coerceIn(0, 255)
        }

        // 阈值以下：把整个 [0, threshold] 区间线性拉到 [darkFloor, MID]。
        //
        // 关键是区间上界就是 **threshold 本身**。
        // 中间试过 `v / (threshold * 0.45)`：那么输入 threshold*0.45 ~ threshold
        // 这一整段（暗部纸张恰恰在这里）全都落在分母之外，被 coerceIn 钳成同一个值，
        // 「一片死黑」只是变成了「一片死灰」——参数换了，症状没变。
        //
        // 线性拉到整个区间的好处：输入 0~threshold 的每一级灰度都分到独立的输出值，
        // 暗部既提亮了（不再是 0）又保住了层次。
        val span = t.coerceAtLeast(1f)
        val scaled = (v / span) * (MID - darkFloor) + darkFloor
        return scaled.toInt().coerceIn(0, MID.toInt())
    }

    /**
     * Otsu 法求灰度直方图的最大类间方差阈值。
     *
     * ## 为什么要有两道 sanity（T15）
     * 用户报告过小概率「增强后黑白颠倒」。[steepen] 的映射对固定阈值是单调的，
     * 单次处理数学上不可能反转输出；怀疑根因就在这里：极端直方图
     * （图上有一小块强反光 / 深阴影 / 黑边，占比小但极端）会把 Otsu 阈值拉偏，
     * 整页被推向全白或全暗，被感知为「颠倒」。按防御性修复处理，不追求复现：
     * - **分位钳制**：阈值必须落在灰度直方图的 [P2, P98] 分位之间，越界钳回边界；
     * - **占比守卫**：阈值两侧任何一侧占比 < 5% 都回退到
     *   `(加权中位数 + 128) / 2`——两簇严重失衡时 Otsu 的分界没有统计意义。
     *
     * 回退时留 `Log.w`：下次用户再遇到，凭这条日志就能定位是不是这条路径。
     *
     * internal 而非 private 是为了让单测能直接构造极端直方图验证守卫
     * （与 [steepen]、[calculateSampleSize] 同一约定）。
     *
     * [onFallback] 可注入是为了 JVM 单测：Paparazzi 带来的 layoutlib jar 里
     * `android.util.Log` 是真身（native 调用），且排在 mockable android.jar
     * 之前，单测里打 Log 会 UnsatisfiedLinkError。生产走默认值 = 真 Log.w。
     */
    internal fun otsuThreshold(
        pixels: IntArray,
        onFallback: (String) -> Unit = { msg -> Log.w("ImageNormalizer", msg) }
    ): Int {
        val histogram = IntArray(256)
        pixels.forEach { pixel ->
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 16) return@forEach
            histogram[luma((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)]++
        }
        val total = histogram.sum()
        if (total == 0) return 128

        var sum = 0L
        histogram.forEachIndexed { value, count -> sum += value.toLong() * count }

        var sumBackground = 0L
        var weightBackground = 0
        var best = 0
        var bestVariance = -1.0
        for (threshold in 0..255) {
            weightBackground += histogram[threshold]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break

            sumBackground += threshold.toLong() * histogram[threshold]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val variance = weightBackground.toDouble() * weightForeground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (variance > bestVariance) {
                bestVariance = variance
                best = threshold
            }
        }

        // 第一道：分位钳制。阈值必须落在 [P2, P98] 之间
        // （累积分布单调，P2 必然 <= P98，coerceIn 不会因 min > max 抛异常）
        var threshold = best.coerceIn(
            quantileOfHistogram(histogram, total, 0.02),
            quantileOfHistogram(histogram, total, 0.98)
        )

        // 第二道：占比守卫。亮侧（>=t）或暗侧（<t）任何一侧不足 5%，
        // 说明两簇严重失衡，Otsu 分界没有统计意义，回退到中位数与 128 的中点。
        // 乘 20 与 total 比较是为了全程整数运算（占比 < 5% <=> 权重*20 < total）。
        var darkWeight = 0
        var brightWeight = 0
        for (value in 0..255) {
            if (value < threshold) darkWeight += histogram[value] else brightWeight += histogram[value]
        }
        if (darkWeight * 20 < total || brightWeight * 20 < total) {
            val median = quantileOfHistogram(histogram, total, 0.5)
            val fallback = ((median + 128) / 2f).toInt().coerceIn(0, 255)
            onFallback(
                "Otsu 阈值异常已回退: t=$threshold" +
                    " 亮侧占比=${brightWeight * 100 / total}%" +
                    " 暗侧占比=${darkWeight * 100 / total}%" +
                    " -> $fallback"
            )
            threshold = fallback
        }
        return threshold
    }

    /**
     * 灰度直方图的 [ratio] 分位值（按像素数加权）。
     * 累积计数首次达到 `ratio * total` 的灰度值即为该分位。
     */
    private fun quantileOfHistogram(histogram: IntArray, total: Int, ratio: Double): Int {
        if (total <= 0) return 128
        val target = ratio * total
        var cumulative = 0
        for (value in 0..255) {
            cumulative += histogram[value]
            if (cumulative >= target) return value
        }
        return 255
    }

    /** 纯色覆盖绘制，供调用方复用（避免各处重复 new Canvas）。 */
    fun fill(bitmap: Bitmap, color: Int) {
        Canvas(bitmap).drawPaint(Paint().apply { this.color = color })
    }

    /** 灰度化（调试/对比用）。 */
    fun toGrayscale(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        canvas.drawBitmap(
            bitmap, 0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
        )
        return out
    }
}
