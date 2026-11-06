package com.mistakebook.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「反问用户」工具的解析与默认值（v0.0.128，issue #46）。
 *
 * 用户要的是电脑端 ask user question 的四条机制：单选/多选、推荐项、
 * 默认选项、超时跳过。解析层把模型的参数归一成卡片要的形状，
 * 任何一条归一错了，界面要么没预选、要么超时离谱。
 */
class AskUserToolsTest {

    private fun ask(
        question: String = "想练哪种题型？",
        options: String = """[{"label":"函数"},{"label":"数列"},{"label":"概率"}]""",
        extra: String = ""
    ): String = """{"question":"$question","options":$options$extra}"""

    @Test
    fun `基本解析`() {
        val parsed = AskUserTools.parse(ask())!!
        assertEquals("想练哪种题型？", parsed.question)
        assertEquals(listOf("函数", "数列", "概率"), parsed.options.map { it.label })
        assertFalse("默认单选", parsed.multiSelect)
        assertEquals("默认超时 90 秒", 90_000L, parsed.timeoutMs)
    }

    @Test
    fun `多选与补充说明`() {
        val parsed = AskUserTools.parse(
            ask(
                options = """[{"label":"函数","description":"含导数"},{"label":"数列"}]""",
                extra = ""","multi_select":true"""
            )
        )!!
        assertTrue(parsed.multiSelect)
        assertEquals("含导数", parsed.options.first().description)
    }

    @Test
    fun `推荐项成为默认预选`() {
        val parsed = AskUserTools.parse(ask(extra = ""","recommended":1"""))!!
        assertEquals(1, parsed.recommendedIndex)
        assertEquals(setOf(1), parsed.defaultIndexes)
    }

    @Test
    fun `显式默认项优先于推荐项`() {
        val parsed = AskUserTools.parse(
            ask(extra = ""","recommended":0,"defaults":[1,2]""")
        )!!
        assertEquals(setOf(1, 2), parsed.defaultIndexes)
    }

    @Test
    fun `越界下标被丢掉`() {
        val parsed = AskUserTools.parse(
            ask(extra = ""","recommended":9,"defaults":[0,9]""")
        )!!
        assertNull("越界的推荐项作废", parsed.recommendedIndex)
        assertEquals(setOf(0), parsed.defaultIndexes)
    }

    @Test
    fun `超时被夹在 5 秒到 5 分钟之间`() {
        assertEquals(5_000L, AskUserTools.parse(ask(extra = ""","timeout_seconds":0"""))!!.timeoutMs)
        assertEquals(300_000L, AskUserTools.parse(ask(extra = ""","timeout_seconds":99999"""))!!.timeoutMs)
        assertEquals(30_000L, AskUserTools.parse(ask(extra = ""","timeout_seconds":30"""))!!.timeoutMs)
    }

    @Test
    fun `不合法参数返回 null 让模型自己纠正`() {
        assertNull("没有选项", AskUserTools.parse("""{"question":"q","options":[]}"""))
        assertNull("只有一个选项", AskUserTools.parse(ask(options = """[{"label":"唯一"}]""")))
        assertNull("没有 question", AskUserTools.parse("""{"options":[{"label":"a"},{"label":"b"}]}"""))
        assertNull("不是 json", AskUserTools.parse("不是 json"))
    }

    @Test
    fun `工具声明包含必要的参数说明`() {
        val tool = AskUserTools.tool()
        assertEquals(AskUserTools.ASK_USER, tool.function.name)
        val schema = tool.function.parameters.toString()
        listOf("question", "options", "multi_select", "recommended", "defaults", "timeout_seconds")
            .forEach { key -> assertTrue("参数缺 $key", schema.contains(key)) }
        assertTrue(
            "描述里要点明「超时会跳过」这一条，模型才知道不必等太久",
            tool.function.description.contains("超时未回答")
        )
    }
}