package com.mistakebook.net.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 嵌入响应解析（v0.0.113）：索引器的数据入口。
 *
 * 服务端把 `data` 按 index 乱序回传、缺一条、某条 embedding 为空——
 * 任何一种都不能静默产出错误向量。这里全部按坏响应拒绝，
 * 由上层把失败归因为「索引失败：原因」，绝不静默补零向量。
 */
class EmbeddingClientParseTest {

    private fun response(vararg vectors: List<Double>) = EmbeddingResponse(
        data = vectors.mapIndexed { index, v -> EmbeddingResponse.Item(index = index, embedding = v) }
    )

    @Test
    fun `按 index 对齐并归一化`() {
        // 乱序回传：index 1 在前
        val response = EmbeddingResponse(
            data = listOf(
                EmbeddingResponse.Item(index = 1, embedding = listOf(0.0, 2.0)),
                EmbeddingResponse.Item(index = 0, embedding = listOf(3.0, 0.0))
            )
        )
        val out = parseEmbeddingResponse(response, expected = 2)!!
        // 输出按请求顺序：第 0 条是 (3,0) → 归一化 (1,0)
        assertEquals(1f, out[0][0], 1e-5f)
        assertEquals(0f, out[0][1], 1e-5f)
        // 第 1 条是 (0,2) → 归一化 (0,1)
        assertEquals(0f, out[1][0], 1e-5f)
        assertEquals(1f, out[1][1], 1e-5f)
    }

    @Test
    fun `条数不一致拒绝`() {
        assertNull(parseEmbeddingResponse(response(listOf(1.0, 0.0)), expected = 2))
    }

    @Test
    fun `index 缺号拒绝`() {
        val response = EmbeddingResponse(
            data = listOf(
                EmbeddingResponse.Item(index = 0, embedding = listOf(1.0)),
                EmbeddingResponse.Item(index = 2, embedding = listOf(1.0))
            )
        )
        assertNull(parseEmbeddingResponse(response, expected = 2))
    }

    @Test
    fun `空 embedding 拒绝`() {
        val response = EmbeddingResponse(
            data = listOf(
                EmbeddingResponse.Item(index = 0, embedding = listOf(1.0)),
                EmbeddingResponse.Item(index = 1, embedding = emptyList())
            )
        )
        assertNull(parseEmbeddingResponse(response, expected = 2))
    }

    @Test
    fun `归一化确实是单位长度`() {
        val out = parseEmbeddingResponse(response(listOf(3.0, 4.0)), expected = 1)!!
        assertEquals(1f, kotlin.math.sqrt(out[0][0] * out[0][0] + out[0][1] * out[0][1]), 1e-5f)
        assertTrue(out[0].size == 2)
    }
}
