package com.mistakebook.math

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 向量数学（v0.0.113，资料库语义检索用）。
 *
 * 为什么手写而不用任何向量库：资料库的量级是「几百个 chunk」
 * （一份 50KB 文档 ≈ 80 块），暴力扫一遍余弦是 1e5 量级的乘加，
 * 手机上亚毫秒级。向量数据库（zvec / HNSW）解决的问题在千万级才出现，
 * 在这个量级引入只增加体积与复杂度。
 *
 * ## 存储口径（对齐 zvec 的小规模建议）
 * - 向量入库前**归一化**：余弦相似度退化为内积，检索时少一步除法；
 * - FP32 原精度存储（zvec 文档明确：千级数据 INT8/PQ 量化收益不明显，
 *   直接存原精度避免召回损失），打包成小端 BLOB 进 Room。
 */
object VectorMath {

    /** 就地归一化；零向量原样返回（余弦无意义，检索时自然排最后）。 */
    fun normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (v in vector) sum += v * v
        if (sum <= 0f) return vector
        val inv = 1f / sqrt(sum)
        for (i in vector.indices) vector[i] *= inv
        return vector
    }

    /** 余弦相似度。向量应已归一化；任意长度不一致返回 0f（视为不相关）。 */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    /** FloatArray -> 小端 BLOB（Room BLOB 列）。 */
    fun pack(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    /** 小端 BLOB -> FloatArray。长度不是 4 的倍数视为损坏，返回空数组。 */
    fun unpack(blob: ByteArray): FloatArray {
        if (blob.isEmpty() || blob.size % 4 != 0) return FloatArray(0)
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}
