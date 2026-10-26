package com.mistakebook.pipeline

import com.mistakebook.data.prefs.RecognitionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别路线决策纯函数单测（v0.0.49：四选项 + 自动回落链）。
 *
 * 覆盖 [RecognitionPlan.decide] 全部分支矩阵，以及
 * [PromptTemplates.VISION_SYSTEM_PROMPT] 的替换正确性——
 * 后者防的是 SYSTEM_PROMPT_INPUT_DESCRIPTION 目标串漂移：
 * 一旦原文里那段话被改动而常量没同步，replaceFirst 会静默不替换，
 * 看图提示词就会残留「由 MinerU 提取的 Markdown」的误导描述。
 */
class RecognitionPlanTest {

    // ---- decide：MINERU 模式 ----

    @Test
    fun `MINERU 模式永远走 Mineru 不看其它条件`() {
        // 没配 MinerU 也走 Mineru：执行层按老行为报 NO_KEY，不自动改道。
        assertEquals(
            RecognitionPlan.Mineru,
            RecognitionPlan.decide(RecognitionMode.MINERU, mineruConfigured = false, supportsVision = true)
        )
        assertEquals(
            RecognitionPlan.Mineru,
            RecognitionPlan.decide(RecognitionMode.MINERU, mineruConfigured = true, supportsVision = true)
        )
        assertEquals(
            RecognitionPlan.Mineru,
            RecognitionPlan.decide(RecognitionMode.MINERU, mineruConfigured = false, supportsVision = false)
        )
    }

    // ---- decide：当前模型（CURRENT）模式 ----

    @Test
    fun `CURRENT 模式当前模型识图时直连`() {
        assertEquals(
            RecognitionPlan.LlmDirect,
            RecognitionPlan.decide(RecognitionMode.CURRENT, mineruConfigured = false, supportsVision = true)
        )
        // 配了 MinerU 也不走：用户明确要求跳过。
        assertEquals(
            RecognitionPlan.LlmDirect,
            RecognitionPlan.decide(RecognitionMode.CURRENT, mineruConfigured = true, supportsVision = true)
        )
    }

    @Test
    fun `CURRENT 模式当前模型不识图时报不可用且原因带模型名`() {
        val plan = RecognitionPlan.decide(
            RecognitionMode.CURRENT,
            mineruConfigured = true,
            supportsVision = false,
            modelName = "gpt-4o-mini"
        )
        assertTrue(plan is RecognitionPlan.Unavailable)
        val reason = (plan as RecognitionPlan.Unavailable).reason
        assertTrue(reason.contains("识图"))
        assertTrue(reason.contains("gpt-4o-mini"))
    }

    @Test
    fun `CURRENT 模式模型名为空时原因不出现空括号`() {
        val plan = RecognitionPlan.decide(
            RecognitionMode.CURRENT,
            mineruConfigured = false,
            supportsVision = false,
            modelName = ""
        )
        val reason = (plan as RecognitionPlan.Unavailable).reason
        assertFalse(reason.contains("（）"))
        assertFalse(reason.contains("()"))
    }

    // ---- decide：识图模型（VISION）模式 ----

    @Test
    fun `VISION 模式配了识图模型时走识图模型直连`() {
        assertEquals(
            RecognitionPlan.VisionDirect,
            RecognitionPlan.decide(
                RecognitionMode.VISION,
                mineruConfigured = false,
                supportsVision = false,
                visionConfigured = true
            )
        )
    }

    @Test
    fun `VISION 模式没配识图模型时报不可用且提到识图模型`() {
        val plan = RecognitionPlan.decide(
            RecognitionMode.VISION,
            mineruConfigured = true,
            supportsVision = true,
            visionConfigured = false
        )
        assertTrue(plan is RecognitionPlan.Unavailable)
        val reason = (plan as RecognitionPlan.Unavailable).reason
        assertTrue(reason.contains("识图模型"))
    }

    // ---- decide：AUTO 模式（自动回落链） ----

    @Test
    fun `AUTO 模式三条路按 MinerU 到当前模型到识图模型排序`() {
        val plan = RecognitionPlan.decide(
            RecognitionMode.AUTO,
            mineruConfigured = true,
            supportsVision = true,
            visionConfigured = true
        )
        assertTrue(plan is RecognitionPlan.Chain)
        assertEquals(
            listOf(
                RecognitionPlan.Route.MINERU,
                RecognitionPlan.Route.CURRENT_LLM,
                RecognitionPlan.Route.VISION_LLM
            ),
            (plan as RecognitionPlan.Chain).routes
        )
    }

    @Test
    fun `AUTO 模式没配 MinerU 时链条从当前模型开始`() {
        val plan = RecognitionPlan.decide(
            RecognitionMode.AUTO,
            mineruConfigured = false,
            supportsVision = true,
            visionConfigured = true
        )
        assertEquals(
            listOf(RecognitionPlan.Route.CURRENT_LLM, RecognitionPlan.Route.VISION_LLM),
            (plan as RecognitionPlan.Chain).routes
        )
    }

    @Test
    fun `AUTO 模式当前模型不识图时链条里没有当前模型`() {
        val plan = RecognitionPlan.decide(
            RecognitionMode.AUTO,
            mineruConfigured = true,
            supportsVision = false,
            visionConfigured = true
        )
        assertEquals(
            listOf(RecognitionPlan.Route.MINERU, RecognitionPlan.Route.VISION_LLM),
            (plan as RecognitionPlan.Chain).routes
        )
    }

    @Test
    fun `AUTO 模式只有 MinerU 可用时链条只有 MinerU`() {
        val plan = RecognitionPlan.decide(
            RecognitionMode.AUTO,
            mineruConfigured = true,
            supportsVision = false,
            visionConfigured = false
        )
        assertEquals(
            listOf(RecognitionPlan.Route.MINERU),
            (plan as RecognitionPlan.Chain).routes
        )
    }

    @Test
    fun `AUTO 模式三条路都不可用时原因都说清楚`() {
        val plan = RecognitionPlan.decide(
            RecognitionMode.AUTO,
            mineruConfigured = false,
            supportsVision = false,
            visionConfigured = false,
            modelName = "text-only-model"
        )
        assertTrue(plan is RecognitionPlan.Unavailable)
        val reason = (plan as RecognitionPlan.Unavailable).reason
        // 三条路都要点到：MinerU 没配 Key + 模型不识图 + 没配识图模型。
        assertTrue(reason.contains("MinerU"))
        assertTrue(reason.contains("识图"))
        assertTrue(reason.contains("text-only-model"))
    }

    // ---- RecognitionMode 键名兼容 ----

    @Test
    fun `旧键名 llm 映射到 CURRENT 且未知键名回落到 AUTO`() {
        assertEquals(RecognitionMode.CURRENT, RecognitionMode.fromKey("llm"))
        assertEquals(RecognitionMode.CURRENT, RecognitionMode.fromKey("current"))
        assertEquals(RecognitionMode.VISION, RecognitionMode.fromKey("vision"))
        assertEquals(RecognitionMode.AUTO, RecognitionMode.fromKey("auto"))
        assertEquals(RecognitionMode.AUTO, RecognitionMode.fromKey("something-new"))
        assertEquals(RecognitionMode.AUTO, RecognitionMode.fromKey(null))
    }

    // ---- PromptTemplates：看图系统提示词 ----

    @Test
    fun `VISION_SYSTEM_PROMPT 替换了输入来源描述`() {
        // 原文描述 MinerU 文本，看图路线不该残留。
        assertTrue(PromptTemplates.SYSTEM_PROMPT.contains("MinerU"))
        assertFalse(PromptTemplates.VISION_SYSTEM_PROMPT.contains("MinerU"))
        // 换成看原图的描述。
        assertTrue(PromptTemplates.VISION_SYSTEM_PROMPT.contains("照片"))
        assertNotEquals(PromptTemplates.SYSTEM_PROMPT, PromptTemplates.VISION_SYSTEM_PROMPT)
    }

    @Test
    fun `VISION_SYSTEM_PROMPT 保留同一套硬性规则与 JSON 结构`() {
        // 两条路线共用规则：只换输入描述，规则集一字不差。
        // 用 JSON 结构里的关键字段与硬性规则编号做锚点。
        for (anchor in listOf("stem", "image_refs", "uncertain", "knowledge_points", "error_reason_guess")) {
            assertTrue(PromptTemplates.SYSTEM_PROMPT.contains(anchor))
            assertTrue(PromptTemplates.VISION_SYSTEM_PROMPT.contains(anchor))
        }
        // 规则条数一致（替换只动开头一段，不动正文）。
        val ruleCount = PromptTemplates.SYSTEM_PROMPT.lines().count { it.trimStart().firstOrNull()?.isDigit() == true }
        val visionRuleCount = PromptTemplates.VISION_SYSTEM_PROMPT.lines().count { it.trimStart().firstOrNull()?.isDigit() == true }
        assertEquals(ruleCount, visionRuleCount)
    }

    @Test
    fun `visionSystemPrompt 追加多题 LaTeX 标题三套附录`() {
        val prompt = PromptTemplates.visionSystemPrompt()
        assertTrue(prompt.startsWith(PromptTemplates.VISION_SYSTEM_PROMPT))
        assertTrue(prompt.contains(PromptTemplates.MULTI_QUESTION_APPENDIX))
        assertTrue(prompt.contains(PromptTemplates.LATEX_FORMAT_APPENDIX))
        assertTrue(prompt.contains(PromptTemplates.TITLE_AND_ANALYSIS_APPENDIX))
    }

    @Test
    fun `visionUserPrompt 交代手写痕迹不进题干且缺图给空数组`() {
        val prompt = PromptTemplates.visionUserPrompt()
        assertTrue(prompt.contains("image_refs"))
        assertTrue(prompt.contains("uncertain"))
        assertTrue(prompt.contains("手写"))
    }
}
