package com.mistakebook.ui.common

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mistakebook.markdown.svgAspectRatio
import androidx.compose.ui.res.stringResource
import com.mistakebook.R
import kotlinx.coroutines.delay

private class SvgBridge(private val onHeightReported: (Int) -> Unit) {
    @android.webkit.JavascriptInterface
    fun onHeight(cssPixels: Float) {
        onHeightReported(Math.ceil(cssPixels.toDouble()).toInt())
    }
}

/**
 * 渲染一段 SVG 源码（精确图示：几何图、函数图像、受力分析）。
 *
 * ## 为什么用 SVG 而不是生图 API
 *
 * 生图模型画几何是不可靠的（三角形画歪、坐标轴标错），而 SVG 是代码：
 * 形状确定、可无限缩放、颜色可控。数学/物理题的辅助图走这条路才对。
 *
 * ## v0.0.114：coil-svg 换成 WebView
 *
 * 用户反馈「SVG 还是会出现渲染异常」。coil 的 SvgDecoder 是一个
 * **阉割版 SVG 引擎**：不支持 CSS 内联样式、foreignObject、mask、
 * 部分 filter，模型生成的 SVG 一旦用到就渲染残缺或失败。而 WebView
 * 的 SVG 引擎就是浏览器本尊——支持面宽得多，且本应用已经为 HTML 块
 * 维护着一整套「WebView + 高度桥」的基建（见 [HtmlBlock]），复用零成本。
 *
 * ## 高度自适应
 *
 * 与 HtmlBlock 同一套桥：SVG 包进透明底的 HTML 壳，手动测量回传
 * CSS 像素高度。初始高度按 viewBox 宽高比估算（取不到按 4:3），
 * 真实测量到位后覆盖。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SvgBlock(
    source: String,
    modifier: Modifier = Modifier
) {
    if (source.isBlank()) return
    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor = colorScheme.surfaceVariant.toArgb()

    // 初始高度：viewBox 宽高比 × 假定屏宽；取不到按 4:3 兜底
    val initialHeight = remember(source) {
        val ratio = svgAspectRatio(source) ?: (400f / 300f)
        (360f / ratio).toInt().coerceIn(120, 800)
    }

    val html = remember(source) {
        buildString {
            append("<!DOCTYPE html><html><head>")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />")
            append("<style>html,body{margin:0;padding:0;background:transparent;}")
            append("svg{max-width:100%;height:auto;display:block;margin:0 auto;}</style>")
            append("</head><body>")
            append(source)
            append("</body></html>")
        }
    }

    var heightDp by remember(source) { mutableIntStateOf(initialHeight) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun applyHeight(dp: Int) {
        // 60..1200dp 之外的高度不可信（脚本错误页 / 空页）
        if (dp < 60 || dp > 1200) return
        heightDp = dp
    }

    fun measure(view: WebView) {
        view.evaluateJavascript(
            "Math.max(document.body ? document.body.scrollHeight : 0, " +
                "document.documentElement ? document.documentElement.scrollHeight : 0)"
        ) { value ->
            val dp = value?.trim('"')?.toFloatOrNull()
                ?.let { Math.ceil(it.toDouble()).toInt() } ?: 0
            applyHeight(dp)
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView = null }
    }

    LaunchedEffect(html, webView) {
        val view = webView ?: return@LaunchedEffect
        var last = 0L
        for (beat in listOf(200L, 600L, 1500L)) {
            delay(beat - last)
            last = beat
            measure(view)
        }
    }

    // 连 <svg 开标签都没有的源码不是图：回退提示
    val notSvg = remember(source) { !source.contains("<svg", ignoreCase = true) }
    if (notSvg) {
        Text(
            text = stringResource(R.string.svg_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(10.dp)
        )
        return
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.loadsImagesAutomatically = false
                setBackgroundColor(backgroundColor)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER

                addJavascriptInterface(
                    SvgBridge { dp -> post { applyHeight(dp) } },
                    "SvgBridge"
                )

                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.post { measure(view) }
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            view.stopLoading()
        },
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
    )
}
