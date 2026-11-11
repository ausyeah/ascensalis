package com.mistakebook.ui.capture

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 连拍成功反馈：白边高亮 → 缩略图飞进右下角 → 扇形堆叠 + 张数气泡（v0.0.95）。
 *
 * ## 为什么要专门做反馈
 * 之前连拍「拍了没反应」：快门按下去要等 CameraX 落盘 + 归一化（几百毫秒），
 * 期间只有快门按钮自己禁用，用户根本不知道这一张到底拍没拍上，于是连按三下
 * 拍出三张一样的题。反馈必须**在拍成的那一刻**给出，而不是等图片处理完。
 *
 * ## 为什么几何计算是纯函数
 * Compose 的测量与绘制跑不了普通 JVM 单测。但「扇形第 0 张该歪几度」
 * 「飞行到 60% 时矩形在哪」这类问题本来就不需要绘制——抽成纯函数后
 * 可以直接在 JVM 里钉住数值，见 CaptureBatchVisualsTest。
 */

/** 堆叠里最多显示几张。再多就靠张数气泡补足，不然会糊成一片。 */
internal const val STACK_VISIBLE_MAX = 3

/** 缩略图解码长边（px）。堆叠里一张只有 38dp，220px 在 3x 屏上刚好不糊。 */
internal const val THUMB_STACK_EDGE = 220

/**
 * 飞行用的解码长边。飞行起始时这张图要铺满整个取景器卡片（约 1080px 宽），
 * 220px 拉伸上去会明显发虚；而且同一时刻只有一张在飞，900px 的内存代价
 * （约 3MB）换来起飞瞬间的清晰，值得。
 */
internal const val THUMB_FLIGHT_EDGE = 900

/** 堆叠里缩略图卡的尺寸与圆角。 */
private val CARD_WIDTH = 38.dp
private val CARD_HEIGHT = 48.dp
private val CARD_CORNER = 7.dp

/** 扇形每档的旋转度数。3 张时最外侧 ±12°，看得到「错开」但不会散架。 */
private const val FAN_SPREAD_DEG = 12f

/** 相邻两档的横向错开量（占卡片宽度的比例）。0.42 刚好露出 2/3 张脸。 */
private const val FAN_SPREAD_X = 0.42f

/** 外侧卡片下沉量（占卡片高度的比例），做出扇子底部收拢的弧。 */
private const val FAN_DROP_Y = 0.10f

internal fun visibleStackCount(count: Int): Int = count.coerceIn(0, STACK_VISIBLE_MAX)

/**
 * 飞行进度 [t]（0..1）时的卡片矩形。
 *
 * 用四边分别插值而不是「起点 + 位移 + 缩放」：起点和终点尺寸差得远
 * （整张取景器 → 40dp 缩略图），分别插值四边才能同时把位置和大小一起收到位，
 * 不会出现先缩后移的两段感。
 */
internal fun flightRect(from: Rect, to: Rect, t: Float): Rect {
    val p = t.coerceIn(0f, 1f)
    return Rect(
        left = from.left + (to.left - from.left) * p,
        top = from.top + (to.top - from.top) * p,
        right = from.right + (to.right - from.right) * p,
        bottom = from.bottom + (to.bottom - from.bottom) * p
    )
}

/** 飞行途中的圆角：跟取景器卡片一致地起手，落到缩略图卡的圆角。 */
internal fun flightCornerDp(t: Float): Float = 24f + (9f - 24f) * t.coerceIn(0f, 1f)

internal fun fanAngleDeg(slot: Int, visible: Int): Float {
    if (visible <= 1) return 0f
    return (slot - (visible - 1) / 2f) * FAN_SPREAD_DEG
}

internal fun fanOffsetXFraction(slot: Int, visible: Int): Float {
    if (visible <= 1) return 0f
    return (slot - (visible - 1) / 2f) * FAN_SPREAD_X
}

internal fun fanOffsetYFraction(slot: Int, visible: Int): Float {
    if (visible <= 1) return 0f
    return abs(slot - (visible - 1) / 2f) * FAN_DROP_Y
}

/**
 * 取一张缩略图。
 *
 * 解码失败（文件被系统清掉、尺寸非法）返回 null——调用方只是少一张预览，
 * 绝不能因此让「拍照成功」这件事变成失败。
 * OOM 单独捕获：runCatching 会把 OutOfMemoryError 一起吞掉，
 * 让内存问题伪装成「文件坏了」（与 CaptureScreen 里的注释同一口径）。
 */
internal fun decodeThumbnail(path: String, maxEdge: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
    }
    return try {
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    } catch (oom: OutOfMemoryError) {
        Log.e("MistakeBookCapture", "缩略图解码 OOM path=$path", oom)
        null
    } catch (error: Throwable) {
        Log.e("MistakeBookCapture", "缩略图解码失败 path=$path", error)
        null
    }
}

/** 一次飞行：路径只用来当动画的 key（同一张重复触发要重启动画）。 */
internal data class CaptureFlight(val path: String, val bitmap: ImageBitmap)

/**
 * 取景器右上角的圆形张数气泡。
 *
 * 用 `clearAndSetSemantics` 把里面的数字换成完整句子：TalkBack 直接读「3」
 * 没有任何信息量，读「本批 3 张」才知道是什么。
 */
@Composable
/**
 * 批次张数胶囊（v0.0.134 用户：「张数放在顶端，扁平胶囊样式」）：
 * 从取景器右上角的 30dp 白色圆徽章改为**扁平胶囊**——黑 42% 底 + 白字，
 * 与闪光/翻转的磨砂圆钮同一底色语言，但无描边、直排文本（「本批 N 张」）。
 * 数字变化的弹跳保留（graphicsLayer scale，绘制期读，不重组）。
 */
internal fun CaptureCountPill(
    text: String,
    modifier: Modifier = Modifier
) {
    val pop = remember { Animatable(1f) }
    val count = text.filter(Char::isDigit).toIntOrNull() ?: 0
    LaunchedEffect(count) {
        // 从 0.7 弹回 1：数字变了要有一眼可见的「又进一张」，
        // 但幅度不能大到像报错——所以只压 30% 再用弹簧回位。
        pop.snapTo(0.7f)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 900f))
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .clearAndSetSemantics { contentDescription = text },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * 右下角的缩略图扇形堆叠，整体就是一个「完成」按钮。
 *
 * 为什么把「完成」藏进缩略图里：原来右下角是独立的「完成 (N)」胶囊，
 * 拍完的照片除了一个数字没有任何视觉凭证——用户不确定自己拍的是哪张、
 * 拍没拍歪。缩略图堆叠一眼就能看到「刚拍的是什么」，点它继续，
 * 这是相机 App 通用的心智模型，不需要额外文案解释。
 */
@Composable
internal fun CaptureThumbnailStack(
    thumbs: List<ImageBitmap>,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = visibleStackCount(thumbs.size)
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.BottomCenter
    ) {
        thumbs.takeLast(visible).forEachIndexed { slot, bitmap ->
            // 每张卡各占一个满尺寸 Box 再在内部 offset：这样不需要
            // BoxScope.align（它是按子节点逐个定位的，会把布局读散），
            // 卡片之间的偏移全部由纯函数算出来，单测能直接断言。
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .offset(
                            x = CARD_WIDTH * fanOffsetXFraction(slot, visible),
                            y = CARD_HEIGHT * fanOffsetYFraction(slot, visible)
                        )
                        .graphicsLayer {
                            rotationZ = fanAngleDeg(slot, visible)
                            // 绕底边中心转：像几本书靠在同一个书立上，
                            // 绕中心转会变成风车，四角张牙舞爪。
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .size(width = CARD_WIDTH, height = CARD_HEIGHT)
                        .clip(RoundedCornerShape(CARD_CORNER))
                        .background(Color.White)
                        .border(1.5.dp, Color.White, RoundedCornerShape(CARD_CORNER))
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .clip(RoundedCornerShape(CARD_CORNER - 2.dp))
                    )
                }
            }
        }
    }
}

/** 一张都没拍时的占位：保持和堆叠同样的尺寸，右下角不会因为状态切换而跳一下。 */
@Composable
internal fun CaptureDonePlaceholder(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Color.White.copy(alpha = 0.08f))
            .semantics(mergeDescendants = true) { disabled() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.35f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 拍成瞬间的整张飞行：从取景器卡片（[from]）收到右下角缩略图位（[to]）。
 *
 * [from] / [to] / [rootOrigin] 都是**窗口坐标**：三处分别用
 * boundsInWindow 量出来，再减去根容器的窗口原点，换算回这层覆盖物的局部坐标。
 * 用窗口坐标而不是 positionInParent，是因为三者层级不同（根 Box / Column 里的卡片 /
 * Row 里的槽位），只有窗口坐标系是共同的。
 */
@Composable
internal fun CaptureFlightOverlay(
    flight: CaptureFlight?,
    from: Rect?,
    to: Rect?,
    rootOrigin: Offset,
    onLanded: () -> Unit
) {
    if (flight == null || from == null || to == null) return
    val progress = remember(flight.path) { Animatable(0f) }
    val landed by rememberUpdatedState(onLanded)
    LaunchedEffect(flight.path) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 430, easing = FastOutSlowInEasing))
        landed()
    }
    // v0.0.134（用户：「拍照动画非常不流畅」）：rect 插值此前在**组合期**
    // 算——每帧重测重绘整层。现在全部挪进 graphicsLayer（绘制期）：
    // 固定基准尺寸 = 起始取景器矩形，平移用 translation、尺寸变化用
    // scaleX/scaleY（transformOrigin 钉在左上，缩放方向与插值一致）。
    // 430ms 的动画从 ~26 次重组降到 0 次。
    val baseW = from.width
    val baseH = from.height
    Box(
        modifier = Modifier
            .graphicsLayer {
                val rect = flightRect(from, to, progress.value)
                translationX = rect.left - rootOrigin.x
                translationY = rect.top - rootOrigin.y
                scaleX = rect.width / baseW
                scaleY = rect.height / baseH
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                shape = RoundedCornerShape(flightCornerDp(progress.value).dp)
                clip = true
            }
            .size(width = with(LocalDensity.current) { baseW.toDp() }, height = with(LocalDensity.current) { baseH.toDp() })
            .border(2.dp, Color.White.copy(alpha = 0.9f), MaterialTheme.shapes.medium)
    ) {
        Image(
            bitmap = flight.bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
