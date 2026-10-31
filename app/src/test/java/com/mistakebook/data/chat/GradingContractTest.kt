package com.mistakebook.data.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T13 手写过程批改的契约守护。
 *
 * 三条容易在后续改动里被悄悄破坏的性质，各用两类证据钉死：
 * - **指令原文**：四节标题与「疑似识别错误」处置必须在提示词里
 *   （运行时直接读 ChatPrompts 常量）；
 * - **注入点**：批改指令只能经 buildContext 的 gradingInstruction 参数
 *   进题目上下文，普通对话传空串（源码契约）；
 * - **分流存在**：发送前的图片校验与强制 OCR 必须在 ViewModel 里
 *   （源码契约，ViewModel 依赖链过重无法实例化）。
 */
class GradingContractTest {

    @Test
    fun `批改指令包含四节输出要求`() {
        val g = ChatPrompts.GRADING_INSTRUCTION
        // 四个小节标题原文照抄产品需求
        listOf(
            "步骤判定",
            "首个错误步骤",
            "正确思路",
            "针对性建议"
        ).forEach { section ->
            assertTrue("批改指令缺少小节：$section", g.contains(section))
        }
    }

    @Test
    fun `批改指令约束只依据给定材料且不臆造`() {
        val g = ChatPrompts.GRADING_INSTRUCTION
        assertTrue(g.contains("只依据我提供的题干、答案、解析和他写的内容判断"))
        assertTrue(g.contains("不许臆造他没写的步骤"))
    }

    @Test
    fun `批改指令要求标注疑似识别错误且不拒批`() {
        val g = ChatPrompts.GRADING_INSTRUCTION
        assertTrue(g.contains("（疑似识别错误）"))
        assertTrue(g.contains("不要因此拒批"))
    }

    @Test
    fun `批改指令经 buildContext 参数注入且普通对话可传空`() {
        val src = readSource("ChatRepository.kt", "src/main/java/com/mistakebook/data/repos/ChatRepository.kt")
        assertTrue(
            "buildContext 必须有 gradingInstruction 参数（默认空串 = 普通对话零注入）",
            src.contains("gradingInstruction: String = \"\"")
        )
        assertTrue(
            "批改指令必须拼进题目上下文所在的 system 消息",
            src.contains("append(gradingInstruction)")
        )
    }

    @Test
    fun `发送分流校验图片并强制 OCR`() {
        val src = readSource("ChatViewModel.kt", "src/main/java/com/mistakebook/ui/chat/ChatViewModel.kt")
        assertTrue(
            "批改模式无图片必须拒绝发送",
            src.contains("批改模式需要附上手写过程照片")
        )
        assertTrue(
            "批改模式必须对本轮图片跑 OCR（不论模型是否支持视觉）",
            src.contains("imageOcr.recognize(")
        )
        assertTrue(
            "OCR 失败必须拼降级小字而不是阻断发送",
            src.contains("手写过程未能 OCR 为文字")
        )
        assertTrue(
            "输入为空时默认问法必须存在（文案已外移到 strings.xml）",
            src.contains("R.string.chat_grading_default_message")
        )
        assertTrue(
            "runOnce 必须把批改指令带进 buildContext",
            src.contains("gradingInstruction = if (grading)")
        )
    }

    @Test
    fun `普通对话不注入任何批改内容`() {
        // gradingInstruction 传空串时 system 上下文与 v0.0.33 逐字一致：
        // 拼接逻辑里空串分支什么都不追加
        val src = readSource("ChatRepository.kt", "src/main/java/com/mistakebook/data/repos/ChatRepository.kt")
        assertTrue(src.contains("if (gradingInstruction.isNotBlank())"))
        assertFalse(
            "批改指令不得写死在题目上下文里（那样普通对话也会带上）",
            src.contains("GRADING_INSTRUCTION")
        )
    }

    private fun readSource(name: String, vararg paths: String): String {
        // 测试进程的工作目录是 app/ 模块目录（Gradle test task 的 cwd），
        // 但直接 gradlew 从项目根跑时又是根目录——三个候选都试。
        val candidates = paths.toList() +
            paths.map { "app/$it" } +
            paths.map { "../$it" }
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 $name，已尝试：${candidates.map { it }}")
        return file.readText()
    }
}
