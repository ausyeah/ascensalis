package com.mistakebook.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 管道表格的回归测试（v0.0.53）。
 *
 * ## 现象
 *
 * 用户真机截图：同一段 Markdown 表格内容**连续出现 3 次**。
 *
 * ## 根因
 *
 * `parsePipeTable` 只**解析**不**消费**输入行，而外层 `parsePlain` 用
 * `lines.forEach { ... }` 逐行遍历——表格的每一行都会被外层循环重新
 * 当成一次「表头」再解析一遍，于是一张 N 行的表被输出 N 次。
 *
 * 修复：外层改成**索引循环**，表格分支把「自己吃掉的那几行」一次性
 * 消费掉（`parsePipeTable` 返回下一行下标）。
 */
class MarkdownTableTest {

    private fun tables(input: String): List<MarkdownParser.Block.Table> =
        MarkdownParser.parse(input).filterIsInstance<MarkdownParser.Block.Table>()

    @Test
    fun pipeTableIsParsedOnlyOnce() {
        val md = """
            | 名称 | 说明 |
            | --- | --- |
            | A | 第一 |
            | B | 第二 |
            | C | 第三 |
        """.trimIndent()

        val result = tables(md)

        assertEquals("一张表只应解析出一次", 1, result.size)
        assertEquals("表头 + 3 行数据", 4, result[0].rows.size)
        assertEquals(listOf("名称", "说明"), result[0].rows[0])
        assertEquals(listOf("C", "第三"), result[0].rows[3])
    }

    @Test
    fun tableContentIsNotDuplicated() {
        val md = """
            | 名称 | 说明 |
            | --- | --- |
            | A | 第一 |
            | B | 第二 |
            | C | 第三 |
        """.trimIndent()

        val blocks = MarkdownParser.parse(md)

        // 全量块里只应出现一次表格，且总块数 = 1
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownParser.Block.Table)
    }

    @Test
    fun twoTablesInOneDocumentAreBothKept() {
        val md = """
            | A1 | A2 |
            | --- | --- |
            | a | b |

            正文段落

            | B1 | B2 |
            | --- | --- |
            | c | d |
        """.trimIndent()

        val result = tables(md)

        assertEquals("两张表都要在", 2, result.size)
        assertEquals(listOf("A1", "A2"), result[0].rows[0])
        assertEquals(listOf("B1", "B2"), result[1].rows[0])
    }

    @Test
    fun tableFollowedByParagraphKeepsBoth() {
        val md = """
            | 名称 | 说明 |
            | --- | --- |
            | A | 第一 |

            表格后面的正文
        """.trimIndent()

        val blocks = MarkdownParser.parse(md)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownParser.Block.Table)
        assertTrue(blocks[1] is MarkdownParser.Block.Paragraph)
        assertEquals("表格后面的正文", (blocks[1] as MarkdownParser.Block.Paragraph).text)
    }

    @Test
    fun paragraphBeforeTableIsKept() {
        val md = """
            表格前面的正文

            | 名称 | 说明 |
            | --- | --- |
            | A | 第一 |
        """.trimIndent()

        val blocks = MarkdownParser.parse(md)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownParser.Block.Paragraph)
        assertTrue(blocks[1] is MarkdownParser.Block.Table)
    }

    @Test
    fun singleRowTableIsParsedOnce() {
        val md = """
            | 名称 | 说明 |
            | --- | --- |
        """.trimIndent()

        val result = tables(md)

        assertEquals(1, result.size)
        assertEquals(1, result[0].rows.size)
    }
}
