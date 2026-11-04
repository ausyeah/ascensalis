package com.mistakebook.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mistakebook.domain.ErrorReason
import com.mistakebook.ui.theme.SurfaceCard
import androidx.compose.material.icons.filled.Download
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.SmallFloatingActionButton
import com.mistakebook.R

// 学科色点
@Composable
fun SubjectDot(
    subjectName: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(SubjectPalette.colorOf(subjectName))
    )
}

// 难度星级展示（1..5）
@Composable
fun DifficultyStars(
    difficulty: Int,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.TextUnit = MaterialTheme.typography.labelSmall.fontSize
) {
    val safe = difficulty.coerceIn(1, 5)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { index ->
            Text(
                text = if (index < safe) "★" else "☆",
                style = MaterialTheme.typography.labelSmall,
                fontSize = size,
                color = if (index < safe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        }
    }
}

// 难度点选（编辑页）：返回被点击的难度值
@Composable
fun DifficultyPicker(
    difficulty: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { index ->
            val star = index + 1
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onChange(star) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (star <= difficulty) "★" else "☆",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (star <= difficulty) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
            }
        }
    }
}

// 错因标签
@Composable
fun ErrorReasonChip(
    reason: ErrorReason,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = reason.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// 顶部小节标题
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

/**
 * 缩略图的解码边长（像素）。
 *
 * 不指定 size 时 Coil 会**按原图尺寸解码**：手机拍的题图动辄 3000~4000px，
 * 一张解码出来就是几十 MB 的 Bitmap，列表里挂着十几张足以把界面拖垮
 * （首页、详情页、编辑页全是这个组件）。512px 足够覆盖最大的缩略图显示尺寸。
 */
private const val THUMB_DECODE_PX = 512

// 原图预览：点击看大图，支持缩放
/**
 * 题目图片缩略图。
 *
 * ## 「保持图片比例」是怎么生效的
 *
 * 旧版调用方给的都是固定方形尺寸（如 72×72）+ [ContentScale.Crop]，
 * 拍出来的长条作业图会被裁到只剩中间一坨——看起来就像「适应比例没生效」。
 *
 * 现在图片加载成功后读取真实宽高比，把它接到外层尺寸上：
 * 调用方只约束**高度**（或给一个高度区间），宽度 = 高度 × 比例自动展开，
 * 于是横图更宽、竖图更窄，画面内容完整不裁。
 *
 * 极端长图按 0.4 ~ 3.0 的比例夹住，防止一条 10:1 的长截图把列表撑爆。
 */
@Composable
fun QuestionThumb(
    imagePath: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /**
     * 是否按图片真实比例展开宽度。
     *
     * - true（默认）：高度由调用方给定，宽度 = 高度 × 图片比例——详情页
     *   大图、编辑页附图用它，画面完整不裁；
     * - false：完全遵守调用方给的固定尺寸（如列表里 72×72 方块），
     *   配合 [ContentScale.Crop] 居中裁剪——列表小图要整齐划一，用它。
     */
    keepAspect: Boolean = true,
    /**
     * 图不可用时的**文字封面**内容（一般传题干）。
     *
     * 覆盖三类场景：纯文本 PDF 路线（photoPath 是 .md，不是图片）、
     * 原图被删、后缀不是图片格式。以前这些情况显示的是一块空白，
     * 列表里没有视觉锚点——PDF 识别出来的题尤其明显（整列都没封面）。
     */
    fallbackText: String? = null
) {
    val context = LocalContext.current
    // request 也 remember：否则每次重组都新建 ImageRequest，Coil 会认为
    // 是不同的请求而重新走一遍加载判定。
    val request = remember(imagePath) {
        ImageRequest.Builder(context)
            .data(imagePath)
            .size(THUMB_DECODE_PX)
            .build()
    }
    // 加载成功前置 null → 先按 1:1 占位（调用方给的高度当边长），
    // 成功后切到真实比例。remember(imagePath)：换图（重新裁切）后重算。
    var aspectRatio by remember(imagePath) { mutableStateOf<Float?>(null) }
    // 一开始就判一次：PDF 文本路线的路径根本不是图片，
    // 等 Coil 异步失败再切会先闪一帧空白。
    var failed by remember(imagePath) {
        mutableStateOf(!com.mistakebook.util.CoverFallback.isLikelyImage(imagePath))
    }
    Surface(
        modifier = modifier
            .then(if (keepAspect) Modifier.aspectRatio(aspectRatio ?: 1f) else Modifier)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (failed) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = com.mistakebook.util.CoverFallback.label(fallbackText.orEmpty()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(6.dp)
                )
            }
            return@Surface
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                val drawable = state.result.drawable
                val w = drawable.intrinsicWidth
                val h = drawable.intrinsicHeight
                if (w > 0 && h > 0) {
                    aspectRatio = (w.toFloat() / h).coerceIn(0.4f, 3f)
                }
            },
            // 真去解码才发现的失败（文件损坏、空文件）也落到文字封面，
            // 不留一块白。
            onError = { failed = true },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// 大图查看对话框：双指缩放 + 拖动
@Composable
fun FullScreenImageDialog(
    imagePath: String,
    contentDescription: String? = null,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // v0.0.114：长按保存到系统相册。绘图产物与拍题照片共用这一张画布。
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            ZoomableImage(
                imagePath = imagePath,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = Color.White
                )
            }
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(
                            if (saveToGallery(context, imagePath)) R.string.image_saved
                            else R.string.image_save_failed
                        ),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = androidx.compose.ui.res.stringResource(
                        R.string.image_save_to_gallery
                    ),
                    tint = Color.White
                )
            }
        }
    }
}

/**
 * 把本地图片存进系统相册（Pictures/Ascensalis）。
 *
 * API 29+ 走 MediaStore，应用自身的插入不需要任何权限；
 * 26-28 落到应用专属外部目录再扫媒入库，同样零权限。
 *
 * @return true = 已入库（或已落盘并通知了媒体扫描）。
 */
internal fun saveToGallery(context: android.content.Context, imagePath: String): Boolean {
    val file = java.io.File(imagePath)
    if (!file.isFile) return false
    return runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(
                    android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    file.name
                )
                put(
                    android.provider.MediaStore.Images.Media.MIME_TYPE,
                    "image/" + file.extension.removePrefix(".").ifBlank { "jpeg" }
                )
                put(
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/Ascensalis"
                )
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: return@runCatching false
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                ?: return@runCatching false
            val target = java.io.File(dir, file.name)
            file.copyTo(target, overwrite = true)
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf("image/*"),
                null
            )
            true
        }
    }.getOrDefault(false)
}

@Composable
private fun ZoomableImage(
    imagePath: String,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 不能用 rememberSaveable：Offset 不可被 Saver 序列化，会在配置变更时崩溃
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
            // 长按 = 保存到相册（v0.0.114 用户指定）
            .pointerInput(imagePath) {
                detectTapGestures(
                    onLongPress = {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(
                                if (saveToGallery(context, imagePath)) R.string.image_saved
                                else R.string.image_save_failed
                            ),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imagePath,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

/**
 * 空状态占位（Mihon 配方：字形符号 + 居中说明 + 等宽操作区）。
 *
 * 字形符号用几何空圆而不是插画：克制派空态的自我表达止于一个
 * 低对比的形状，插画一上就抢了内容的戏。默认不传则保持纯文字。
 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    /** 顶部低对比字形符号（如「◌」）；不传则只有文字。 */
    glyph: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (glyph != null) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

// 通用信息行：用于详情页「学科 / 难度 / 错因」
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

