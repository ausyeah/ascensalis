package com.mistakebook.math

/**
 * LaTeX 兼容黑名单转换（v0.0.83，用户约束 11–13 的渲染端兜底）。
 *
 * ## 背景
 *
 * 本渲染器（KaTeX WebView 光栅化）对以下结构实测**不可靠**，用户已裁决
 * 全部禁用，宁可用降级形式也不要渲染失败或整条源码：
 *
 * 1. `cases` / `dcases` / `rcases` / `array` / `vmatrix` / 裸 `matrix` 环境；
 * 2. `\left` / `\right` 全系定界符（含 `\left.`、`\left|` 等）与 `\big` 系；
 * 3. 「左侧大括号」的分段效果必须拆成多个独立公式块 + 中文条件说明（提示词层约束）。
 *
 * 保留面（实测稳定）：行内 `$...$`、`pmatrix` / `bmatrix` 矩阵、
 * 简单 `aligned` 推导、不含大型定界符的常规命令。
 *
 * ## 转换策略（宁可视觉近似，不可渲染失败）
 *
 * - `\left(` → `(`、`\left\{` → `\{`、`\left.` → 删除……（内容全保留）；
 * - `\big` 系大小命令直接删除（普通定界符视觉近似）；
 * - `cases` → `aligned`（`&` 与 `\\` 结构完全兼容）；
 * - `array` → `matrix`（丢列格式参数；裸 matrix 失败则交给 pmatrix——见下）；
 * - `vmatrix` / 裸 `matrix` → `pmatrix`（pmatrix 实测可渲染，视觉近似）。
 */
object LatexCompat {

    /** KaTeX 实测不可靠、必须转换的多行环境。 */
    private val BANNED_MULTILINE = setOf("cases", "dcases", "rcases", "array", "vmatrix", "Vmatrix")

    /** 裸 matrix：pmatrix 可渲染而它失败，转换到 pmatrix。 */
    private val MATRIX = setOf("matrix")

    /** \left / \right 的成对替换：源尾缀 → 目标开头。`.|` 等直接删除/降级。 */
    private val LEFT_RIGHT_MAP = mapOf(
        "(" to "(",
        ")" to ")",
        "[" to "[",
        "]" to "]",
        "\\{" to "\\{",
        "\\}" to "\\}",
        "|" to "|",
        // \left. / \right. 是「隐形定界符」，纯配平用途，删掉即可
        "." to ""
    )

    /** \big 系大小命令（含小写与首字母大写全组合）。 */
    private val SIZED = Regex("""\\(?:Bigg|bigg|Big|big)(?:l|r|m)?(?=[(\[\{|.\\]|\s)""")

    /** 匹配 \left 或 \right 后紧跟的定界符（\{ \} 等命令形式或标点形式）。 */
    private val LEFT_RIGHT = Regex("""\\(left|right)\s*((?:\\[{}|.\[\]()])|[^a-zA-Z\s])""")

    /** 匹配 \begin{env} 或 \end{env}（env 为环境名）。 */
    private val ENV = Regex("""\\(begin|end)\{([a-zA-Z*]+)\}""")

    /**
     * 黑名单降级转换。**幂等**：转换结果不再包含任何黑名单结构，
     * 重复调用不会有二次变化。
     */
    fun downgrade(latex: String): String {
        var out = latex
        // 1) \left X → 对应普通定界符（\left. → 删除）
        out = LEFT_RIGHT.replace(out) { m ->
            val delim = m.groupValues[2]
            LEFT_RIGHT_MAP[delim] ?: ""
        }
        // 2) \big 系大小命令删除（内容保留）
        out = SIZED.replace(out, "")
        // 3) 环境转换：循环两轮覆盖嵌套（如 array 里套 vmatrix 的极端情况）
        repeat(2) {
            out = ENV.replace(out) { m ->
                val kind = m.groupValues[1]
                val env = m.groupValues[2]
                val target = when {
                    kind == "begin" && env in BANNED_MULTILINE -> replacementEnv(env)
                    kind == "end" && env in BANNED_MULTILINE -> replacementEnv(env)
                    kind == "begin" && env in MATRIX -> "pmatrix"
                    kind == "end" && env in MATRIX -> "pmatrix"
                    else -> null
                } ?: return@replace m.value
                if (kind == "begin") "\\begin{$target}" else "\\end{$target}"
            }
        }
        return out
    }

    /** 禁用环境 → 可渲染环境的替换目标。 */
    private fun replacementEnv(env: String): String = when (env) {
        "cases", "dcases", "rcases" -> "aligned"
        "array" -> "matrix"
        "vmatrix", "Vmatrix" -> "pmatrix"
        else -> env
    }
}
