package com.mistakebook.ui.common

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mistakebook.markdown.HtmlDocument

private class HtmlBridge(private val onHeightReported: (Int) -> Unit) {
    @JavascriptInterface
    fun onHeight(cssPixels: Float) {
        val dpValue = Math.ceil(cssPixels.toDouble()).toInt()
        onHeightReported(dpValue)
    }
}

/**
 * 渲染一段 ```html 围栏（v0.0.57，v0.0.80 重构高度测量与 JS 桥接通信）。
 *
 * ## 为什么用 WebView
 *
 * 用户要的「HTML 真正渲染」意味着带样式、表格、布局的整块内容。Compose
 * 没有通用 HTML 渲染器；自己写一个解析/排版引擎既不现实也不可靠。
 * WebView 是系统自带、零额外依赖，且与打印链路（HTML → PDF）共用同一套排版。
 *
 * ## 高度自适应（v0.0.80 彻底修复底部截断）
 *
 * 1. 视口 meta 标签声明了 width=device-width，DOM scrollHeight 测量值即为 CSS 像素（等同 Android dp）。
 *    旧版将 CSS 像素二次除以 density，导致在 xxhdpi 屏幕上高度缩水为原来的 1/3，产生严重底部截断。
 * 2. 注入 ResizeObserver 与 HtmlBridge，在 DOM 尺寸变动或异步内容就绪时主动双向回传真实高度；
 * 3. 超链接通过 shouldOverrideUrlLoading 打开系统外部浏览器，避免在内部白屏或崩溃。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlBlock(
    source: String,
    modifier: Modifier = Modifier
) {
    if (source.isBlank()) return
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val textColor = colorScheme.onSurface.toArgb()
    val backgroundColor = colorScheme.surfaceVariant.toArgb()

    val html = remember(source, textColor, backgroundColor) {
        HtmlDocument.wrap(source, textColor, backgroundColor)
    }
    var heightDp by remember(html) { mutableIntStateOf(60) }

    AndroidView(
        factory = { ctx ->
            var lastLayoutWidth = -1
            fun measure(view: WebView) {
                view.evaluateJavascript(
                    "Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.scrollHeight : 0, document.body ? document.body.offsetHeight : 0)"
                ) { value ->
                    val dp = value?.trim('"')?.toFloatOrNull()?.let { Math.ceil(it.toDouble()).toInt() } ?: 0
                    if (dp > heightDp) heightDp = dp
                }
            }
            WebView(ctx).apply {
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

                addJavascriptInterface(HtmlBridge { dp ->
                    post {
                        if (dp > heightDp) {
                            heightDp = dp
                        }
                    }
                }, "HtmlBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.post { view.let { measure(it) } }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return false
                        val scheme = uri.scheme?.lowercase()
                        if (scheme == "http" || scheme == "https") {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                context.startActivity(intent)
                            }
                            return true
                        }
                        return true
                    }
                }
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    val width = view.width
                    if (width > 0 && width != lastLayoutWidth) {
                        lastLayoutWidth = width
                        view.post { measure(view as WebView) }
                    }
                }
                tag = html
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.setBackgroundColor(backgroundColor)
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp.coerceAtLeast(40.dp))
    )
}
