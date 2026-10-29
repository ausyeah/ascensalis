package com.mistakebook.markdown

import androidx.collection.LruCache

/**
 * SVG 安全清洗（**纯 Kotlin，可 JVM 单测**）。
 *
 * ## 为什么要清洗
 *
 * SVG 是 XML，能带脚本、能引外链。模型输出的辅助图示本来只是几条线
 * 和几个 `<text>`，但「模型输出」是不可信输入——它可能被提示词注入
 * 带进 `<script>`，也可能随手引一个外链图片（等于把用户的 IP 和
 * 使用时间泄露给第三方）。
 *
 * ## 清洗什么
 *
 * - `<script>` / `<foreignObject>`：整段连内容一起删（能执行脚本或塞 HTML）；
 * - `<image href="http...">`：删掉整个元素（外链图片 = 隐私泄露 + 内容不可控）；
 * - `on*` 事件属性、`javascript:` 伪协议：删（执行入口）；
 * - 几何 / 文本 / 样式元素：原样保留——那才是图示的内容。
 *
 * ## 顺手补的一件事
 *
 * 缺 `xmlns` 时补上。AndroidSVG（coil-svg 用的解码器）**没有命名空间会
 * 直接解析失败**，而模型输出 SVG 时经常省掉这一句——不补的话，
 * 明明是一段正确的图形，界面上却什么都不显示。
 */
object SvgSanitizer {

    private val SCRIPT = Regex("<script\\b[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
    private val SCRIPT_EMPTY = Regex("<script\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    private val FOREIGN = Regex(
        "<foreignObject\\b[^>]*>[\\s\\S]*?</foreignObject>",
        RegexOption.IGNORE_CASE
    )
    /** 其它可嵌入可执行内容 / 外链的容器（v0.0.57：兼容性加固）。 */
    private val IFRAME = Regex("<iframe\\b[^>]*>[\\s\\S]*?</iframe>", RegexOption.IGNORE_CASE)
    private val OBJECT = Regex("<object\\b[^>]*>[\\s\\S]*?</object>", RegexOption.IGNORE_CASE)
    private val EMBED = Regex("<embed\\b[^>]*/?>", RegexOption.IGNORE_CASE)
    /** XML 声明 / DOCTYPE：AndroidSVG 对不规范的这些前缀较敏感，直接去掉。 */
    private val XML_DECL = Regex("<\\?xml[\\s\\S]*?\\?>", RegexOption.IGNORE_CASE)
    private val DOCTYPE = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE)
    /** 带 http(s) 外链的 image 元素（含自闭合与成对两种写法，也认协议相对 `//`）。 */
    private val REMOTE_IMAGE = Regex(
        "<image\\b[^>]*?(?:xlink:)?href\\s*=\\s*[\"']\\s*(?:https?:)?//[^\"']*[\"'][^>]*?(?:/>|>[\\s\\S]*?</image>)",
        RegexOption.IGNORE_CASE
    )
    private val EVENT_ATTR = Regex(
        "\\son[a-zA-Z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
        RegexOption.IGNORE_CASE
    )
    private val JS_HREF = Regex(
        "(xlink:)?href\\s*=\\s*[\"']\\s*(?:javascript|data:text/html):[^\"']*[\"']",
        RegexOption.IGNORE_CASE
    )
    private val XMLNS = Regex("xmlns\\s*=", RegexOption.IGNORE_CASE)
    private val HTML_OPEN = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val HTML_CLOSE = Regex("</html\\s*>", RegexOption.IGNORE_CASE)
    private val BODY_OPEN = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BODY_CLOSE = Regex("</body\\s*>", RegexOption.IGNORE_CASE)

    private const val SVG_SANITIZE_CACHE_ENTRIES = 64

    /**
     * v0.0.54 后续优化 · Task B：按原始 source 缓存清洗结果。
     *
     * sanitize 是**纯函数**，缓存不改变任何输出。动机：模型把 SVG 写闭合后，
     * 这段源码在后续每个流式 chunk 里都不变（新内容都加在它后面），而
     * [MarkdownParser] 每个 chunk 都重解析整篇 → 同一段闭合 SVG 被反复全量清洗。
     * 按 source 缓存后，这些重复清洗命中，「每 chunk 一次全量」降成
     * 「一次全量 + 其余 O(1) 查表」。未闭合阶段的重复清洗由 MarkdownModel 的
     * 超长降级（超长未闭合先不产出）兜住，两者叠加消除 O(n²)。
     */
    private val cache = LruCache<String, String>(SVG_SANITIZE_CACHE_ENTRIES)

    /**
     * 清洗工作量计数器，**仅供单测**断言「有上界」，不是生产 API。
     * [sanitizeCallCount] = sanitize 被进入次数；[sanitizeCharCount] = 真正过正则的字符数（仅缓存未命中累计）。
     */
    internal var sanitizeCallCount = 0
    internal var sanitizeCharCount = 0L

    /** 单测专用：清零计数器并清空缓存，避免用例之间互相污染。 */
    internal fun resetForTest() {
        sanitizeCallCount = 0
        sanitizeCharCount = 0L
        cache.evictAll()
    }

    fun sanitize(source: String): String {
        sanitizeCallCount++
        cache.get(source)?.let { return it }
        sanitizeCharCount += source.length
        val out = computeSanitized(source)
        cache.put(source, out)
        return out
    }

    private fun computeSanitized(source: String): String {
        var out = source.trim()
        if (out.isBlank()) return ""
        // 先剥外壳：模型常把 svg 包在 ```html / <?xml?> / <!DOCTYPE> 里。
        // AndroidSVG 遇到 `<html>` 包裹会解析失败 —— 剥掉后图才出得来。
        out = out.replace(XML_DECL, "")
        out = out.replace(DOCTYPE, "")
        out = out.replace(HTML_OPEN, "")
        out = out.replace(HTML_CLOSE, "")
        out = out.replace(BODY_OPEN, "")
        out = out.replace(BODY_CLOSE, "")
        out = out.replace(SCRIPT, "")
        out = out.replace(SCRIPT_EMPTY, "")
        out = out.replace(FOREIGN, "")
        out = out.replace(IFRAME, "")
        out = out.replace(OBJECT, "")
        out = out.replace(EMBED, "")
        out = out.replace(REMOTE_IMAGE, "")
        out = out.replace(EVENT_ATTR, "")
        // javascript: 伪协议的 href 直接清空，而不是删掉整个元素——
        // 删元素会连带删掉可能是合法几何的兄弟节点，清空属性更精确。
        out = out.replace(JS_HREF) { m ->
            val prefix = if (m.value.startsWith("xlink:", ignoreCase = true)) "xlink:href" else "href"
            "$prefix=\"\""
        }
        out = ensureXmlns(out)
        return out.trim()
    }

    /**
     * 补 xmlns。
     *
     * 只在**根 `<svg>` 标签**上补，且只在确实没有的时候补——
     * 有用户/模型可能用了带命名空间的嵌套结构，盲目插入会破坏格式。
     */
    private fun ensureXmlns(source: String): String {
        if (XMLNS.containsMatchIn(source)) return source
        val openTag = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE).find(source)
            ?: return source
        val tag = openTag.value
        val withNs = tag.replaceFirst(
            Regex("<svg", RegexOption.IGNORE_CASE),
            "<svg xmlns=\"http://www.w3.org/2000/svg\""
        )
        return source.replaceFirst(tag, withNs)
    }
}

/** [MarkdownParser] 里的调用点用它，短名字更好读。 */
internal fun sanitizeSvg(source: String): String = SvgSanitizer.sanitize(source)

/**
 * 从模型输出里抠出一段完整 `<svg>…</svg>` 并清洗（v0.0.57）。
 *
 * 模型很少老老实实「只输出 SVG」：常见的是
 * 「下面是示意图：」+ ```svg 围栏 + 解释。这里做三件事：
 * 1. 取**最外层** `<svg ...>...</svg>`（含嵌套时按配对深度找到正确结尾）；
 * 2. 去掉 XML 声明 / DOCTYPE；
 * 3. 过一遍 [SvgSanitizer.sanitize]。
 *
 * @return 清洗后的 SVG；没有可用的 `<svg>` 或清洗后为空时返回 null。
 *   纯函数，可单测。
 */
fun extractSvgDocument(raw: String): String? {
    if (raw.isBlank()) return null
    val start = Regex("<svg\\b", RegexOption.IGNORE_CASE).find(raw)?.range?.first ?: return null
    // 按嵌套深度找配对的 </svg>：模型偶尔会写 <svg><svg>…</svg></svg>。
    var depth = 0
    var index = start
    var end = -1
    val openTag = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)
    val closeTag = Regex("</svg\\s*>", RegexOption.IGNORE_CASE)
    while (index < raw.length) {
        val open = openTag.find(raw, index)
        val close = closeTag.find(raw, index)
        if (open == null && close == null) break
        if (open != null && (close == null || open.range.first < close.range.first)) {
            depth++
            index = open.range.last + 1
        } else if (close != null) {
            depth--
            index = close.range.last + 1
            if (depth <= 0) {
                end = index
                break
            }
        } else {
            break
        }
    }
    if (end <= start) return null
    val doc = SvgSanitizer.sanitize(raw.substring(start, end))
    return doc.takeIf { it.contains("<svg", ignoreCase = true) }
}

/**
 * 从 viewBox 取宽高比（`viewBox="0 0 400 300"` → 400/300）。
 * 渲染层按它给 SVG 定高度：宽度铺满、高度等比，图才不会被压扁或拉长。
 *
 * 取不到 viewBox 时退回 `<svg width=".." height="..">`（v0.0.57：很多模型
 * 只写 width/height 不写 viewBox，此前一律按 4:3 兜底，比例不对的图会被
 * 压扁）。都取不到返回 null，调用方再用兜底比例。
 *
 * 注意 viewBox 四个数是 `min-x min-y width height`，宽高是**后两个**。
 */
fun svgAspectRatio(source: String): Float? {
    val match = Regex("viewBox\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        .find(source)
    if (match != null) {
        val parts = match.groupValues[1].trim().split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (parts.size >= 4) {
            val width = parts[2].toFloatOrNull()
            val height = parts[3].toFloatOrNull()
            if (width != null && height != null && width > 0f && height > 0f) {
                return width / height
            }
        }
    }
    // 退回根 <svg> 标签上的 width/height（可带 px 单位）
    val openTag = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE).find(source)?.value ?: return null
    val width = numberAttr(openTag, "width") ?: return null
    val height = numberAttr(openTag, "height") ?: return null
    if (width <= 0f || height <= 0f) return null
    return width / height
}

/** 取标签属性里的数值（忽略 `px` 等单位）；百分比 / `auto` 返回 null。 */
private fun numberAttr(tag: String, name: String): Float? {
    val raw = Regex("""\b$name\s*=\s*["']\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
        .find(tag)?.groupValues?.get(1) ?: return null
    return raw.toFloatOrNull()
}
