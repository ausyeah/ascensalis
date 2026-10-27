package com.mistakebook.poster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 海报生图提示词单测。
 *
 * 防两件事：
 * 1. 背景模式提示词里**不能出现要写的文字**——生图模型看到中文
 *    就会自己画上去，画出来全是错字；
 * 2. 整图模式必须把每行文字都写进去，且明确要求「不要加别的文字」。
 */
class PosterPromptTest {

    private val content = PosterContent(
        dateText = "2026年10月26日",
        headline = "背单词 Day 12",
        stats = listOf(
            PosterStat("学过", "30", "词"),
            PosterStat("正确率", "85%", emphasis = true),
            PosterStat("目标", "20", "词")
        ),
        footerText = "登临 · 错题本"
    )

    @Test
    fun `背景模式提示词不含海报文字且要求无字`() {
        val prompt = PosterPrompt.build(content, PosterImageMode.BACKGROUND)
        assertFalse(prompt.contains("背单词 Day 12"))
        assertFalse(prompt.contains("2026年10月26日"))
        assertFalse(prompt.contains("85%"))
        assertTrue(prompt.contains("no text"))
    }

    @Test
    fun `整图模式提示词包含每行文字`() {
        val prompt = PosterPrompt.build(content, PosterImageMode.FULL)
        assertTrue(prompt.contains("2026年10月26日"))
        assertTrue(prompt.contains("背单词 Day 12"))
        assertTrue(prompt.contains("学过 30词"))
        assertTrue(prompt.contains("正确率 85%"))
        assertTrue(prompt.contains("目标 20词"))
        assertTrue(prompt.contains("登临 · 错题本"))
        assertTrue(prompt.contains("Do not add any other text"))
    }

    @Test
    fun `整图模式无单位的统计项不追加空格`() {
        val plain = PosterContent(
            dateText = "2026年10月26日",
            headline = "专注 Day 3",
            stats = listOf(PosterStat("正确率", "85%")),
            footerText = "登临 · 错题本"
        )
        val prompt = PosterPrompt.build(plain, PosterImageMode.FULL)
        assertTrue(prompt.contains("正确率 85%"))
        assertFalse(prompt.contains("正确率 85% "))
    }
}
