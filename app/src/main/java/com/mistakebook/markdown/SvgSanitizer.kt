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
    private val FOREIGN = Regex(
        "<foreignObject\\b[^>]*>[\\s\\S]*?</foreignObject>",
        RegexOption.IGNORE_CASE
    )
    /** 带 http(s) 外链的 image 元素（含自闭合与成对两种写法）。 */
    private val REMOTE_IMAGE = Regex(
        "<image\\b[^>]*?(?:xlink:)?href\\s*=\\s*[\"']\\s*https?:[^\"']*[\"'][^>]*?(?:/>|>[\\s\\S]*?</image>)",
        RegexOption.IGNORE_CASE
    )
    private val EVENT_ATTR = Regex(
        "\\son[a-zA-Z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
        RegexOption.IGNORE_CASE
    )
    private val JS_HREF = Regex(
        "(xlink:)?href\\s*=\\s*[\"']\\s*javascript:[^\"']*[\"']",
        RegexOption.IGNORE_CASE
    )
    private val XMLNS = Regex("xmlns\\s*=", RegexOption.IGNORE_CASE)

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
        out = out.replace(SCRIPT, "")
        out = out.replace(FOREIGN, "")
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
 * 从 viewBox 取宽高比（`viewBox="0 0 400 300"` → 400/300）。
 *
 * 渲染层按它给 SVG 定高度：宽度铺满、高度等比，图才不会被压扁或拉长。
 * 取不到（没写 viewBox、格式不对）时返回 null，调用方用兜底比例。
 *
 * 注意 viewBox 四个数是 `min-x min-y width height`，宽高是**后两个**。
 */
fun svgAspectRatio(source: String): Float? {
    val match = Regex("viewBox\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        .find(source) ?: return null
    val parts = match.groupValues[1].trim().split(Regex("[\\s,]+")).filter { it.isNotBlank() }
    if (parts.size < 4) return null
    val width = parts[2].toFloatOrNull() ?: return null
    val height = parts[3].toFloatOrNull() ?: return null
    if (width <= 0f || height <= 0f) return null
    return width / height
}
