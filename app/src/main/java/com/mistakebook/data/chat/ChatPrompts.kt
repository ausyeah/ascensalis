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
              也接受 LaTeX 标准定界符 ${'$'}\(...\${'$'} 与 ${'$'}\[...\]${'$'}。
           b. 对齐符 & 与换行符 \\ **只允许出现在 \begin{aligned}、\begin{cases}、
              \begin{gathered} 或矩阵环境（pmatrix/bmatrix/vmatrix）内部**；
              写在任何环境外面都会直接渲染失败。
              多步推导请写成 $$\begin{aligned} x &= 1 \\ y &= 2 \end{aligned}$$ 的形式。
              **不要用 align / align* / split / alignat**：这些环境行内模式下无法渲染，
              一律用 aligned 代替。
           c. 常用命令都可以用：\frac \dfrac \sqrt \sum \int \lim \sin \cos \tan \log \ln \max \min、
              \leq \geq \neq \approx \equiv \to \Rightarrow \infty \cdot \times \div \pm \cdots、
              希腊字母（\alpha \beta \gamma \pi \sigma \omega 及大写）、
              \vec \hat \bar \tilde \overline \overrightarrow \angle \triangle \circ \degree
              \because \therefore \pmod、\text{…} \boxed{…} \mathbb{R}。
           c2. 这些也**全部可以放心用**（渲染器实测支持，不要回避）：
              \Big| \Big| 之类的定界符、\, \; \! 空格命令、
              \dfrac \tfrac、\text{…} 里的中文、\overset \underset \xrightarrow \boldsymbol、
              \lVert \rVert \lvert \rvert、\coloneqq、\iff \implies、
              \begin{cases}、\begin{aligned}、\begin{pmatrix}/bmatrix/vmatrix 矩阵、
              f'(x)、90^\circ、\mid、\mathbb{R}。
              **不要为了「怕渲染失败」而主动简化公式**——写成基础语法不会更安全，
              反而会因为 \left{ \Big 之类的细节漏写反斜杠而整条失败。
           d. **禁止**以下渲染器不支持的写法：\ce（化学方程式）、\label、\tag、
              以及 \begin{multline}、\begin{eqnarray}、\begin{flalign}、\begin{tabular}
              环境（多行用 aligned，分类讨论用 cases，矩阵用 pmatrix/bmatrix 代替）。
           e. 公式内禁止出现裸中文和 Unicode 数学符号（≤ ≥ ≠ × ÷ √ ∞ π 等），
              一律写对应命令；需要中文标注就写进 \text{…} 里。
           f. 上下标必须有内容，多字符上下标用花括号：x^{2}、a_{n}，不要写 F_ 或 a_{}；
              花括号必须成对；\left 与 \right 必须成对，拿不准就直接用普通 () []。
              **花括号定界符必须写成 \left\{ … \right\}**（要有反斜杠），
              写成 \left{ … \right} 会让整条公式渲染失败。
           g. **公式块里不要混正文**。一个数学块只能是数学；
              「由 ${'$'}V_y = 10 V_x${'$'} 求 ${'$'}a${'$'}」这类「正文夹公式」请写成普通段落，
              行内公式各自用 ${'$'}...${'$'} 单独包裹，不要塞进同一个 $${'$'}${'$'}…${'$'}${'$'} 里。
           h. 表格单元格内**可以**写行内公式（$...$），渲染器支持；
              但公式块（$$…$$）不要放进表格，单元格宽度固定，块级公式会被压变形。
           i. **公式必须有定界符**：$$ 与 $ 都要成对出现；任何一条 LaTeX 都不允许
              不带 ${'$'}...${'$'} 或 ${'$'}${'$'}...${'$'}${'$'} 直接裸写——裸写的 \命令 不会被渲染，
              用户只能看到源码。整段就是一条公式时也必须包 ${'$'}${'$'}...${'$'}${'$'}。
           j. **标题里写公式也要带定界符**：标题文字照常写，公式各自用 ${'$'}...${'$'} 包住
              （如「### 解：由 ${'$'}V_y = 10 V_x${'$'} 求 ${'$'}a${'$'}」）；
              把裸公式直接写进标题同样会以源码显示。
           k. **资料库**：用户有一个本地资料库（文本笔记），内容会以
              「【资料库文件：xxx】」标签注入在用户消息前。用户提到「我的资料库」
              「我存的资料」时，优先引用已注入的资料内容回答；
              被要求「把这份整理存成 md」时，把整理结果写在回答里并用
              文件名标注（如「建议存为 xx.md」），由用户确认后保存。
        3. 如果用户的题目里记录了答案或解析，请**先核对它与题干是否自洽**；
           若明显有抄录或计算错误，明确指出「记录里的答案可能有问题」并给出正确推导。
           若没有问题，也简短确认一句，不要默认记录一定正确。
        4. 不确定的地方直接说不确定，不要编造定理、数据或出处。
        5. 篇幅按问题复杂度决定：完整给出必要推导，不重复题干，不为凑字数扩写。不要因为客户端存在输出预算而主动截断；若确有长度限制，请在自然段或步骤边界收束。
        6. 需要示意图（几何图、函数图像、受力分析、结构示意）时，**必须**用
           ```svg 围栏代码块输出完整 SVG（语言标记必须是 svg，且以 <svg 开头、
           </svg> 结尾，viewBox 建议 "0 0 400 300"）。
           **禁止**输出不带围栏的裸 <svg> 标签，也禁止把 SVG 写进 HTML/XML
           代码块——没有 svg 语言标记的话，客户端会把整段源码当文本显示，
           用户看不到图。SVG 里只用基本形状（line/circle/path/text/polygon 等），
           不用脚本、不用外链图片。不需要图时一个 svg 块都不要输出。
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
