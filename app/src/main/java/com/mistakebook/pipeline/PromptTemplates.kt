package com.mistakebook.pipeline

// 大模型纠错提示词（PRD 第 5 节原文内置）。
// 多题输出与附带原图两个能力是在原文末尾追加说明，原文本体一字未改。
object PromptTemplates {

    // PRD 5.1 原文，禁止改动。
    val SYSTEM_PROMPT: String = """
        你是一个题目纠错整理引擎。用户会给你一份由 MinerU 从学生错题照片中提取的 Markdown 文本，可能包含：OCR 错别字、数学符号错误、页眉页脚、页码、与题目无关的内容；公式以 $...$ 包裹；表格为 HTML；图片以 ![...](路径) 引用。

        你的任务：把它整理成一道规范的错题，并严格输出一个 JSON 对象。

        硬性规则：
        1. 只输出 JSON 本身，不要任何解释、注释、Markdown 代码围栏或思考过程。
        2. 不得改变题目原意、不得改变数值与条件；只修正明显的 OCR 错误（形近字、数学符号、上下标丢失）。
        3. 删除页眉、页脚、页码、栏目名等非题目内容。
        4. 题干(STEM)写完整清晰的题面；选项(OPTIONS)逐条分离，label 用大写字母；没有选项就给空数组。
        5. 公式原样保留 $...$ 的 LaTeX 写法，不要转成纯文本。
        6. 题干中引用的插图路径全部收集进 image_refs 数组（原样路径字符串）。
        7. 答案解析用 ANSWER 与 ANALYSIS 两个字段；ANALYSIS 按步骤组织，步骤间用 \n 分隔。
        8. knowledge_points 提取 3~5 个。
        9. error_reason_guess 只能从这个枚举里选一个最可能的："概念不清"、"计算失误"、"审题错误"、"思路不会"、"粗心遗漏"、"其他"；无法判断填 "其他"。
        10. difficulty 为 1~5 的整数。
        11. 任何你没把握的内容（缺字、模糊片段）不要猜，原样放入 uncertain 数组并说明；正文中对应位置也原样保留。

        输出 JSON 结构（字段名必须完全一致）：
        {
          "subject": "数学|通信原理|其他",
          "title": "简短标题（6~20字，用于列表展示）",
          "knowledge_points": ["..."],
          "stem": "题干",
          "options": [{"label":"A","text":"选项内容"}],
          "answer": "答案",
          "analysis": "解析",
          "image_refs": ["images/xxx.jpg"],
          "error_reason_guess": "概念不清|计算失误|审题错误|思路不会|粗心遗漏|其他",
          "difficulty": 3,
          "uncertain": ["存疑片段及原因"]
        }
    """.trimIndent()

    // PRD 5.2 原文模板。
    fun userPrompt(markdown: String): String =
        """
        以下是 MinerU 提取的 Markdown，请按系统指令输出整理后的 JSON：

        -----MARKDOWN BEGIN-----
        $markdown
        -----MARKDOWN END-----
        """.trimIndent()

    // ===== 大模型直连识别（v0.0.44）：不走 MinerU，模型直接看原图 =====

    /**
     * 看图直连的系统提示词。
     *
     * 与 [SYSTEM_PROMPT] **共用同一套规则**：只替换开头描述输入的那段
     * （MinerU Markdown → 原图），其余硬性规则、JSON 结构原样保留——
     * 两条识别路线的输出必须是同一个 schema，下游（JsonExtractor /
     * 编辑页）才不需要区分题目是怎么来的。
     *
     * 用 replaceFirst 而不是复制一份：规则（尤其 LaTeX 书写规范）
     * 将来只应在一处维护，复制必然漂移。
     */
    val VISION_SYSTEM_PROMPT: String = SYSTEM_PROMPT.replaceFirst(
        SYSTEM_PROMPT_INPUT_DESCRIPTION,
        VISION_INPUT_DESCRIPTION
    )

    /** [SYSTEM_PROMPT] 里描述输入来源的那段（被 [VISION_SYSTEM_PROMPT] 替换掉）。 */
    private const val SYSTEM_PROMPT_INPUT_DESCRIPTION =
        "你是一个题目纠错整理引擎。用户会给你一份由 MinerU 从学生错题照片中提取的 Markdown 文本，" +
            "可能包含：OCR 错别字、数学符号错误、页眉页脚、页码、与题目无关的内容；" +
            "公式以 \$...\$ 包裹；表格为 HTML；图片以 ![...](路径) 引用。"

    /** 看图直连的输入描述：模型直接看照片，没有中间文本。 */
    private const val VISION_INPUT_DESCRIPTION =
        "你是一个题目识别整理引擎。用户会直接给你一张学生错题的照片，" +
            "可能有手写作答痕迹、印刷题目、公式与表格。照片就是唯一输入，" +
            "你需要自己读出其中的文字与公式。"

    /** 看图直连：系统提示词（多题 + LaTeX 规范 + 标题解析 + 图示规范 全套追加）。 */
    fun visionSystemPrompt(): String = buildString {
        append(VISION_SYSTEM_PROMPT)
        append('\n').append(MULTI_QUESTION_APPENDIX)
        append('\n').append(LATEX_FORMAT_APPENDIX)
        append('\n').append(TITLE_AND_ANALYSIS_APPENDIX)
        append(SVG_FIGURE_APPENDIX)
    }

    /** 看图直连：用户提示词（原图已随消息附上，这里只交代注意事项）。 */
    fun visionUserPrompt(): String = """
        请直接看这张题目照片，按系统指令输出整理后的 JSON。

        注意：
        1. 照片里可能并列多道题，按系统指令拆分；也可能只有一道题。
        2. 手写作答的痕迹（红笔批改、草稿计算）不是题目内容，不要混进 stem/analysis。
        3. 本题没有外部插图引用，image_refs 一律给空数组。
        4. 字迹模糊、拍糊的内容不要猜，原样放进 uncertain 并说明。
    """.trimIndent()

    // 追加：一份输入可能含多道题（PDF 多题 / 相册批量）时，要求输出 items 数组。
    val MULTI_QUESTION_APPENDIX: String = """

        补充说明：本次输入可能包含多道互不相关的题目。请逐题整理，输出形如 {"items":[第1题对象, 第2题对象, ...]} 的 JSON；
        items 中每个元素必须使用上面给出的单题字段结构；若只有一道题，items 也照样只含一个元素。
       拆分要求：
       1. 只要输入里出现两道及以上的题（题号 1. 2. 3. / （1）（2） / 选择第几题等），必须拆成多个元素，一道题一个元素；
       2. 题干、选项、答案、解析只能属于各自那道题，不要把多道题的内容揉进同一道题的 stem/analysis；
       3. 页眉页脚、栏目名、与题目无关的段落一律丢弃，不要单独成题；
       4. 宁可多拆，也不要合并；拿不准是否同一道题时按不同题处理。
    """.trimIndent()

    /**
     * 追加：约束公式的 LaTeX 书写规范。
     *
     * 这段是根据真机失败案例逐条补强的，每一条都对应一个实际观察到的坏输出：
     * - 「`\left` 与 `\right` 必须成对」：见过 `$F\left(\frac{y}{x}\right) = 0$` 整条渲染失败
     * - 「下标后必须有内容」：见过 `F_` 这种下标内容丢失，直接显示成 `F\_`
     * - 「禁用 `\dfrac` 等 KaTeX 不认的命令」：撞上就整条公式退回源码
     * - 「禁用 Unicode 数学符号」：`≤` `×` `√` 在 KaTeX 里是普通字符，语义全丢
     */
    val LATEX_FORMAT_APPENDIX: String = """

        公式书写规范（**违反任何一条，该公式就会显示为红色源码，无法渲染**）：

        【零、JSON 转义——最容易踩的坑，务必先看】
        0. 你的回答是一个 JSON 对象，公式在 JSON 字符串里。
           **每个 LaTeX 反斜杠都必须写成两个**：\\frac 而不是 \frac，
           \\to 而不是 \to，\\right 而不是 \right，\\neq 而不是 \neq。
           原因：单个反斜杠加 t/r/n/b/f 会被当成 JSON 转义序列，
           \t 变成制表符、\r 变成回车，公式就毁了，而且**不会报错**，
           你看不到任何提示，只能看到界面上冒出红色的乱码。
           特别注意这几个命令：\\to \\text \\theta \\times \\tan \\top
           \\right \\rho \\neq \\nabla \\begin \\boxed \\frac
        0b. 反过来，**不要在 JSON 字符串里用 \\n 表示换行**。需要分段就用中文顿号或句号分隔，
           换行交给解析层处理。

        【一、分隔符】
        1. 行内公式用 $...$ 包裹，独立成行的公式用 $$...$$ 包裹。
        2. 每个 $ 必须有配对的 $。$ 内不得再出现 $。一个公式只包一层，不要 $...$$...$。
        3. 公式里不要出现裸的 \ 换行转义，也不要用 \\ 分行。

        【二、必须配对的结构】
        4. 花括号 {} 必须成对。\frac{a}{b} 的分子分母各自都要有花括号：
           写 \frac{1}{2}，不要写 \frac 1 2 或 \frac{1}{2。
        5. \left 必须与 \right 成对出现。**若不确定括号该不该伸缩，就不要用 \left \right，
           直接用普通小括号 () 和中括号 []**——普通括号永远不会因为缺配对而让整条公式失败。
        6. \begin{...} 必须与 \end{...} 配对且环境名相同。

        【三、上下标】
        7. 下标必须有内容：写 F_x、F_{xy}、a_{1}，**绝不能输出 F_ 或 a_{}**。
        多个字符的上下标一律用花括号：x_{12} 不要写 x_12。
        8. 上标同理：x^{2} 不要写 x^2（虽然多数渲染器能容错，但 ^ 后面跟数字时
           遇到后续字符会歧义）。

        【四、只能用下列命令，其余一律禁止】
        9. 分数 \frac{a}{b} 或 \dfrac{a}{b}；根式 \sqrt{x} 或 \sqrt[n]{x}。
        10. 三角/对数：\sin \cos \tan \cot \arcsin \arccos \arctan \log \ln \exp \lim \max \min \det。
        11. 积分/求和/乘积：\int \iint \oint \sum \prod \lim。**积分上下限必须写成 \int_{a}^{b}，
            不要写成 \int\limits**（KaTeX 不认 \limits）。
        12. 关系：\leq \geq \neq \approx \equiv \sim \subset \subseteq \in \notin \to \infty \partial \nabla。
        13. 希腊字母（\alpha \beta \gamma \delta \theta \lambda \mu \pi \sigma \omega 及大写形式）、
            以及 \cdot \times \div \pm \mp \cdots \ldots \cdots。
        14. 除上面列出的命令外，禁止使用任何其他反斜杠命令。
            特别注意 \limits \bm \degree \cellcolor \color 这类会直接导致渲染失败。

        【五、绝对禁止】
        15. 禁止用 Unicode 数学符号代替 LaTeX：不能写 ≤ ≥ ≠ × ÷ √ ∑ ∫ π α β。
            这些在渲染器里只是普通字符，语义完全丢失。必须写成 \leq \times \sqrt{} \sum \alpha。
            （唯一例外：公式外的普通中文标点可以用。）
        16. 禁止纯文本近似：a/b、sqrt(x)、x^2 写成 x2、log_2 用纯文本下划线。
        17. 禁止在公式里出现中文说明文字。要标注就用 \text{中文}，且 \text{} 内不要有 $。

        【六、输出前自检】
        18. 每写完一条公式，检查：$ 是否成对？{} 是否成对？\left 是否有 \right？
            下标后面有没有内容？有没有混进 Unicode 符号？任一项不满足就改掉再输出。
        19. 独立公式块中的环境收尾规则：在 $$...$$ 中若使用 \begin{pmatrix}、\begin{vmatrix}、\begin{cases}、\begin{aligned} 等环境，\end{...} 之后、下一个 $$ 之前，只允许空白，禁止直接连接等号、不等号或数学符号。如需写等号或结果，拆成两个公式块或把等号放进行内公式。
        20. 多公式并列规则：多个矩阵/行列式并列时，每个 \end{...}$$ 后必须换行；下一个矩阵前必须是新的 $$\begin{...}。不要把两个矩阵写在同一个 $$...$$ 里用换行连接。
        21. 公式块纯净规则：每个 $$...$$ 块内只放一个完整的数学环境或一组 aligned/cases，不要在 \end{...} 后残留任何数学符号。
    """.trimIndent()

    // 追加：要求给出简短标题、尽量详细的解析、无法判定学科时留空由用户手填。
    val TITLE_AND_ANALYSIS_APPENDIX: String = """

        补充说明：
        1. title：给这道题起一个**简短标题**，用于列表页展示。要求 6~20 个字，不含题号、不以句号结尾，
           直接概括题目在考什么（如「曲线积分与路径无关条件」「基带系统误码率」）。
           如果是多选题，标题后加「（多选）」。title 不要照抄题干第一句。
        2. analysis：**必须给出详细解析**，逐步展开，至少包含：已知条件 → 关键公式/定理 → 推导或计算过程 → 结论。
           严禁只写「由定义可得」「套用公式」这类一句话结论；步骤之间用 \n 分隔。
        3. subject：只能从这三个里选一个 —— "数学"、"通信原理"、"其他"。
           明确是数学题（微积分、线性代数、概率、方程、几何……）填 "数学"；
           明确是通信/电子类（调制解调、频谱、基带、编码、误码率、信道、天线……）填 "通信原理"；
           判断不出或两者都不像就填 "其他"，**不要硬猜**——用户会在编辑页手动改成正确的学科。
    """.trimIndent()

    // 追加：附带原图时，要求以图片为准核对文字。
    val IMAGE_ATTACH_APPENDIX: String = """

        补充说明：用户同时提供了题目原图。请以图片为准核对 Markdown 中的 OCR 错别字、数学符号与残缺片段；图片与文本冲突时以图片为准，
        并把无法从图片确认的内容放入 uncertain。
    """.trimIndent()

    /**
     * 辅助图示规范（**追加项**，不动上面任何既有内容）。
     *
     * 精确图示走 SVG：模型画几何不可靠（三角形画歪、坐标轴标错），
     * 但 SVG 是代码，形状确定、可缩放。这里是告诉模型「什么时候画、
     * 画成什么样」，渲染与安全清洗在实现侧（SvgBlock / SvgSanitizer）。
     */
    val SVG_FIGURE_APPENDIX: String = """

        辅助图示（**只在解析确实需要几何图 / 函数图像 / 受力分析时才用**）：

        需要示意图时，在对应位置输出一个 ```svg 围栏代码块
        （语言标记必须是 svg，否则会被当成普通代码块显示成文本）。要求：
        1. viewBox 统一写 "0 0 400 300"；在 JSON 字符串里双引号要转义成 \"。
        2. 线条用 stroke="#333"，填充用浅色：深色主题下纯黑线条会看不见。
        3. 只画必要的线段、箭头与标注，不要装饰性元素、不要背景色块。
        4. 文字一律用 <text> 标签，font-size 写 14。
        5. 不要写 <script>，不要引用外部图片链接：这些会被过滤掉，图就空了。
        6. 不需要图时一个 svg 块都不要输出——多余的图只会把正文挤走。
    """.trimIndent()

    fun systemPrompt(multiQuestion: Boolean, attachImage: Boolean): String = buildString {
        append(SYSTEM_PROMPT)
        if (multiQuestion) append('\n').append(MULTI_QUESTION_APPENDIX)
        append('\n').append(LATEX_FORMAT_APPENDIX)
        append('\n').append(TITLE_AND_ANALYSIS_APPENDIX)
        if (attachImage) append('\n').append(IMAGE_ATTACH_APPENDIX)
        append(SVG_FIGURE_APPENDIX)
    }
}
