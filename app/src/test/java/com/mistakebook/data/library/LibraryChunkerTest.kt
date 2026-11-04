package com.mistakebook.data.library

import com.mistakebook.math.VectorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资料库分块（v0.0.113）：向量检索的第一步，切坏了后面全白搭——
 * 块太大嵌出来「什么都沾一点」，块太小上下文不够。
 */
class LibraryChunkerTest {

    @Test
    fun `短段合并 长段独立`() {
        val text = "第一段。\n\n第二段。\n\n第三段。"
        val chunks = LibraryChunker.chunk("笔记.md", text)
        // 三个短段远小于 600 字，应打包进同一块
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("第一段") && chunks[0].contains("第三段"))
    }

    @Test
    fun `超长段落硬切且带重叠`() {
        val body = "字".repeat(1000)
        val chunks = LibraryChunker.chunk("长.md", "开头\n\n$body\n\n结尾")
        // 开头/结尾与长段隔空行，各自成块；长段被切成 ≥2 块
        assertTrue(chunks.size >= 3)
        // 硬切的重叠：第 2 块的开头与第 1 块的结尾共享 OVERLAP_CHARS 字
        val first = chunks[1].substringAfter("\n")
        val second = chunks[2].substringAfter("\n")
        assertEquals(
            first.takeLast(LibraryChunker.OVERLAP_CHARS),
            second.take(LibraryChunker.OVERLAP_CHARS)
        )
    }

    @Test
    fun `每块带出处头`() {
        val chunks = LibraryChunker.chunk("高数笔记.md", "极限的定义。")
        assertEquals(1, chunks.size)
        assertTrue("块要以《文件名》#序号开头", chunks[0].startsWith("《高数笔记.md》#1\n"))
    }

    @Test
    fun `空文本不产生块`() {
        assertTrue(LibraryChunker.chunk("空.md", "").isEmpty())
        assertTrue(LibraryChunker.chunk("空.md", "  \n\n  ").isEmpty())
    }

    @Test
    fun `内容哈希只看内容`() {
        assertEquals(
            LibraryChunker.contentHash("同一段内容"),
            LibraryChunker.contentHash("同一段内容")
        )
        assertFalse(
            LibraryChunker.contentHash("同一段内容") == LibraryChunker.contentHash("同一段内容。")
        )
        // 稳定：同一输入重算多次结果一致（增量索引用它做判据）
        assertEquals(
            LibraryChunker.contentHash("稳定"),
            LibraryChunker.contentHash("稳定")
        )
    }
}
