package com.mistakebook.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
@Composable
fun QuestionThumb(
    imagePath: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
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
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// 大图查看对话框：双指缩放 + 拖动
@Composable
fun FullScreenImageDialog(
    imagePath: String,
    contentDescription: String?,
    onDismiss: () -> Unit
) {
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
        }
    }
}

@Composable
private fun ZoomableImage(
    imagePath: String,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
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

// 空状态占位
@Composable
fun EmptyState(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
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

