package com.mistakebook.data.chat

import com.mistakebook.net.llm.ToolRoundState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 两个新工具（issue #54 函数图像 / #55 对话存错题）的**接线**与**回声**约束。
 *
 * 两处最容易悄悄坏掉的地方：
 *
 * ① 工具注册与分发：JVM 上跑不了 ChatGenerationService（要 Android 运行时），
 *    所以按仓库惯例钉源码——注册必须在 tools 列表里、分发必须有分支。
 * ② **图不能回传给模型**：函数图像 SVG 有几十 KB，如果注入时也写进「本轮」
 *    缓冲，模型下一轮就会收到自己刚生成的整张 SVG（烧 token 且毫无意义）。
 *    这条用 [ToolRoundState] 的真实行为断言，不靠源码字符串。
 */
class ChatToolWiringTest {

    private val service = readSource("src/main/java/com/mistakebook/ui/chat/ChatGenerationService.kt")
    private val prompts = readSource("src/main/java/com/mistakebook/data/chat/ChatPrompts.kt")

    @Test
    fun `两个工具都注册进了每次请求的工具列表`() {
        assertTrue(
            "plot_function 必须始终提供（纯计算、不需要任何 Key）",
            service.contains("addAll(com.mistakebook.data.chat.PlotTools.all())")
        )
        assertTrue(
            "save_question 必须始终提供",
            service.contains("addAll(com.mistakebook.data.chat.SaveQuestionTools.all())")
        )
    }

    @Test
    fun `两个工具都有分发分支与 stage 文案`() {
        assertTrue(service.contains("PlotTools.PLOT_FUNCTION -> {"))
        assertTrue(service.contains("SaveQuestionTools.SAVE_QUESTION -> {"))
        assertTrue(service.contains("正在画函数图像…"))
        assertTrue(service.contains("正在存进错题本…"))
    }

    @Test
    fun `函数图像用 injectAnswer 注入正文，而不是塞进工具结果里`() {
        assertTrue(
            "图要由客户端直接插进回答（让模型转述几十 KB 的 SVG 必被截断）",
            service.contains("rounds.injectAnswer(")
        )
        assertTrue(
            "绘图成功要走 FunctionPlotter",
            service.contains("com.mistakebook.math.FunctionPlotter.plot(request)")
        )
    }

    @Test
    fun `注入的图只进跨轮累积，不回传给模型`() {
        val rounds = ToolRoundState()
        rounds.onDelta("先看清楚函数的形状：")
        rounds.injectAnswer("\n\n<svg>this is a big chart</svg>\n\n")

        assertTrue("跨轮累积（要给用户看、要落库）必须有图", rounds.answer.contains("<svg>"))
        assertEquals("先看清楚函数的形状：", rounds.roundAnswer)
        assertFalse(
            "本轮缓冲（会作为 assistant 回声回传给模型）不能带图——否则每轮都烧几十 KB",
            rounds.roundAnswer.contains("<svg>")
        )
    }

    @Test
    fun `工具跑完后的快照必须重新取内容`() {
        assertTrue(
            "界面是整体替换语义：拿工具执行前那份内容发一次，会把刚注入的图从屏幕上抹掉",
            service.contains("content = joinThinkingAndAnswer(rounds.answer, rounds.thinking, rounds.thinkingMillis),\n                            sessionId = sessionId\n                        )\n                    )\n                    llmMessages +=")
        )
    }

    @Test
    fun `系统提示词把函数图像指向工具`() {
        assertTrue(
            "提示词必须写明函数图像调用 plot_function",
            prompts.contains("plot_function 工具")
        )
        assertTrue(
            "提示词必须写明存错题调用 save_question",
            prompts.contains("save_question 工具")
        )
        assertTrue(
            "并且要明确禁止手写 SVG 画函数曲线（那是会画错的旧路径）",
            prompts.contains("禁止**手写 SVG 去近似画函数曲线")
        )
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
