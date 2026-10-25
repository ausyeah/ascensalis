package com.mistakebook.ui.common

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
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
    AsyncImage(
        model = request,
        contentDescription = "示意图",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
    )
}

/** 4:3：任务书约定的 viewBox（400×300）比例，也是最常见的图示比例。 */
private const val FALLBACK_RATIO = 400f / 300f
