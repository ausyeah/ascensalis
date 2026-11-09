package com.mistakebook.data.chat

import com.mistakebook.domain.ErrorReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * save_question 工具（issue #55）的参数解析单测。
 *
 * 这个工具**直接写库**，解析错了就是一条脏数据进错题本，所以每一处
 * 宽松之处都写明理由：错因认不出归「其他」（不能因为一个标签写歪就丢掉
 * 整道题），难度越界夹到 1~5，选项带不带标号都认。
 */
class SaveQuestionToolsTest {

    @Test
    fun `完整参数原样进草稿`() {
        val draft = SaveQuestionTools.parse(
            """
            {"stem":"求 ${'$'}f(x)=x^2+2x${'$'} 在 ${'$'}[-3,1]${'$'} 上的最小值",
             "answer":"-1","analysis":"配方得 ${'$'}(x+1)^2-1${'$'}",
             "subject":"数学","knowledge_points":["二次函数","最值"],
             "error_reason":"概念不清","difficulty":4,
             "title":"二次函数最值","note":"注意对称轴是否在区间内"}
            """.trimIndent()
        )
        assertEquals("数学", draft!!.subjectName)
        assertTrue(draft.stem.contains("x^2+2x"))
        assertEquals("-1", draft.answer)
        assertEquals(listOf("二次函数", "最值"), draft.knowledgePoints)
        assertEquals(ErrorReason.CONCEPT, draft.errorReason)
        assertEquals(4, draft.difficulty)
        assertEquals("二次函数最值", draft.title)
        assertEquals("注意对称轴是否在区间内", draft.note)
    }

    @Test
    fun `没有原图时按手动录入的约定传空串`() {
        val draft = SaveQuestionTools.parse("""{"stem":"1+1=?"}""")
        assertEquals("", draft!!.imagePath)
        assertEquals("", draft.mineruMarkdown)
        assertTrue("题干是唯一的入库门槛", draft.isValid())
    }

    @Test
    fun `题干为空就拒绝（不许存空卡片）`() {
        assertNull(SaveQuestionTools.parse("""{"answer":"1"}"""))
        assertNull(SaveQuestionTools.parse("""{"stem":"   "}"""))
        assertNull(SaveQuestionTools.parse("不是 JSON"))
    }

    @Test
    fun `错因按中文标签认，也认枚举名，认不出归其他`() {
        assertEquals(ErrorReason.CONCEPT, SaveQuestionTools.errorReason("概念不清"))
        assertEquals(ErrorReason.CALCULATION, SaveQuestionTools.errorReason("计算失误"))
        assertEquals(ErrorReason.NO_IDEA, SaveQuestionTools.errorReason("思路不会"))
        assertEquals(ErrorReason.CONCEPT, SaveQuestionTools.errorReason("CONCEPT"))
        assertEquals(ErrorReason.OTHER, SaveQuestionTools.errorReason("今天心情不好"))
        assertEquals(ErrorReason.OTHER, SaveQuestionTools.errorReason(null))
    }

    @Test
    fun `难度越界夹到 1 到 5，缺省 3`() {
        assertEquals(1, SaveQuestionTools.parse("""{"stem":"a","difficulty":0}""")!!.difficulty)
        assertEquals(5, SaveQuestionTools.parse("""{"stem":"a","difficulty":99}""")!!.difficulty)
        assertEquals(3, SaveQuestionTools.parse("""{"stem":"a"}""")!!.difficulty)
        assertEquals(2, SaveQuestionTools.parse("""{"stem":"a","difficulty":"2"}""")!!.difficulty)
    }

    @Test
    fun `知识点去重去空并限 6 个`() {
        val draft = SaveQuestionTools.parse(
            """{"stem":"a","knowledge_points":["二次函数"," ","二次函数","极值","单调性","对称轴","判别式","韦达定理"]}"""
        )
        assertEquals(6, draft!!.knowledgePoints.size)
        assertEquals(listOf("二次函数", "极值", "单调性", "对称轴", "判别式", "韦达定理"), draft.knowledgePoints)
    }

    @Test
    fun `选项带标号就拆开，不带就按顺序补`() {
        val labeled = SaveQuestionTools.parseOptions(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """["A. 1","B、2","(C) 3","D)4"]"""
            )
        )
        assertEquals("A", labeled[0].label)
        assertEquals("1", labeled[0].text)
        assertEquals("B", labeled[1].label)
        assertEquals("2", labeled[1].text)
        assertEquals("C", labeled[2].label)
        assertEquals("3", labeled[2].text)
        assertEquals("D", labeled[3].label)
        assertEquals("4", labeled[3].text)
    }

    @Test
    fun `选项没有标号就补 A B C`() {
        val plain = SaveQuestionTools.parseOptions(
            kotlinx.serialization.json.Json.parseToJsonElement("""["x^2","2x","x+1"]""")
        )
        assertEquals(listOf("A", "B", "C"), plain.map { it.label })
        assertEquals("x^2", plain[0].text)
    }

    @Test
    fun `英文句子不会被当成 C 选项（标号后必须是分隔符）`() {
        val options = SaveQuestionTools.parseOptions(
            kotlinx.serialization.json.Json.parseToJsonElement("""["Calculate the limit"]""")
        )
        assertEquals("不能被咬成「C」+「alculate…」", "Calculate the limit", options[0].text)
        assertEquals("A", options[0].label)
    }

    @Test
    fun `工具声明与名字对得上`() {
        val tool = SaveQuestionTools.tool()
        assertEquals("save_question", tool.function.name)
        assertTrue(SaveQuestionTools.handles("save_question"))
        assertTrue(
            "描述里要写清触发场景，模型才知道什么时候调用",
            tool.function.description.contains("加入错题本") ||
                tool.function.description.contains("存成错题")
        )
    }
}
