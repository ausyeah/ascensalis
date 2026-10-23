package com.mistakebook.ui.chat

/**
 * 流式正文的"公式门控"。
 *
 * ## 为什么要有这个
 *
 * 流式期间模型是逐 chunk 吐字的，公式经常停在半截：`$$x^2`、`$a+b`、`\(a+b`。
 * 这时候 Markdown 解析器要么把未闭合的 `$$` 产出成 `complete=false` 的块
 * （RichText 里会按 `"$$" + latex` 源码显示），要么把未配对的 `$` 原样
 * 混进文本流——于是界面出现"先显示一段时间的源码、拼完才突然变成公式"，
 * 排版还跟着跳。用户反馈就是这个。
 *
 * 这里把**最后一个未闭合公式之后的尾巴**整体截掉：
 * - 前半段（已闭合公式 + 普通文本）照常渲染、照常滑出；
 * - 尾巴公式在配对符没到齐之前**不显示**，配对符一到齐，整段公式
 *   一次进入渲染链路，以渲染好的姿态滑入，不再闪源码。
 *
 * 只处理尾部：中途的 `$` 一旦配对就按公式走，不会误伤。
 *
 * ## 定界符覆盖面
 *
 * `$$`、`$`、`\(` `\)`、`\[` `\]` 四种都管——模型混用定界符是常态
 * （DeepSeek/o 系偏爱 `\(..\)`），漏一种就会在流式期间闪对应源码。
 *
 * ## 与 [parseInline] 的分工
 *
 * `parseInline` 只负责把"完整的文本"切成 span；它不知道文本是不是流式的。
 * 本函数负责在**进入解析前**把不完整的尾巴剥掉，两者叠加才能做到
 * "未闭合公式绝不显示源码"。
 */
fun trimIncompleteTrailingFormula(text: String): String {
    if (text.isEmpty()) return text
    var i = 0
    var pendingStart = -1
    while (i < text.length) {
        when {
            text[i] == '$' -> {
                val isPair = i + 1 < text.length && text[i + 1] == '$'
                val openLen = if (isPair) 2 else 1
                // 优先匹配 `$$`，与 parseInline 的判定顺序一致（先试 `$$` 再试 `$`）。
                val closeIdx = if (isPair) {
                    text.indexOf("$$", i + openLen)
                } else {
                    text.indexOf('$', i + openLen)
                }
                if (closeIdx >= 0) {
                    i = closeIdx + openLen
                } else {
                    // 未闭合：从这里到结尾都是半截公式，整段隐藏。
                    pendingStart = i
                    break
                }
            }

            // `\(` / `\[`：与 parseInline 的 LaTeX 分支同样找配对闭符
            text[i] == '\\' && i + 1 < text.length &&
                (text[i + 1] == '(' || text[i + 1] == '[') -> {
                val closeToken = if (text[i + 1] == '(') "\\)" else "\\]"
                val closeIdx = text.indexOf(closeToken, i + 2)
                if (closeIdx >= 0) {
                    i = closeIdx + 2
                } else {
                    pendingStart = i
                    break
                }
            }

            else -> i++
        }
    }
    return if (pendingStart >= 0) text.substring(0, pendingStart) else text
}
