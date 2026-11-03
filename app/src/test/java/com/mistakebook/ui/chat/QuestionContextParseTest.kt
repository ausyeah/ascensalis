package com.mistakebook.ui.chat

import com.mistakebook.data.chat.ChatPrompts
import com.mistakebook.domain.ErrorReason
import com.mistakebook.domain.Option
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「题目卡片」的解析回归测试。
 *
 * 卡片把注入给模型的题目上下文拆成头部元信息 + 题目正文。这里钉三件事：
 *
 * 1. **元信息与正文的边界**——拆错位置，题干就会跑到芯片里去；
 * 2. **两句给模型的话必须被剔掉**——开头那句定向说明、结尾那句
 *    「请基于以上内容回答用户的问题。」，留在界面上用户会以为 App 在自言自语；
 * 3. **格式不符时退回「全是正文」**——不能把不认识的文本当元信息秀，
 *    也不能因为拆不出元信息就把题目正文吞掉。
 *
 * 判据全部取 [ChatPrompts.questionContext] 的真实输出：提示词改排版时，
 * 这里的断言会跟着红，而不是等用户在手机上看到一张空卡。
 */
class QuestionContextParseTest {

    @Test
    fun parsesRealQuestionContextIntoHeaderAndBody() {
        val text = ChatPrompts.questionContext(
            title = "泊松分布求二阶矩",
            // 题干里全是 LaTeX：$ 在 Kotlin 两种字符串里都是模板起始符，
            // 只能写成 \$（原始串也一样，它只关掉转义、不关掉模板）。
            stem = "随机变量 \$X\$ 的概率分布为 \$P\\{X=k\\}=\\frac{C}{k!}\$",
            options = emptyList(),
            answer = "2",
            analysis = "由方差公式 \$D(X)=E(X^2)-[E(X)]^2\$ 得 \$E(X^2)=2\$。",
            subjectName = "数学",
            knowledgePoints = listOf("概率分布归一化", "泊松分布", "期望与方差"),
            difficulty = 3,
            errorReasonLabel = ErrorReason.CONCEPT.label
        )
        val parsed = QuestionContext.parse(text)

        assertEquals("泊松分布求二阶矩", parsed.title)
        assertEquals("数学", parsed.subject)
        assertEquals("中等", parsed.difficulty)
        assertEquals("概念不清", parsed.errorReason)
        assertEquals("概率分布归一化、泊松分布、期望与方差", parsed.knowledge)
        assertTrue(parsed.hasMeta)

        // 正文只剩题目本体：题干 + 我记录的答案 + 我记录的解析
        assertTrue(parsed.body.startsWith("【题干】"))
        assertTrue(parsed.body.contains("我记录的答案"))
        assertTrue(parsed.body.contains("我记录的解析"))
        assertTrue(parsed.body.contains("E(X^2)"))
        // 给模型的两句话都不能留在界面上
        assertFalse(parsed.body.contains(ChatPrompts.HEAD_INTRO))
        assertFalse(parsed.body.contains(ChatPrompts.QUESTION_TAIL))
        assertFalse(parsed.title.contains(ChatPrompts.HEAD_INTRO))
        assertFalse(parsed.body.contains("请基于以上内容"))
    }

    @Test
    fun optionsAndAnswerLandInBodyNotInHeader() {
        val text = ChatPrompts.questionContext(
            title = "选择题",
            stem = "下面哪个是偶数？",
            options = listOf(Option(text = "2"), Option(text = "3")),
            answer = "A",
            analysis = "",
            subjectName = "数学",
            knowledgePoints = emptyList(),
            difficulty = 1,
            errorReasonLabel = "粗心遗漏"
        )

        val parsed = QuestionContext.parse(text)

        // 选项/答案属于正文，绝不能进芯片
        assertTrue(parsed.body.contains("【选项】"))
        assertTrue(parsed.body.contains("A. 2"))
        assertFalse(parsed.subject.contains("选项"))
        assertEquals("很简单", parsed.difficulty)
    }

    /** 元信息全缺（提示词改版、或换了个 system 消息）时整块退回正文。 */
    @Test
    fun unrecognizedFormatFallsBackToWholeTextAsBody() {
        val text = "这是一段没有既定排版的一般系统消息。\n\n第二段。"

        val parsed = QuestionContext.parse(text)

        assertFalse(parsed.hasMeta)
        assertEquals("", parsed.title)
        // 题目内容一个字都不能吞
        assertTrue(parsed.body.contains("这是一段没有既定排版的一般系统消息"))
        assertTrue(parsed.body.contains("第二段。"))
    }

    /** 连空行都没有时同样退回全文，且仍要剔掉结尾那句。 */
    @Test
    fun textWithoutBlankLineIsTreatedAsBodyOnly() {
        val text = "${ChatPrompts.HEAD_INTRO}\n题干就一行。\n${ChatPrompts.QUESTION_TAIL}"

        val parsed = QuestionContext.parse(text)

        assertFalse(parsed.hasMeta)
        assertEquals("题干就一行。", parsed.body)
    }

    @Test
    fun emptyContentYieldsEmptyCard() {
        val parsed = QuestionContext.parse("   \n  ")

        assertFalse(parsed.hasMeta)
        assertEquals("", parsed.body)
    }

    /**
     * 标题里带全角冒号不能被切坏。
     *
     * `substringBefore` 取第一个冒号，所以「标题：概率：泊松」的标题
     * 必须是「概率：泊松」而不是「标题」。这条是防手滑改成 lastIndexOf。
     */
    @Test
    fun valueKeepsColonsAfterTheFirstOne() {
        val text = "标题：概率：泊松\n学科：数学\n\n【题干】\n求 E(X)。"

        val parsed = QuestionContext.parse(text)

        assertEquals("概率：泊松", parsed.title)
        assertEquals("数学", parsed.subject)
        assertEquals("【题干】\n求 E(X)。", parsed.body)
    }

    /**
     * 结尾那句话必须由 [ChatPrompts.QUESTION_TAIL] 认，不能另抄一份字面量。
     *
     * 两边各写各的后果：提示词改了结尾句，卡片就开始把它当题目内容展示。
     */
    @Test
    fun tailIsStrippedByTheSharedConstantNotACopy() {
        val text = "标题：t\n学科：数学\n\n【题干】\nstem\n${ChatPrompts.QUESTION_TAIL}"

        val parsed = QuestionContext.parse(text)

        assertEquals("【题干】\nstem", parsed.body)
    }
}
