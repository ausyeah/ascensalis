package com.mistakebook.mindmap

import com.mistakebook.report.WeeklyStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思维导图解析与标记（F4）的纯函数测试。
 */
class MindmapParserTest {

    @Test
    fun `标题与列表嵌套解析成树`() {
        val md = """
            # 数学
            ## 函数
            - 🔴数列（3道）
              - 等差数列
            ## 几何
            - 圆锥曲线
        """.trimIndent()
        val tree = MindmapParser.parse(md, "数学")
        assertEquals("数学", tree.content)
        val chapters = tree.children.map { it.content }
        assertEquals(listOf("函数", "几何"), chapters)
        val underFunction = tree.children[0].children
        assertEquals("🔴数列（3道）", underFunction[0].content)
        assertEquals(listOf("等差数列"), underFunction[0].children.map { it.content })
    }

    @Test
    fun `多个一级标题包进合成根`() {
        val md = "# 代数\n- 多项式\n# 几何\n- 圆"
        val tree = MindmapParser.parse(md, "数学")
        assertEquals("数学", tree.content)
        assertEquals(2, tree.children.size)
    }

    @Test
    fun `空输入返回仅含根名的空树`() {
        val tree = MindmapParser.parse("说明文字而已", "物理")
        assertEquals("物理", tree.content)
        assertTrue(tree.children.isEmpty())
    }

    @Test
    fun `强调符号被剥掉`() {
        // 无标题的裸列表：唯一根节点直接作为树根
        val tree = MindmapParser.parse("- **导数**", "数学")
        assertEquals("导数", tree.content)
        assertTrue(tree.children.isEmpty())
    }

    @Test
    fun `缺失知识点检测`() {
        val required = listOf("数列", "圆锥曲线", "导数")
        val md = "包含 数列 和 导数"
        assertEquals(listOf("圆锥曲线"), MindmapParser.missingKnowledgePoints(md, required))
    }
}

/**
 * 薄弱点标记阈值的钉子测试：阈值是产品决策，改这里必须是有意的。
 */
class MindmapPromptsTest {

    @Test
    fun `三级标记阈值`() {
        fun kp(name: String, count: Int) = WeeklyStats.KnowledgePointCount(name, count)
        assertEquals("🔴数列（3道）", MindmapPrompts.mark(kp("数列", 3)))
        assertEquals("🔴数列（10道）", MindmapPrompts.mark(kp("数列", 10)))
        assertEquals("🟠导数（2道）", MindmapPrompts.mark(kp("导数", 2)))
        assertEquals("圆（1道）", MindmapPrompts.mark(kp("圆", 1)))
    }

    @Test
    fun `user 提示包含全部薄弱点原文`() {
        val list = listOf(
            WeeklyStats.KnowledgePointCount("数列", 4),
            WeeklyStats.KnowledgePointCount("导数", 2)
        )
        val user = MindmapPrompts.user("数学", list)
        assertTrue(user.contains("🔴数列（4道）"))
        assertTrue(user.contains("🟠导数（2道）"))
        assertTrue(user.contains("数学"))
    }

    @Test
    fun `空薄弱点清单给占位说明`() {
        assertTrue(MindmapPrompts.user("化学", emptyList()).contains("暂无错题数据"))
    }
}
