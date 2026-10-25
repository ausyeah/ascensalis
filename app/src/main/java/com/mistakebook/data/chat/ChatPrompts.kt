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
     * 手写过程批改指令（T13）。
     *
     * 注入位置：题目上下文之后（[questionContext] 拼好的同一段），
     * 仅批改模式发送时附加——不开批改开关的普通对话完全看不到它，
     * 行为与 v0.0.33 逐字一致。
     *
     * 原文照抄产品需求：四节输出（步骤判定/首个错误步骤/正确思路/针对性建议），
     * 并明确「OCR 文本可能有识别错误」的处置——遇到疑似识别错误标注
     * 「（疑似识别错误）」并按最可能意图批改，不因此拒批。
     */
    val GRADING_INSTRUCTION: String = """
【解题过程批改模式】
下面用户提供的是他自己手写的解题过程（照片或 OCR 文本）。请严格按照本题的题干、标准答案与解析，逐步批改他的过程：

1. 逐步判定：把他的过程拆成步骤，每一步给出判定（正确 / 错误 / 无法判断）和一句话理由。只依据我提供的题干、答案、解析和他写的内容判断，不许臆造他没写的步骤。
2. 首个错误步骤：明确指出从第几步开始错、错在哪个具体位置（引用他的原文）。
3. 正确思路：从首个错误步骤起，给出正确的做法（按步骤写）。
4. 针对性建议：针对他的错误类型给一条具体的、可执行的练习建议。

输出用中文 Markdown，四个小节标题分别为：步骤判定 / 首个错误步骤 / 正确思路 / 针对性建议。OCR 文本可能有识别错误：遇到明显不通顺或疑似识别错误的地方，标注「（疑似识别错误）」并按最可能的意图批改，不要因此拒批。
    """.trimIndent()


    /**
     * 角色设定。
     *
     * 两条硬要求：
     * 1. 公式用 `$…$` / `$$…$$`——界面靠 [com.mistakebook.ui.common.RichText] 的
     *    KaTeX 渲染，模型若输出裸 `x^2`，用户看到的是源码而不是公式。
     * 2. **先指出记录里的答案或解析是否自洽**。这个 App 收录的错题里，
     *    OCR/识别环节抄错的题真实存在（v0.0.5 用户实测：`1/(x²-1)` 存成了 `1/(e^x-1)`，
     *    渲染完全正常、但答案是错的）。不主动核对，用户就会把错的当对的记下去。
     */
    val SYSTEM: String = """
        你是一位耐心的中学/大学理科辅导老师，正在一个「错题本」应用里回答用户关于某道错题的问题。

        输出要求：
        1. 用中文回答，Markdown 排版，结构清晰；必要时用小标题和列表分步。
        2. 所有数学公式必须用 LaTeX：行内公式用一对美元符号包起来，独立成行的公式用两对美元符号包起来。
           不要输出裸 LaTeX 源码。
           **美元符号与公式内容必须在同一行**，不要写成开头的标记、换行、再写内容、换行、再写结尾标记。
           多步推导请写成一行（用两个反斜杠分隔两行），不要用 \begin{aligned}。
           **表格单元格内禁止使用 LaTeX**，一律写纯文本或 Unicode 符号（如 α、≤、√）。
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
