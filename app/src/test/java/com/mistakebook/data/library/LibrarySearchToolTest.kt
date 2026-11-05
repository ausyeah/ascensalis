package com.mistakebook.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `library_search` 工具（v0.0.120）。
 *
 * 对话里原来只有 library_list / library_read：模型要回答「资料库里关于 X 的
 * 内容」，得先列目录再整份读，一次问答就可能塞进几万字。检索工具只回最相关
 * 的几段。这里钉住三件容易退化的事：参数解析、结果带上出处、以及总量上限
 * ——上限一破，检索就退化成「整份读」。
 */
class LibrarySearchToolTest {

    private fun hit(name: String, index: Int, text: String, score: Double = 0.9) =
        LibrarySearchFusion.Hit(docName = name, chunkIndex = index, text = text, score = score)

    @Test
    fun `工具定义要求先检索而不是整份读`() {
        val tool = LibraryTools.all().first { it.function.name == LibraryTools.SEARCH }

        assertEquals("library_search", tool.function.name)
        assertTrue(
            "描述里必须点明先检索：否则模型还是走 list + read",
            tool.function.description.contains("先调用本工具定位段落") &&
                tool.function.description.contains("不要用 library_list")
        )
        assertTrue(
            "参数必须有 query",
            tool.function.parameters.toString().contains("query")
        )
        assertTrue(
            "检索也是资料库工具，handles 必须认领",
            LibraryTools.handles(LibraryTools.SEARCH)
        )
    }

    @Test
    fun `参数解析`() {
        assertEquals("矩阵求逆", LibraryTools.parseSearchQuery("""{"query":"矩阵求逆"}"""))
        assertEquals("矩阵求逆", LibraryTools.parseSearchQuery("""{"query":"  矩阵求逆  "}"""))
        assertNull(LibraryTools.parseSearchQuery("""{"other":"x"}"""))
        assertNull(LibraryTools.parseSearchQuery("""{"query":"   "}"""))
        assertNull(LibraryTools.parseSearchQuery("不是 json"))
    }

    @Test
    fun `命中结果带出处与相关度`() {
        val text = LibraryTools.formatHits(
            query = "矩阵求逆",
            hits = listOf(
                hit("linear_algebra.md", 2, "A square matrix is invertible iff det != 0."),
                hit("高数复习要点.md", 5, "用高斯消元求逆。", score = 0.75)
            )
        )

        assertTrue("要有文件名", text.contains("linear_algebra.md"))
        assertTrue("要有段号", text.contains("第 2 段"))
        assertTrue("要有相关度", text.contains("相关度 0.90"))
        assertTrue("第二份资料同样带出处", text.contains("高数复习要点.md · 第 5 段"))
        assertTrue("正文要原样带出", text.contains("A square matrix is invertible"))
    }

    @Test
    fun `空结果给可执行提示`() {
        val text = LibraryTools.formatHits("不存在的话题", emptyList())

        assertTrue("要说明可能没建索引", text.contains("建立索引"))
        assertTrue("要带回查询词，便于模型复述", text.contains("不存在的话题"))
        assertFalse("不许静默返回空串", text.isBlank())
    }

    @Test
    fun `结果总量有上限，检索不会退化成整份读`() {
        val longText = "段".repeat(500)
        val hits = (1..6).map { hit("doc.md", it, longText) }

        val text = LibraryTools.formatHits("q", hits)

        assertTrue(
            "总字符不能超过 MAX_SEARCH_CHARS，否则一次检索塞回几万字",
            text.length <= LibraryTools.MAX_SEARCH_CHARS + 120
        )
        assertTrue("截断时必须说明省略", text.contains("其余段落已省略"))
    }
}