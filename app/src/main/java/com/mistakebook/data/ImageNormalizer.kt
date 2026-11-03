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
import kotlin.math.roundToInt

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
 * **彩色像素**（红笔、蓝笔）保留色相，只做光照校正不做灰度陡坡
 * （陡坡会把同一种颜色在明暗两处压出两种深浅，见 [mapPixel]）。
 */
object ImageNormalizer {

    /** 归一化后的长边上限。再大对 OCR 没有增益，只会拖慢处理和占内存。 */
    private const val MAX_LONG_EDGE = 2400

    /** JPEG 质量。90 以上对文字没有可见收益，体积却线性上涨。 */
    private const val JPEG_QUALITY = 92

    /** 饱和度低于此值视为「近灰」，走强对比通道。 */
    private const val GRAY_SATURATION = 0.18f

    /** [steepen] 的中点。映射围绕这里双向展开。 */
    private const val MID = 128f

    /**
     * 光照均一化的参数（v0.0.95）。
     *
     * 背景网格长边最多 [BG_GRID_MAX] 格：够细能跟上阴影的渐变，
     * 又粗到每格塞得下几十个笔画——分位统计要有足够样本才有意义。
     */
    private const val BG_GRID_MAX = 32

    /**
     * 每格取多少分位当「这一格的纸面亮度」。
     *
     * 为什么不是最大值：纸上有一小块高光（反光、铜版纸的镜面反射）
     * 就会把整格背景拉到 255，同格的纸面全被除成灰。
     * 为什么不是中位数：中文密排时一格里字迹能占到三成，中位数会落到字上。
     * 0.90 分位对「不超过一成的高光 + 三成的字迹」都稳。
     */
    private const val BG_PERCENTILE = 0.90

    /**
     * 背景亮度下限。低于它的区域一律按它算。
     *
     * 没有这道下限，纯黑区域（桌边、屏幕边框）的背景会被估成个位数，
     * 除以它之后噪点被放大成灰白一片——「去死黑」反而变成「把黑洗成灰」。
     */
    private const val BG_FLOOR = 32

    /** 轻度档只做一半光照校正：那是「稍作灰化」，不是「去除背光」。 */
    private const val BLEND_LIGHT = 0.5f

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
     * 三步，顺序不能换：
     * 1. **光照均一化**（[flattenIllumination]）：把背光/阴影压低的那部分纸面
     *    按局部背景除回白纸水平；
     * 2. **Otsu 求阈值**：在均一化之后的灰度上求，阴影不再把分界拉偏；
     * 3. **分段陡坡**（[steepen]）：纸面推向纯白、字迹压向纯黑。
     *
     * ## 为什么第 1 步必须存在（v0.0.95 用户反馈）
     * 旧实现只有第 2、3 步，是**全局阈值 + 逐像素映射**：
     * 一张桌上拍的作业照，左半边靠窗、右半边在阴影里，两边纸面亮度能差 60 级。
     * 全局阈值只有一个，落在两者之间——靠窗的白纸被推到 255，
     * 阴影里的白纸被推到暗部，整片阴影成了「一片死黑」。
     * 用户说的「非常失调、非常黑的一片片」就是它。
     *
     * 这不是调参能解决的：任何单一阈值都必然把其中一半判错。
     * 必须先把亮度场抹平，让「阴影里的白纸」和「亮处的白纸」变成同一个数量级。
     *
     * 彩色像素仍然保留色相，只按同一套曲线提亮/压暗，避免红笔批注被抹成灰。
     */
    fun enhanceForOcr(bitmap: Bitmap, strength: Int): Bitmap {
        if (strength <= 0) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 灰度单独提出来：背景估计、阈值、逐像素映射都要用。
        // 旧版在 Otsu 和映射里各算一次 luma，等于每像素算两遍。
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            gray[i] = luma((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
        }

        // 就地改写：gray 从此是「均一化后的灰度」，后续都用它。
        // 返回的网格留给彩色像素算亮度缩放——它们不走灰度陡坡，只除光照。
        val blend = illuminationBlend(strength)
        val grid = flattenIllumination(gray, width, height, blend)

        val threshold = otsuThresholdOfGray(gray)

        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                val index = rowBase + x
                val pixel = pixels[index]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val maxC = maxOf(r, g, b)
                val background = grid.sample(x, y)
                pixels[index] = mapPixel(
                    r = r,
                    g = g,
                    b = b,
                    target = steepen(gray[index], threshold, strength),
                    colorScale = if (maxC == 0) {
                        0f
                    } else {
                        flattenExposure(maxC, background, blend) / maxC.toFloat()
                    }
                )
            }
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    /**
     * 单像素映射：彩色走「按比例缩放」保住色相，近灰走灰度陡坡。
     *
     * ## 为什么彩色不再跟灰度共用 [steepen]
     * 陡坡是为「纸 vs 墨」设计的曲线。压到彩色上会把它压成暗红/暗蓝，
     * 更糟的是**同一种颜色在亮处和阴影处会被压出两种深浅**——
     * 阴影里的 luma 低，陡坡给的目标值也低，用户看到的就是「红笔一半深一半浅」。
     * 彩色只要做光照校正（除一次局部背景），亮处阴影处的同一支笔才会一样红。
     *
     * ## 为什么 alpha 必须显式补上
     * v0.0.95 之前，饱和度落在 0.18~0.30 那一档（浅蓝铅笔、淡红批注）
     * 的合成代码漏了最高字节，alpha 恒为 0——那些像素在增强图上直接是**透明洞**。
     * 这里统一走 `0xFF shl 24`，两条分支都不再有机会漏。
     *
     * @param target 近灰像素的目标灰度（[steepen] 的结果，已含光照均一化）
     * @param colorScale 彩色像素的亮度缩放（光照均一化后的 maxC / 原 maxC）
     */
    internal fun mapPixel(
        r: Int,
        g: Int,
        b: Int,
        target: Int,
        colorScale: Float
    ): Int {
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val saturation = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC
        return if (saturation >= GRAY_SATURATION) {
            val nr = (r * colorScale).toInt().coerceIn(0, 255)
            val ng = (g * colorScale).toInt().coerceIn(0, 255)
            val nb = (b * colorScale).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        } else {
            (0xFF shl 24) or (target shl 16) or (target shl 8) or target
        }
    }

    /** 光照校正的强度：轻度只做一半，标准与强力做满。 */
    internal fun illuminationBlend(strength: Int): Float = when (strength) {
        1 -> BLEND_LIGHT
        else -> 1f
    }

    /**
     * 把 [gray] 就地改写为「光照均一化后的灰度」。
     *
     * 做法是**平场校正**：估计每个小块的纸面亮度，再把每个像素除回去。
     * 背光只是乘在纸面上的一个低频系数，除掉它，阴影里的白纸就回到白纸的亮度。
     *
     * @param blend 0 = 原样返回（轻度档只做一半），1 = 完全校正。
     * @return 估计出来的背景网格。彩色像素要拿它算亮度缩放（见 [mapPixel]），
     *   所以不另算一遍——网格的采样是整个增强里最贵的部分之一。
     *
     * 前置条件（由 [enhanceForOcr] 保证）：[width]、[height] 为正，且
     * [gray] 至少 [width]×[height] 个元素。
     */
    internal fun flattenIllumination(
        gray: IntArray,
        width: Int,
        height: Int,
        blend: Float
    ): BackgroundGrid {
        val grid = estimateBackgroundGrid(gray, width, height)
        if (blend <= 0f) return grid
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                val index = rowBase + x
                gray[index] = flattenExposure(gray[index], grid.sample(x, y), blend)
            }
        }
        return grid
    }

    /**
     * 单个像素的平场校正：`value / background * 255`。
     *
     * 为什么除法而不是减一个固定偏移：阴影是**乘法性**的（照度低 → 整片按比例变暗），
     * 减去固定值只会让亮处和暗处的差距原样保留。
     *
     * [BG_FLOOR] 兜住分母：背景估得极低时（纯黑桌边），除法会把噪点放大成灰白一片。
     */
    internal fun flattenExposure(value: Int, background: Int, blend: Float): Int {
        val bg = background.coerceAtLeast(BG_FLOOR)
        val flattened = (value.toLong() * 255 / bg).toInt().coerceIn(0, 255)
        val b = blend.coerceIn(0f, 1f)
        if (b <= 0f) return value
        if (b >= 1f) return flattened
        return (value + (flattened - value) * b).roundToInt().coerceIn(0, 255)
    }

    /**
     * 局部背景网格：把整幅画面压成 [cols]×[rows] 个「纸面亮度」。
     *
     * 独立成类是为了让「估计」与「采样」能分开测：前者关心分位统计对不对，
     * 后者关心双线性取出来的值在格与格之间有没有跳变。
     */
    internal class BackgroundGrid(
        val width: Int,
        val height: Int,
        val cols: Int,
        val rows: Int,
        private val cells: IntArray
    ) {
        /**
         * 双线性取 [x],[y] 处的背景亮度。
         *
         * 不能取最近邻：32×32 的网格铺在 1600px 宽的图上，每格 50px，
         * 最近邻会在阴影的渐变里留下一条条 50px 宽的台阶——那正是要消灭的
         * 「一片片」的另一种形态。
         */
        fun sample(x: Int, y: Int): Int {
            if (cols <= 0 || rows <= 0 || width <= 0 || height <= 0) return BG_FLOOR
            if (cols == 1 && rows == 1) return cells[0]
            // 像素中心映射到网格坐标（-0.5 让格心的采样点落在像素中心）
            val gx = ((x + 0.5f) * cols / width - 0.5f).coerceIn(0f, (cols - 1).toFloat())
            val gy = ((y + 0.5f) * rows / height - 0.5f).coerceIn(0f, (rows - 1).toFloat())
            val x0 = gx.toInt()
            val y0 = gy.toInt()
            val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val y1 = (y0 + 1).coerceAtMost(rows - 1)
            val fx = gx - x0
            val fy = gy - y0
            val top = cells[y0 * cols + x0] * (1 - fx) + cells[y0 * cols + x1] * fx
            val bottom = cells[y1 * cols + x0] * (1 - fx) + cells[y1 * cols + x1] * fx
            return (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * 估计局部纸面亮度（v0.0.95）。
     *
     * 分三步：
     * 1. 每格取 [BG_PERCENTILE] 分位——字迹是少数、纸面是多数，分位落回纸面；
     * 2. 3×3 均值平滑——去掉格子边界上的硬折角，避免「一格一亮」的补丁感；
     * 3. 全图黑区兜底——背景不许低于「全图中位亮度的一半」，也不许低于 [BG_FLOOR]。
     *    没有这道兜底，桌面、屏幕边框这类纯黑区域会把背景估成个位数，
     *    除以它之后整片被提亮成灰白噪点，「去死黑」变成「把黑洗成灰」。
     */
    internal fun estimateBackgroundGrid(gray: IntArray, width: Int, height: Int): BackgroundGrid {
        val cols = width.coerceIn(1, BG_GRID_MAX)
        val rows = height.coerceIn(1, BG_GRID_MAX)
        val cellCount = cols * rows
        val histograms = Array(cellCount) { IntArray(256) }
        val global = IntArray(256)

        for (y in 0 until height) {
            val cellY = (y.toLong() * rows / height).toInt().coerceIn(0, rows - 1)
            val cellRowBase = cellY * cols
            val srcRowBase = y * width
            for (x in 0 until width) {
                val value = gray[srcRowBase + x].coerceIn(0, 255)
                val cellX = (x.toLong() * cols / width).toInt().coerceIn(0, cols - 1)
                histograms[cellRowBase + cellX][value]++
                global[value]++
            }
        }

        val cells = IntArray(cellCount) { index ->
            val histogram = histograms[index]
            quantileOfHistogram(histogram, histogram.sum(), BG_PERCENTILE)
        }
        val smoothed = smoothGrid(cells, cols, rows)

        // 兜底：中位数代表「这幅画整体的亮度水平」，一半作为黑区的下限
        val median = quantileOfHistogram(global, width * height, 0.5)
        val floor = maxOf(BG_FLOOR, median / 2)
        for (index in smoothed.indices) {
            if (smoothed[index] < floor) smoothed[index] = floor
        }
        return BackgroundGrid(width, height, cols, rows, smoothed)
    }

    /** 网格的 3×3 均值平滑，边界按实际邻居数取平均（不改中心值之外的结构）。 */
    private fun smoothGrid(cells: IntArray, cols: Int, rows: Int): IntArray {
        val out = IntArray(cells.size)
        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                var sum = 0
                var count = 0
                for (dy in -1..1) {
                    val yy = cy + dy
                    if (yy < 0 || yy >= rows) continue
                    for (dx in -1..1) {
                        val xx = cx + dx
                        if (xx < 0 || xx >= cols) continue
                        sum += cells[yy * cols + xx]
                        count++
                    }
                }
                out[cy * cols + cx] = sum / count
            }
        }
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
     * [onFallback] 可注入是为了 JVM 单测：Paparazzi 带来的 layoutlib jar 里
     * `android.util.Log` 是真身（native 调用），且排在 mockable android.jar
     * 之前，单测里打 Log 会 UnsatisfiedLinkError。生产走默认值 = 真 Log.w。
     *
     * 为了单测能直接构造极端直方图验证守卫，入口保持 internal（与 [steepen]、
     * [calculateSampleSize] 同一约定）；真正的算法核心 [otsuFromHistogram]
     * 由「整包 ARGB」和「一维灰度」两个入口共用，两道 sanity 只有一份实现。
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
        return otsuFromHistogram(histogram, onFallback)
    }

    /**
     * 对一维灰度（0..255）求 Otsu 阈值。
     *
     * 光照均一化之后的图走这条（见 [enhanceForOcr]）：那时灰度已经被就地改写，
     * 没必要再塞回 ARGB 里让下一个函数重新拆一遍通道。
     */
    internal fun otsuThresholdOfGray(
        gray: IntArray,
        onFallback: (String) -> Unit = { msg -> Log.w("ImageNormalizer", msg) }
    ): Int {
        val histogram = IntArray(256)
        gray.forEach { histogram[it.coerceIn(0, 255)]++ }
        return otsuFromHistogram(histogram, onFallback)
    }

    private fun otsuFromHistogram(
        histogram: IntArray,
        onFallback: (String) -> Unit
    ): Int {
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
