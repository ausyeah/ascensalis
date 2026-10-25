package com.mistakebook.pipeline

/**
 * JSON 修复（**纯 Kotlin，可 JVM 单测**）。
 *
 * ## 为什么需要它
 *
 * 大模型输出的 JSON 几乎不可能每次都合法，而「解析失败」在用户侧的表现
 * 就是**整道题识别失败**。修回一段非法 JSON，等于救回一次识别。
 * 这里只做**确定性**的修复——能明确判断出原意的那些：
 *
 * 1. **字符串里的裸换行 / 制表符**：模型写多行题干时直接回车，
 *    JSON 字符串不允许裸换行，整段解析失败。这是最高频的一种。
 * 2. **输出被截断**：末尾缺闭合引号或括号（max_tokens 打满的典型现场），
 *    把没闭合的部分补上，前面的题目内容就还能用。
 * 3. 结构性的：围栏、首尾花括号、尾随逗号、全角引号（沿用既有逻辑）。
 *
 * 不做的事：**猜**未转义的内部双引号（`"stem": "他说"你好""`）。
 * 猜错会把合法 JSON 改坏，得不偿失——那种输出宁可降级。
 */
object JsonRepair {

    /**
     * 把字符串字面量内的裸控制字符转义掉（换行 → `\n`、制表 → `\t`）。
     *
     * 只在**字符串内**动手：字符串外的换行是排版空白，本来就该保留
     * （解析时会被忽略）。
     */
    fun escapeControlCharsInStrings(text: String): String {
        val out = StringBuilder(text.length + 16)
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString && c == '\\') {
                // 转义序列：原样带过去，不然后面的 \" 会被当成字符串结束
                out.append(c)
                if (i + 1 < text.length) {
                    out.append(text[i + 1])
                    i++
                }
            } else if (c == '"') {
                inString = !inString
                out.append(c)
            } else if (inString) {
                when (c) {
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c.code < 0x20) {
                        // 其余控制字符在 JSON 字符串里非法且没有信息量，丢掉
                    } else {
                        out.append(c)
                    }
                }
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /**
     * 补齐被截断的结尾：字符串没闭合补 `"`，括号没闭合补 `}` / `]`。
     *
     * 扫描时跳过字符串内容，所以字符串里的 `{` 不会被误当成没闭合的括号。
     */
    fun closeTruncated(text: String): String {
        var out = text
        var inString = false
        val open = ArrayList<Char>()
        var i = 0
        while (i < out.length) {
            val c = out[i]
            if (inString) {
                when {
                    c == '\\' -> i++ // 跳过被转义的字符
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> open += '{'
                    '[' -> open += '['
                    '}' -> if (open.lastOrNull() == '{') open.removeAt(open.lastIndex)
                    ']' -> if (open.lastOrNull() == '[') open.removeAt(open.lastIndex)
                }
            }
            i++
        }
        if (inString) out += "\""
        for (index in open.lastIndex downTo 0) {
            out += if (open[index] == '{') '}' else ']'
        }
        return out
    }

    /**
     * 全套修复（顺序有讲究）：
     * 围栏 → 首尾花括号 → 尾随逗号 → 全角引号 → 裸控制字符 → 截断补全。
     *
     * 全角引号必须在控制字符转义**之前**（先定准字符串边界），
     * 截断补全必须在最后（前面几步可能切掉尾部，那时才谈得上补什么）。
     */
    fun repair(text: String): String {
        var out = stripFence(text)
        val start = out.indexOf('{')
        val end = out.lastIndexOf('}')
        if (start >= 0 && end > start) out = out.substring(start, end + 1)
        out = out.replace(Regex("""\s*,\s*([}\]])"""), "$1")
        out = out.replace('\u201C', '"').replace('\u201D', '"')
        out = out.replace('\u2018', '"').replace('\u2019', '"')
        out = escapeControlCharsInStrings(out)
        return closeTruncated(out)
    }

    private fun stripFence(text: String): String =
        text.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
}
