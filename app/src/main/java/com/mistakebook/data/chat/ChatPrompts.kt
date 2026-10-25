package com.mistakebook.data.chat

import com.mistakebook.domain.Option
import com.mistakebook.domain.labelFor

/**
 * 对话用的提示词与题目上下文渲染。
 *
 * 纯函数，无 Android 依赖，可单测。
 */
object ChatPrompts {

    /**
     * 角色设定。
     *
     * 两条硬要求：
     * 1. 公式必须落在渲染器的支持面内——界面靠 [com.mistakebook.ui.common.RichText] 的
     *    KaTeX 渲染，超出支持面的公式会整条退回红色源码。第 2 条规范不是风格建议，
     *    是逐条对着本仓库打包的 katex.min.js 实测出来的可渲染边界。
     * 2. **先指出记录里的答案或解析是否自洽**。这个 App 收录的错题里，
     *    OCR/识别环节抄错的题真实存在（v0.0.5 用户实测：`1/(x²-1)` 存成了 `1/(e^x-1)`，
     *    渲染完全正常、但答案是错的）。不主动核对，用户就会把错的当对的记下去。
     */
    val SYSTEM: String = """
        你是一位耐心的中学/大学理科辅导老师，正在一个「错题本」应用里回答用户关于某道错题的问题。

        输出要求：
        1. 用中文回答，Markdown 排版，结构清晰；必要时用小标题和列表分步。
        2. 公式规范（**违反任何一条，该公式会显示为红色源码，用户看不到公式**）：
           a. 行内公式用 ${'$'}...${'$'} 包裹，且美元符号与公式内容在同一行；
              独立公式块用 ${'$'}${'$'}...${'$'}${'$'} 包裹，块内可以换行。
           b. 对齐符 & 与换行符 \\ **只允许出现在 \begin{aligned}、\begin{cases} 或矩阵环境
              （pmatrix/bmatrix/vmatrix）内部**；写在任何环境外面都会直接渲染失败。
              多步推导请写成 $$\begin{aligned} x &= 1 \\ y &= 2 \end{aligned}$$ 的形式。
           c. 常用命令都可以用：\frac \dfrac \sqrt \sum \int \lim \sin \cos \tan \log \ln \max \min、
              \leq \geq \neq \approx \equiv \to \Rightarrow \infty \cdot \times \div \pm \cdots、
              希腊字母（\alpha \beta \gamma \pi \sigma \omega 及大写）、
              \vec \hat \bar \tilde \overline \overrightarrow \angle \triangle \circ \degree
              \because \therefore \pmod、\text{…} \boxed{…} \mathbb{R}。
           d. **禁止**以下渲染器不支持的写法：\ce（化学方程式）、\label、\tag，
              以及 \begin{multline}、\begin{eqnarray}、\begin{flalign}、\begin{tabular} 环境
              （多行用 aligned，分类讨论用 cases，矩阵用 pmatrix/bmatrix 代替）。
           e. 公式内禁止出现裸中文和 Unicode 数学符号（≤ ≥ ≠ × ÷ √ ∞ π 等），
              一律写对应命令；需要中文标注就写进 \text{…} 里。
           f. 上下标必须有内容，多字符上下标用花括号：x^{2}、a_{n}，不要写 F_ 或 a_{}；
              花括号必须成对；\left 与 \right 必须成对，拿不准就直接用普通 () []。
           g. **表格单元格内禁止使用 LaTeX**，一律写纯文本或 Unicode 符号（如 α、≤、√）。
              需要展示带公式的表格时，改成「先列一张不含公式的表，再用正文单独说明公式」；
              实在需要公式对照，就改用列表逐条写，不要放进表格里。
              原因：表格单元格宽度固定，公式放进去会被压缩到看不清甚至溢出错位。
        3. 如果用户的题目里记录了答案或解析，请**先核对它与题干是否自洽**；
           若明显有抄录或计算错误，明确指出「记录里的答案可能有问题」并给出正确推导。
           若没有问题，也简短确认一句，不要默认记录一定正确。
        4. 不确定的地方直接说不确定，不要编造定理、数据或出处。
        5. 篇幅按问题复杂度决定：完整给出必要推导，不重复题干，不为凑字数扩写。不要因为客户端存在输出预算而主动截断；若确有长度限制，请在自然段或步骤边界收束。
    """.trimIndent()

    /**
     * 渲染题目上下文。
     *
     * **只注入一次**（首条助手消息，`injected = true`），后续轮次靠 [com.mistakebook.domain.ChatRole.SYSTEM]
     * 消息一直带在上下文头部。
     *
     * @param options 题干选项；填空/解答题为空。
     * @param difficulty 1..5，转成中文档位。
     * @param errorReason 错因枚举名，转成中文。
     */
    fun questionContext(
        title: String,
        stem: String,
        options: List<Option>,
        answer: String,
        analysis: String,
        subjectName: String,
        knowledgePoints: List<String>,
        difficulty: Int,
        errorReasonName: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("以下是用户正在复习的一道错题：")
        if (title.isNotBlank()) sb.appendLine("标题：$title")
        if (subjectName.isNotBlank()) sb.appendLine("学科：$subjectName")
        if (knowledgePoints.isNotEmpty()) {
            sb.appendLine("知识点：${knowledgePoints.joinToString("、")}")
        }
        sb.appendLine("难度：${difficultyLabel(difficulty)}")
        if (errorReasonName.isNotBlank()) sb.appendLine("记录中的错因：$errorReasonName")
        sb.appendLine()
        sb.appendLine("【题干】")
        sb.appendLine(stem.ifBlank { "（空）" })
        if (options.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("【选项】")
            options.forEachIndexed { index, option ->
                sb.appendLine("${labelFor(index)}. ${option.text}")
            }
        }
        if (answer.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("【我记录的答案】")
            sb.appendLine(answer)
        }
        if (analysis.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("【我记录的解析】")
            sb.appendLine(analysis)
        }
        sb.appendLine()
        sb.append("请基于以上内容回答用户的问题。")
        return sb.toString().trim()
    }

    /** 1..5 -> 中文档位。越界时按 3 处理。 */
    fun difficultyLabel(difficulty: Int): String = when (difficulty) {
        1 -> "很简单"
        2 -> "简单"
        3 -> "中等"
        4 -> "较难"
        5 -> "很难"
        else -> "中等"
    }
}
