package com.mistakebook.ui.crop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    onSkipCurrent: (() -> Unit)? = null
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

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(0.6f)
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
