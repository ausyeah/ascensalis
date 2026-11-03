package com.mistakebook.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「题目上下文卡片」与对话入口图标的契约守护。
 *
 * Compose 的测量绘制在 JVM 单测里跑不起来，所以这里只钉**结构**——
 * 钉的都是这次改动的「一退就回到旧样子」的点：
 *
 * 1. 题目上下文必须走 [QuestionContextCard]，不能又变成一堵铺开的原文；
 * 2. 卡片默认收起（`mutableStateOf(false)`）——题目上下文是给模型的背景，
 *    整块铺开意味着每次进会话都要先滑过几十行才看到对话；
 * 3. 详情页的 AI 对话入口必须是对话气泡图标，不能退回魔法星星；
 * 4. 卡片上的文案全部走字符串资源（仓库规则：界面文案全部进 strings.xml）。
 *
 * 解析逻辑本身的可测行为在 [QuestionContextParseTest]。
 */
class QuestionContextCardContractTest {

    @Test
    fun `注入的题目上下文必须渲染成题目卡片`() {
        val src = readSource("app/src/main/java/com/mistakebook/ui/chat/ChatMessageBubble.kt")

        assertTrue(
            "injected 分支必须交给 QuestionContextCard，不能再把整块原文铺出来",
            src.contains("QuestionContextCard(content = message.content, mathRenderer = mathRenderer)")
        )
        assertTrue(
            "卡片要能拿到数学渲染器，否则题干里的 LaTeX 会显示成源码",
            src.contains("mathRenderer = mathRenderer")
        )
        assertFalse(
            "injected 分支不允许再直接 RichText 整块原文",
            src.contains("RichText(\n                text = message.content")
        )
    }

    @Test
    fun `题目卡片默认收起不霸屏`() {
        val src = readSource("app/src/main/java/com/mistakebook/ui/chat/QuestionContextCard.kt")

        assertTrue(
            "默认收起：题目上下文是给模型的背景，铺开会把对话挤到屏幕外",
            src.contains("mutableStateOf(false)")
        )
        assertTrue(
            "必须按内容记收起态，重新注入题目时卡片要回到收起",
            src.contains("remember(content) { mutableStateOf(false) }")
        )
        assertTrue(
            "正文必须走 RichText，题干里的公式照常渲染",
            src.contains("RichText(")
        )
        assertFalse(
            "卡片标题/芯片/知识点不允许硬编码中文",
            src.contains("\"数学\"") || src.contains("\"中等\"")
        )
    }

    @Test
    fun `详情页 AI 对话入口必须用对话气泡图标`() {
        val src = readSource("app/src/main/java/com/mistakebook/ui/detail/DetailScreen.kt")

        assertTrue(
            "入口图标必须是对话气泡（用户一眼认出「这是去聊天的」）",
            src.contains("Icons.Default.Chat,")
        )
        assertFalse(
            "不允许退回 AutoAwesome 魔法星星：它只表示「AI」这一个侧面",
            src.contains("Icons.Default.AutoAwesome")
        )
        assertTrue(
            "入口仍然要接 onOpenChat(question.id)，别把接线弄丢",
            src.contains("onOpenChat(question.id)")
        )
    }

    @Test
    fun `卡片文案必须走字符串资源`() {
        val strings = readSource("app/src/main/res/values/strings.xml")

        assertTrue(strings.contains("<string name=\"chat_context_header\">题目上下文</string>"))
        assertTrue(
            "展开/收起要有人话文案（同时当图标的 contentDescription）",
            strings.contains("<string name=\"chat_question_card_expand\">查看完整题目</string>") &&
                strings.contains("<string name=\"chat_question_card_collapse\">收起题目</string>")
        )
        assertTrue(
            "知识点行要带标签，否则那串顿号文本看不出是什么",
            strings.contains("<string name=\"chat_question_card_knowledge\">知识点：%1\$s</string>")
        )

        val card = readSource("app/src/main/java/com/mistakebook/ui/chat/QuestionContextCard.kt")
        assertTrue(card.contains("R.string.chat_question_card_expand"))
        assertTrue(card.contains("R.string.chat_question_card_collapse"))
        assertTrue(card.contains("R.string.chat_question_card_knowledge"))
    }

    private fun readSource(path: String): String {
        val candidates = listOf(
            File(path),
            File("../$path"),
            File(path.removePrefix("app/")),
            File("../" + path.removePrefix("app/"))
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalArgumentException("Cannot find file: $path among $candidates")
        return file.readText()
    }
}
