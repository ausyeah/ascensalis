package com.mistakebook.ui.common

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mistakebook.markdown.HtmlDocument

/**
 * 渲染一段 ```html 围栏（v0.0.57）。
 *
 * ## 为什么用 WebView
 *
 * 用户要的「HTML 真正渲染」意味着带样式、表格、布局的整块内容。Compose
 * 没有通用 HTML 渲染器；自己写一个解析/排版引擎既不现实也不可靠。
 * WebView 是系统自带、零额外依赖，且与打印链路（HTML → PDF）共用同一套排版。
 *
 * ## 安全
 *
 * 1. 进来的 [source] 已经过 [com.mistakebook.markdown.HtmlSanitizer]（去脚本/事件/外链）；
 * 2. 这里再关掉网络加载（`blockNetworkLoads`）与文件访问——即便清洗漏掉
 *    一个远程资源，也拉不下来，不会泄露 IP；
 * 3. 只允许执行我们注入的测量脚本（内容里已无任何 `<script>` / `on*`）。
 *
 * ## 高度
 *
 * WebView 没有「按内容自适应高度」的原生能力。加载完成后用一次
 * `evaluateJavascript` 读 `scrollHeight`，再把高度设回布局。
 * 不这么做只有两个结果：要么固定高度截断内容，要么 WebView 内部滚动
 * （嵌在 LazyColumn 里滚动会和外层抢手势，体验极差）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlBlock(
    source: String,
    modifier: Modifier = Modifier
) {
    if (source.isBlank()) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val textColor = colorScheme.onSurface.toArgb()
    val backgroundColor = colorScheme.surfaceVariant.toArgb()

    // 内容或配色变化都重新生成文档（深色/浅色切换要跟着变）。
    val html = remember(source, textColor, backgroundColor) {
        HtmlDocument.wrap(source, textColor, backgroundColor)
    }
    var heightPx by remember(source) { mutableIntStateOf(1) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                // 内容已清洗；这里只为跑我们注入的高度测量脚本。
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.loadsImagesAutomatically = true
                setBackgroundColor(backgroundColor)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            "Math.max(document.body.scrollHeight, document.documentElement.scrollHeight)"
                        ) { value ->
                            val px = value?.trim('"')?.toFloatOrNull()?.toInt() ?: 0
                            if (px > 0) heightPx = px
                        }
                    }
                }
                tag = html
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        update = { webView ->
            // 复用同一个 WebView：只有文档真的变了才重新加载，
            // 否则每次重组都 load 一次，滚动时疯狂闪白。
            if (webView.tag != html) {
                webView.tag = html
                webView.setBackgroundColor(backgroundColor)
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { heightPx.toDp() }.coerceAtLeast(40.dp))
    )
}
