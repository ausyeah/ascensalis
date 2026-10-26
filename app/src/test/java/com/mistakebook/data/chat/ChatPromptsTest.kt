package com.mistakebook.data.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatPrompts.SYSTEM] 公式规范回归测试。
 *
 * 规范里的允许/禁止清单不是风格建议，是对着仓库打包的 katex.min.js
 * 实测出来的可渲染边界（实测记录见 LatexSanitizerTest 的注释）。
 * 这里只断言关键条款存在，防止后续改提示词时无意删掉约束。
 */
class ChatPromptsTest {

    @Test
    fun systemPromptConstrainsFormulasToRenderableSet() {
        val s = ChatPrompts.SYSTEM
        // 多行推导的正道写法：aligned 环境内才允许 & 与换行
        assertTrue(s.contains("\\begin{aligned}"))
        // 实测不支持的命令/环境必须点名禁止
        assertTrue(s.contains("\\ce"))
        assertTrue(s.contains("\\label"))
        assertTrue(s.contains("\\tag"))
        assertTrue(s.contains("multline"))
        assertTrue(s.contains("eqnarray"))
        assertTrue(s.contains("flalign"))
        assertTrue(s.contains("tabular"))
        // 中文标注的正道写法
        assertTrue(s.contains("\\text{…}"))
        // 表格单元格内已解禁行内公式（渲染器支持），块级公式仍禁止
        assertTrue(s.contains("表格单元格内**可以**写行内公式"))
        assertTrue(s.contains("公式块（$$…$$）不要放进表格"))
        // 实测支持的命令要明确说「可以用」，避免模型无谓回避
        assertTrue(s.contains("\\boxed"))
        assertTrue(s.contains("\\Big"))
        assertTrue(s.contains("\\dfrac"))
        // 花括号定界符必须写全
        assertTrue(s.contains("\\left\\{"))
        // 公式失败的后果要说清楚，让模型明白这不是建议
        assertTrue(s.contains("红色源码"))
    }

    @Test
    fun questionContextRendersCoreFields() {
        val text = ChatPrompts.questionContext(
            title = "标题",
            stem = "题干内容",
            options = emptyList(),
            answer = "A",
            analysis = "解析内容",
            subjectName = "数学",
            knowledgePoints = listOf("数列"),
            difficulty = 3,
            errorReasonName = "概念混淆"
        )
        assertTrue(text.contains("题干内容"))
        assertTrue(text.contains("我记录的答案"))
        assertTrue(text.contains("解析内容"))
        assertTrue(text.contains("数列"))
        assertTrue(text.contains("概念混淆"))
        assertFalse(text.contains("【选项】"))
    }
}
