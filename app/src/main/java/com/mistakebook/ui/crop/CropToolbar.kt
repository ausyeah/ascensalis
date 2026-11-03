package com.mistakebook.ui.crop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistakebook.R

/**
 * 裁剪页的顶栏与底栏（v0.0.95 重做）。
 *
 * ## 为什么换掉 M3 的 TopAppBar / SegmentedButton
 * 那两个组件自带 M3 的默认形状、默认排版和默认色板（紫/灰容器），
 * 与全 App「中性锌灰 + 单强调色 + 层次靠 1dp 描边」的语言完全是两套东西。
 * 叠在黑色裁剪底上尤其突兀：半透明灰块 + 圆角直边分段按钮，
 * 用户说的「土、粗糙」指的就是这一层。
 *
 * ## 现在的语言
 * - 顶栏：纯黑底 + 1dp 白色 8% 下描边，圆形磨砂返回键（与相机页同一颗按钮），
 *   标题下方挂一枚「第 N / M 张」的小胶囊；
 * - 底栏：一整块浮起的面板（圆角取 MaterialTheme.shapes.extraLarge），
 *   内部按「模式 → 增强 → 动作」三层，选中态一律白底深字。
 *
 * 圆角与字号全部走 MaterialTheme，这样设置页的字号/圆角旋钮在这里也生效——
 * 一个旋钮改全 App，不在某个页面留例外。
 */

/** 深色底上的主动作色：白底深字，与相机页「完成」同一套口径。 */
private val ActionInk = Color(0xFF14151A)

/** 顶栏下描边 / 面板描边的同一档白。 */
private val DividerColor = Color.White.copy(alpha = 0.08f)

@Composable
internal fun CropTopBar(
    title: String,
    /** 批次进度（「第 2 / 3 张」）。null 表示单张，不占位。 */
    progress: String?,
    previewEnabled: Boolean,
    onBack: () -> Unit,
    onPreview: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .statusBarsPadding()
            // 下描边画在栏自己的底边：黑色底上不用阴影，一条 8% 的白线
            // 就足以把顶栏和画布分开。画在这里而不是另起一个兄弟节点，
            // 是因为 Scaffold 的 topBar 槽会把多个子节点叠在同一个原点。
            .drawBehind {
                drawRect(
                    color = DividerColor,
                    topLeft = Offset(0f, size.height - 1f),
                    size = Size(size.width, 1f)
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FrostedRoundButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.crop_back),
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = progress,
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // 预览是次要动作：不做成实心按钮，免得和底栏的「确认」抢注意力
        Box(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(Color.White.copy(alpha = if (previewEnabled) 0.12f else 0.06f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.10f),
                    shape = MaterialTheme.shapes.large
                )
                .clickable(
                    enabled = previewEnabled,
                    role = Role.Button,
                    onClick = onPreview
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.crop_preview),
                color = Color.White.copy(alpha = if (previewEnabled) 0.92f else 0.3f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
internal fun CropToolbar(
    maskMode: Boolean,
    onMaskModeChange: (Boolean) -> Unit,
    hasStrokes: Boolean,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onRotate: () -> Unit,
    onReset: () -> Unit,
    onConfirm: () -> Unit,
    /** 对比度增强档位（0 关 / 1 轻 / 2 标准 / 3 强），v0.0.55 反馈。 */
    enhanceStrength: Int = 2,
    onEnhanceStrengthChange: (Int) -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(Color.Black.copy(alpha = 0.72f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.10f),
                    shape = MaterialTheme.shapes.extraLarge
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (maskMode) {
                    stringResource(R.string.crop_mask_hint)
                } else {
                    stringResource(R.string.crop_hint)
                },
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall
            )

            SegmentedTrack(
                options = listOf(
                    stringResource(R.string.crop_selection),
                    stringResource(R.string.crop_mask)
                ),
                selectedIndex = if (maskMode) 1 else 0,
                onSelect = { onMaskModeChange(it == 1) }
            )

            // 增强四档常驻（v0.0.57 起）：藏起来只会让人以为没有这个功能。
            // 档位条比模式切换矮一档——它是「参数」不是「模式」。
            SegmentedTrack(
                options = listOf(
                    stringResource(R.string.capture_ocr_off),
                    stringResource(R.string.capture_ocr_light),
                    stringResource(R.string.capture_ocr_standard),
                    stringResource(R.string.capture_ocr_strong)
                ),
                selectedIndex = enhanceStrength.coerceIn(0, 3),
                onSelect = onEnhanceStrengthChange,
                compact = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (maskMode) {
                    GhostAction(
                        label = stringResource(R.string.crop_mask_undo),
                        enabled = hasStrokes,
                        onClick = onUndo,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    GhostAction(
                        label = stringResource(R.string.crop_reset),
                        enabled = true,
                        onClick = onReset,
                        modifier = Modifier.weight(1f)
                    )
                }

                PrimaryAction(
                    label = stringResource(R.string.crop_confirm),
                    onClick = onConfirm,
                    modifier = Modifier.weight(1.5f)
                )

                if (maskMode) {
                    GhostAction(
                        label = stringResource(R.string.crop_mask_clear),
                        enabled = hasStrokes,
                        onClick = onClear,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    GhostAction(
                        label = stringResource(R.string.crop_rotate),
                        enabled = true,
                        onClick = onRotate,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 分段选择条：一条 6% 白的轨道，选中项是一枚白胶囊。
 *
 * 自绘而不是用 M3 SegmentedButton：M3 那套自带 8dp 圆角与大写字母间距，
 * 和本应用「圆角跟主题走、字号偏紧」的排版节奏对不上；
 * 而且它不接受 MaterialTheme.shapes，圆角旋钮对它会失效。
 *
 * 颜色用 animateColorAsState 做 160ms 过渡：直接硬切在黑色底上会「闪」，
 * 但太长又会让点按显得迟钝——160ms 是「感觉到但来不及看」的区间。
 */
@Composable
private fun SegmentedTrack(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = 0.06f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = MaterialTheme.shapes.medium
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val container by animateColorAsState(
                targetValue = if (selected) Color.White else Color.Transparent,
                animationSpec = tween(durationMillis = 160),
                label = "segment-container"
            )
            val content by animateColorAsState(
                targetValue = if (selected) ActionInk else Color.White.copy(alpha = 0.72f),
                animationSpec = tween(durationMillis = 160),
                label = "segment-content"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .background(container)
                    .clickable(role = Role.Button, onClick = { onSelect(index) })
                    .padding(vertical = if (compact) 7.dp else 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = content,
                    style = if (compact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/** 次要动作：只有文字，不抢主动作的注意力；禁用态降到 30% 白。 */
@Composable
private fun GhostAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 0.86f else 0.3f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** 主动作：白底深字 + 勾选图标，与相机页「完成」的视觉权重相等。 */
@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(Color.White)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = ActionInk,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = ActionInk,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** 磨砂圆形图标钮，与相机页顶部那颗同一套参数（44dp / 42% 黑 / 18% 白描边）。 */
@Composable
private fun FrostedRoundButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}