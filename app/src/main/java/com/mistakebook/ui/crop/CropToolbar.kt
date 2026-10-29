package com.mistakebook.ui.crop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mistakebook.R

@OptIn(ExperimentalMaterial3Api::class)
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
    /** T2 对话连拍：非空时显示「不裁切，下一张」跳过入口（本张用原图）。 */
    onSkipCurrent: (() -> Unit)? = null,
    /** 对比度增强档位（0 关 / 1 轻 / 2 标准 / 3 强），v0.0.55 反馈。 */
    enhanceStrength: Int = 2,
    /** 增强档位条是否展开。默认收起，点「增强」才展开——见下方注释。 */
    enhancePanelOpen: Boolean = false,
    onEnhancePanelOpenChange: (Boolean) -> Unit = {},
    onEnhanceStrengthChange: (Int) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (maskMode) stringResource(R.string.crop_mask_hint) else stringResource(R.string.crop_hint),
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.weight(1f)
            ) {
                SegmentedButton(
                    selected = !maskMode,
                    onClick = { onMaskModeChange(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(stringResource(R.string.crop_selection))
                }
                SegmentedButton(
                    selected = maskMode,
                    onClick = { onMaskModeChange(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(stringResource(R.string.crop_mask))
                }
            }
            Spacer(Modifier.width(8.dp))
            // 增强入口（v0.0.55 反馈）：单独一颗按钮，点开展开四档档位条。
            //
            // 为什么不常驻：工具栏已经有一排模式切换 + 一排操作，再常驻一排四档
            // 会把裁剪区压得很小——而裁切区正是这个页面的主战场。档位条按需展开，
            // 展开时当前档位写在按钮上，用户随时知道现在用的是哪档。
            TextButton(
                onClick = { onEnhancePanelOpenChange(!enhancePanelOpen) },
                modifier = Modifier.weight(0.62f)
            ) {
                Text(
                    text = stringResource(
                        when (enhanceStrength.coerceIn(0, 3)) {
                            0 -> R.string.capture_ocr_off
                            1 -> R.string.capture_ocr_light
                            3 -> R.string.capture_ocr_strong
                            else -> R.string.capture_ocr_standard
                        }
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (enhancePanelOpen) {
            // 四档档位条：与拍照页原先那颗档位条同一套资源与顺序，
            // 只是搬到了「能同时看到裁切结果」的地方。
            val options = listOf(
                R.string.capture_ocr_off,
                R.string.capture_ocr_light,
                R.string.capture_ocr_standard,
                R.string.capture_ocr_strong
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, titleRes ->
                    SegmentedButton(
                        selected = enhanceStrength.coerceIn(0, 3) == index,
                        onClick = { onEnhanceStrengthChange(index) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Color.White.copy(alpha = 0.9f),
                            inactiveContainerColor = Color.Black.copy(alpha = 0.3f),
                            activeContentColor = Color.Black,
                            inactiveContentColor = Color.White,
                            activeBorderColor = Color.White
                        )
                    ) {
                        Text(stringResource(titleRes))
                    }
                }
            }
            Text(
                text = stringResource(R.string.crop_enhance_hint),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (maskMode) {
                TextButton(
                    onClick = onUndo,
                    enabled = hasStrokes,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.crop_mask_undo), color = Color.White)
                }
            } else {
                TextButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.crop_reset), color = Color.White)
                }
            }

            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1.5f)
                    .padding(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text(stringResource(R.string.crop_confirm))
            }

            if (maskMode) {
                TextButton(
                    onClick = onClear,
                    enabled = hasStrokes,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.crop_mask_clear), color = Color.White)
                }
            } else {
                TextButton(
                    onClick = onRotate,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.crop_rotate), color = Color.White)
                }
            }
        }

        if (onSkipCurrent != null) {
            TextButton(
                onClick = onSkipCurrent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.crop_chat_skip), color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}
