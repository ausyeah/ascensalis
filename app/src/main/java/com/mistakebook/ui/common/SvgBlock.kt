package com.mistakebook.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.mistakebook.markdown.svgAspectRatio

/**
 * 渲染一段 SVG 源码（精确图示：几何图、函数图像、受力分析）。
 *
 * ## 为什么用 SVG 而不是生图 API
 *
 * 生图模型画几何是不可靠的（三角形画歪、坐标轴标错），而 SVG 是代码：
 * 形状确定、可无限缩放、颜色可控。数学/物理题的辅助图走这条路才对。
 *
 * ## 高度怎么定
 *
 * 宽度铺满、高度按 viewBox 的宽高比算（[svgAspectRatio] 取不到时用
 * 4:3 兜底，也就是任务书约定的 400×300）。**不给固定高度的话
 * Compose 会按 0 高度布局，图根本不显示**——这是最容易踩的一个坑。
 *
 * ## 失败兜底（v0.0.57）
 *
 * 用户反馈「SVG 兼容性 / 健壮性差」：模型偶尔写出解码器不认的 SVG
 * （缺 viewBox、错误嵌套、非法属性），此前失败时**整块空白**，用户
 * 完全不知道这里有图。现在解码失败给一条可见的提示，而不是空气。
 */
@Composable
fun SvgBlock(
    source: String,
    modifier: Modifier = Modifier
) {
    if (source.isBlank()) return
    val context = LocalContext.current
    val ratio = remember(source) { svgAspectRatio(source) ?: FALLBACK_RATIO }
    // 传字节而不是字符串：SvgDecoder 走的是 InputStream 解码路径。
    val request = remember(source, context) {
        ImageRequest.Builder(context)
            .data(source.toByteArray())
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    var failed by remember(source) { mutableStateOf(false) }
    if (failed) {
        Text(
            text = "示意图无法显示",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(10.dp)
        )
        return
    }
    AsyncImage(
        model = request,
        contentDescription = "示意图",
        contentScale = ContentScale.Fit,
        onState = { state ->
            // 只认 Error；Loading / Success 不动，避免成功后又翻成失败态。
            if (state is AsyncImagePainter.State.Error) failed = true
        },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
    )
}

/** 4:3：任务书约定的 viewBox（400×300）比例，也是最常见的图示比例。 */
private const val FALLBACK_RATIO = 400f / 300f
