package com.mistakebook.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 向量数学（v0.0.113）：归一化 + 余弦 + BLOB 打包。
 *
 * 检索的全部数学都在这里——cosine 算错一个符号，语义搜索就
 * 「最相关的排最后」，界面上毫无异常。
 */
class VectorMathTest {

    @Test
    fun `归一化后内积等于余弦`() {
        val a = floatArrayOf(3f, 4f)
        val b = floatArrayOf(4f, 3f)
        val na = VectorMath.normalize(a.copyOf())
        val nb = VectorMath.normalize(b.copyOf())
        // 手算：dot = 24, |a|=5, |b|=5 → 0.96
        assertEquals(0.96f, VectorMath.cosine(na, nb), 1e-4f)
    }

    @Test
    fun `余弦与向量长度无关`() {
        val a = floatArrayOf(1f, 0f)
        val big = floatArrayOf(100f, 0f)
        assertEquals(1f, VectorMath.cosine(VectorMath.normalize(a), VectorMath.normalize(big)), 1e-5f)
    }

    @Test
    fun `零向量不崩溃`() {
        val zero = VectorMath.normalize(floatArrayOf(0f, 0f))
        assertEquals(0f, zero[0], 1e-6f)
        assertEquals(0f, VectorMath.cosine(zero, floatArrayOf(1f, 1f)), 1e-6f)
    }

    @Test
    fun `维度不一致视为不相关`() {
        assertEquals(0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f, 0f)), 1e-6f)
    }

    @Test
    fun `BLOB 打包往返无损`() {
        val v = floatArrayOf(0.25f, -1.5f, 3.75f, 0f)
        val back = VectorMath.unpack(VectorMath.pack(v))
        assertTrue(v.contentEquals(back))
    }

    @Test
    fun `损坏的 BLOB 返回空数组`() {
        assertEquals(0, VectorMath.unpack(ByteArray(3)).size)
        assertEquals(0, VectorMath.unpack(ByteArray(0)).size)
    }

    @Test
    fun `打包是小端序`() {
        val blob = VectorMath.pack(floatArrayOf(1f))
        // 1.0f 的 IEEE754 是 0x3F800000，小端序字节应为 00 00 80 3F
        assertEquals(0x00, blob[0].toInt() and 0xFF)
        assertEquals(0x00, blob[1].toInt() and 0xFF)
        assertEquals(0x80.toByte(), blob[2])
        assertEquals(0x3F, blob[3].toInt() and 0xFF)
    }
}
