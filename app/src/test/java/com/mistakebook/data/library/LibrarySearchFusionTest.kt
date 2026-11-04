package com.mistakebook.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混合检索的 RRF 融合（v0.0.113）。
 *
 * 向量分数（0.83）与关键词命中（几次）量纲不同，加权必有一边被淹没；
 * RRF 只看名次。这里钉住三条性质：两路相加、名次决定贡献、
 * 融合不丢信息（任一路的条目都保留 docName/chunkIndex/text）。
 */
class LibrarySearchFusionTest {

    private fun hit(doc: String, idx: Int) = LibrarySearchFusion.Hit(doc, idx, "内容 $doc#$idx", 0.0)

    @Test
    fun `两路都命中的条目得分相加排最前`() {
        val vector = listOf(hit("A.md", 1), hit("B.md", 2), hit("C.md", 3))
        val keyword = listOf(hit("B.md", 2), hit("A.md", 1))
        val fused = LibrarySearchFusion.fuse(vector, keyword, topK = 5)
        // A、B 两路都出现 → RRF 分相加，都高于只在一路出现的 C
        assertTrue(
            "前两名应是 A 和 B（实际 ${fused.take(2).map { it.docName }}）",
            fused.take(2).map { it.docName }.toSet() == setOf("A.md", "B.md")
        )
        assertEquals("C.md", fused[2].docName)
    }

    @Test
    fun `只在一出现的条目不丢`() {
        val fused = LibrarySearchFusion.fuse(
            vectorRanked = listOf(hit("V.md", 1)),
            keywordRanked = listOf(hit("K.md", 5)),
            topK = 5
        )
        assertEquals(2, fused.size)
        assertEquals(setOf("V.md", "K.md"), fused.map { it.docName }.toSet())
    }

    @Test
    fun `topK 截断`() {
        val fused = LibrarySearchFusion.fuse(
            vectorRanked = (1..10).map { hit("V$it.md", it) },
            keywordRanked = emptyList(),
            topK = 3
        )
        assertEquals(3, fused.size)
    }

    @Test
    fun `名次越靠前贡献越大`() {
        val vector = listOf(hit("A.md", 1), hit("B.md", 2))
        val fused = LibrarySearchFusion.fuse(vector, emptyList(), topK = 2)
        assertTrue("A 第一路第 1 名应高于 B", fused[0].score > fused[1].score)
        // RRF 公式核对：1/(60+1)
        assertEquals(1.0 / (LibrarySearchFusion.K + 1), fused[0].score, 1e-9)
    }

    @Test
    fun `空输入返回空`() {
        assertTrue(LibrarySearchFusion.fuse(emptyList(), emptyList(), topK = 5).isEmpty())
    }

    @Test
    fun `docName 加 chunkIndex 才是唯一键`() {
        // 不同文档的同序号块不能被误合并
        val fused = LibrarySearchFusion.fuse(
            vectorRanked = listOf(hit("A.md", 1), hit("B.md", 1)),
            keywordRanked = emptyList(),
            topK = 5
        )
        assertEquals(2, fused.size)
    }
}
