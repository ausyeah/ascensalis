package com.mistakebook.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.ImageNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拍照增强调节步（v0.0.51 T5）。
 *
 * ## 解决什么
 *
 * 自动增强偶尔黑白反转 / 过度二值化，而拍完之后没有任何补救手段——
 * 增强管线（[ImageNormalizer.enhanceForOcr]）早已按档位参数化
 * （0 关 / 1 轻 / 2 标准 / 3 强），缺的只是拍完后的调节入口。
 *
 * 四档选择 + 实时预览：预览是对**刚拍的原图文件**按档位重跑
 * `enhanceForOcr`（不是叠加），选「关」显示的就是原像素。
 * 确认后把调节结果写回同一文件，后续裁切 / OCR 拿到的就是调节后的图。
 *
 * 上次选择沿用设置里的 `ocrStrength`（DataStore），不另开键：
 * 拍照页顶部的档位条已经写它，两处 UI 共享同一份偏好，语义一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhanceAdjustOverlay(
    imagePath: String,
    initialStrength: Int,
    onConfirmed: (String) -> Unit,
    onDiscarded: () -> Unit
) {
    var strength by remember { mutableStateOf(initialStrength) }
    // 预览位图按档位缓存：切档重算，同档直接复用
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var computing by remember { mutableStateOf(false) }

    LaunchedEffect(imagePath, strength) {
        computing = true
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val src = BitmapFactory.decodeFile(imagePath)
                if (src == null) null
                else if (strength == 0) src
                else {
                    val enhanced = ImageNormalizer.enhanceForOcr(src, strength)
                    if (enhanced !== src) src.recycle()
                    enhanced
                }
            }.getOrNull()
        }
        preview = bmp
        computing = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val bmp = preview
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.capture_enhance_preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (computing) {
            Text(
                text = stringResource(R.string.capture_enhance_computing),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.capture_enhance_title),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall
            )
            val options = listOf(
                R.string.capture_ocr_off,
                R.string.capture_ocr_light,
                R.string.capture_ocr_standard,
                R.string.capture_ocr_strong
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, titleRes ->
                    SegmentedButton(
                        selected = strength == index,
                        onClick = { strength = index },
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDiscarded,
                    enabled = !computing
                ) {
                    Text(stringResource(R.string.action_cancel), color = Color.White)
                }
                Spacer(Modifier.height(0.dp))
                Button(
                    onClick = {
                        // 确认：把调节结果写回原图路径（覆盖），裁切/OCR 即用调节后的图
                        val current = preview
                        if (current != null) {
                            val written = runCatching {
                                java.io.FileOutputStream(imagePath).use { stream ->
                                    current.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                                }
                            }.isSuccess
                            if (written) onConfirmed(imagePath) else onDiscarded()
                        } else {
                            onConfirmed(imagePath)
                        }
                    },
                    enabled = !computing && preview != null
                ) {
                    Text(stringResource(R.string.capture_enhance_confirm))
                }
            }
        }
    }
}
