package com.mistakebook.markdown

import androidx.collection.LruCache

/**
 * HTML 安全清洗（**纯 Kotlin，可 JVM 单测**）。v0.0.57 新增。
 *
 * ## 背景
 *
 * 模型开始用 ```html 围栏输出完整图示 / 排版块（用户要求「HTML 渲染要真正实现」），
 * 但此前 MarkdownParser 只把 SVG 围栏转图形，html 围栏落到 [MarkdownParser.Block.Code]
 * ——生成结束后气泡里就是一整段源码。用户看到的「流式正常、结束变源码」大多属于这一类。
 *
 * ## 为什么必须清洗
 *
 * 渲染层用的是 WebView。模型输出是不可信输入：提示词注入可以在 HTML 里塞
 * `<script>`、`onload=`、`javascript:` 链接，甚至外链图片把用户 IP 泄露出去。
 * 所以**先清洗、再交给 WebView**，WebView 侧再关掉网络加载（见 HtmlBlock）。
 *
 * ## 清洗规则（宁可少显示，不可执行）
 *
 * - `<script>` / `<iframe>` / `<object>` / `<embed>` / `<form>` / `<button>` / `<input>`
 *   / `<link>` / `<base>`：整段删；
 * - `<meta http-equiv="refresh">`：删（可做跳转）；
 * - `on*` 事件属性：删；
 * - `javascript:` / `vbscript:` / `data:text/html` 的 href：清空；
 * - `<img src="http...">`：清空 src（外链 = 隐私泄露；提示词也要求不外链）；
 * - `@import`、CSS `expression(`、`url(javascript:)`：删；
 * - XML 声明 / DOCTYPE / `<html>` / `<head>` / `<body>` 外壳：剥掉，只留片段，
 *   由 [HtmlDocument] 用统一外壳重新包起来（保证 viewport 与基础样式）。
 *
 * ## 与原 HTML 表格解析的关系
 *
 * `MarkdownParser` 仍会把**普通文本里的** `<table>` 抠成原生表格块（排版更好）。
 * 只有 ```html 围栏整块走本类 + WebView——那里通常是带布局/样式的完整页面。
 */
object HtmlSanitizer {

    private const val CACHE_ENTRIES = 32

    private val SCRIPT = Regex("<script\\b[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
    private val SCRIPT_EMPTY = Regex("<script\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    private val IFRAME = Regex("<iframe\\b[^>]*>[\\s\\S]*?</iframe>", RegexOption.IGNORE_CASE)
    private val OBJECT = Regex("<object\\b[^>]*>[\\s\\S]*?</object>", RegexOption.IGNORE_CASE)
    private val FORM = Regex("<form\\b[^>]*>[\\s\\S]*?</form>", RegexOption.IGNORE_CASE)
    private val EMBED = Regex("<embed\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    private val BUTTON = Regex("<button\\b[^>]*>[\\s\\S]*?</button>", RegexOption.IGNORE_CASE)
    private val INPUT = Regex("<input\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    private val LINK = Regex("<link\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    private val BASE = Regex("<base\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    private val META_REFRESH = Regex(
        "<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?refresh[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val EVENT_ATTR = Regex(
        "\\son[a-zA-Z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
        RegexOption.IGNORE_CASE
    )
    private val DANGEROUS_HREF = Regex(
        "\\bhref\\s*=\\s*[\"']\\s*(?:javascript|vbscript|data):[^\"']*[\"']",
        RegexOption.IGNORE_CASE
    )
    /** 外链图片：只清空 src，保留 alt 占位。 */
    private val REMOTE_IMG_SRC = Regex(
        "(<img\\b[^>]*?)\\bsrc\\s*=\\s*[\"']\\s*(?:https?:)?//[^\"']*[\"']",
        RegexOption.IGNORE_CASE
    )
    private val STYLE_IMPORT = Regex("@import[^;]*;", RegexOption.IGNORE_CASE)
    private val CSS_EXPRESSION = Regex("expression\\s*\\([^)]*\\)", RegexOption.IGNORE_CASE)
    private val CSS_JS_URL = Regex(
        "url\\s*\\(\\s*[\"']?\\s*(?:javascript|vbscript):[^)]*\\)",
        RegexOption.IGNORE_CASE
    )
    private val XML_DECL = Regex("<\\?xml[\\s\\S]*?\\?>", RegexOption.IGNORE_CASE)
    private val DOCTYPE = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("</?html\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val HEAD_TAG = Regex("</?head\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BODY_TAG = Regex("</?body\\b[^>]*>", RegexOption.IGNORE_CASE)

    private val cache = LruCache<String, String>(CACHE_ENTRIES)

    /** 单测专用：清缓存，避免用例互相污染。 */
    internal fun resetForTest() = cache.evictAll()

    /**
     * 清洗一段 HTML，返回可安全塞进 WebView `<body>` 的片段。
     * 输入为空 / 清洗后为空时返回空串（渲染层据此不显示）。
     */
    fun sanitize(source: String): String {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return ""
        cache.get(trimmed)?.let { return it }
        val out = compute(trimmed)
        cache.put(trimmed, out)
        return out
    }

    private fun compute(source: String): String {
        var out = source
        out = out.replace(SCRIPT, "")
        out = out.replace(SCRIPT_EMPTY, "")
        out = out.replace(IFRAME, "")
        out = out.replace(OBJECT, "")
        out = out.replace(FORM, "")
        out = out.replace(EMBED, "")
        out = out.replace(BUTTON, "")
        out = out.replace(INPUT, "")
        out = out.replace(LINK, "")
        out = out.replace(BASE, "")
        out = out.replace(META_REFRESH, "")
        out = out.replace(EVENT_ATTR, "")
        out = out.replace(DANGEROUS_HREF) { m ->
            if (m.value.contains("href", ignoreCase = true)) "href=\"#\"" else m.value
        }
        out = out.replace(REMOTE_IMG_SRC) { m -> "${m.groupValues[1]}src=\"\"" }
        out = out.replace(STYLE_IMPORT, "")
        out = out.replace(CSS_EXPRESSION, "")
        out = out.replace(CSS_JS_URL, "")
        // 外壳剥掉：统一由 HtmlDocument 包一层（保证 viewport / 基础排版）
        out = out.replace(XML_DECL, "")
        out = out.replace(DOCTYPE, "")
        out = out.replace(HTML_TAG, "")
        out = out.replace(HEAD_TAG, "")
        out = out.replace(BODY_TAG, "")
        return out.trim()
    }
}

/** 渲染层用的短名字。 */
internal fun sanitizeHtml(source: String): String = HtmlSanitizer.sanitize(source)

/**
 * 把清洗后的片段包成完整 HTML 文档（v0.0.57）。
 *
 * ## 为什么用统一外壳
 *
 * 模型给的 HTML 有时是片段、有时是完整文档，样式/字号/颜色五花八门。
 * 统一外壳保证三件事：
 * 1. **viewport**：没有它，WebView 按 980px 虚拟宽度缩放，字会小得看不清；
 * 2. **基础排版**：字体、行高、表格边框、图片不溢出——即使模型没写样式也可读；
 * 3. **配色跟随主题**：深色模式下白底黑字会把用户刺到。
 *
 * 片段里自带的 `<style>` 仍然生效（在外壳之后解析，优先级更高），
 * 所以「模型想要自定义样式」不被这个外壳压制。
 */
object HtmlDocument {

    /**
     * @param fragment 已过 [HtmlSanitizer] 的 HTML 片段。
     * @param textArgb 正文字色（`0xFFRRGGBB` 形式）。
     * @param backgroundArgb 背景色。
     */
    fun wrap(fragment: String, textArgb: Int, backgroundArgb: Int): String {
        val fg = hex(textArgb)
        val bg = hex(backgroundArgb)
        val border = hex(blend(backgroundArgb, textArgb, 0.25f))
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
            *, *::before, *::after { box-sizing: border-box; }
            html,body{margin:0;padding:0;background:transparent;}
            body{font-family:system-ui,-apple-system,"Noto Sans CJK SC","PingFang SC",sans-serif;
                 color:$fg;font-size:15px;line-height:1.65;word-break:break-word;overflow-wrap:anywhere;
                 padding:4px 2px 20px 2px;}
            h1,h2,h3,h4{line-height:1.3;margin:.6em 0 .4em;}
            p{margin:.4em 0;}
            table{border-collapse:collapse;width:100%;margin:.5em 0;}
            th,td{border:1px solid $border;padding:6px 8px;text-align:left;vertical-align:top;}
            th{background:rgba(127,127,127,.12);}
            img,svg,video,canvas{max-width:100%;height:auto;}
            pre{white-space:pre-wrap;word-break:break-word;margin:.4em 0;}
            code{font-family:ui-monospace,Menlo,monospace;}
            a{color:#2f7bd8;text-decoration:underline;}
            </style>
            </head>
            <body>
            $fragment
            <script>
            (function() {
                function report() {
                    try {
                        var h = Math.max(
                            document.body ? document.body.scrollHeight : 0,
                            document.documentElement ? document.documentElement.scrollHeight : 0,
                            document.body ? document.body.offsetHeight : 0
                        );
                        if (window.HtmlBridge && window.HtmlBridge.onHeight) {
                            window.HtmlBridge.onHeight(h);
                        }
                    } catch(e) {}
                }
                if (document.readyState === 'complete' || document.readyState === 'interactive') {
                    report();
                } else {
                    window.addEventListener('DOMContentLoaded', report);
                    window.addEventListener('load', report);
                }
                if (typeof ResizeObserver !== 'undefined' && document.body) {
                    new ResizeObserver(report).observe(document.body);
                }
                setTimeout(report, 100);
                setTimeout(report, 400);
                setTimeout(report, 1000);
            })();
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** 在背景色与文字色之间插值，用来给表格边框一个不刺眼的中间色。 */
    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
