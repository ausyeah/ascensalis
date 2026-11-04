package com.mistakebook.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 资料库向量索引块（v0.0.113，语义检索用）。
 *
 * 资料文件本体仍在 `files/library/`（文本原样），这里存的是**检索层**：
 * 一份文档切成若干块，每块一条记录，向量归一化后按 FP32 小端打包进
 * [vector]。检索时全表扫余弦——量级是几百块，暴力扫亚毫秒
 * （zvec 对千级数据的建议同样是 FP32 平铺，无需 ANN 索引）。
 *
 * ## 增量判据
 * [contentHash] 是块文本的 SHA-256 前 16 hex。重复「建立索引」时，
 * hash 没变的块直接复用，只有新增/修改的块才发嵌入请求——
 * 同一份资料反复重建索引不产生重复流量。
 *
 * [docName] 不做外键：资料文件在文件系统里，删除文件走索引器
 * 清理对应块（文件系统与 DB 无事务，靠重建兜底）。
 */
@Entity(
    tableName = "library_chunks",
    indices = [Index(value = ["docName"])]
)
data class LibraryChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属资料文件名（library 目录内的安全名）。 */
    val docName: String,
    /** 块在文档内的序号（1 起），检索结果排序与展示用。 */
    val chunkIndex: Int,
    /** 块文本指纹（SHA-256 前 16 hex），增量索引判据。 */
    val contentHash: String,
    /** 块文本（含《文件名》#序号 出处头）。 */
    val text: String,
    /** 向量维度。检索时与查询向量维度不一致的块跳过（换模型后旧块自然失效）。 */
    val dim: Int,
    /** 生成向量所用的嵌入模型名：换模型后旧向量必须重算，靠它识别。 */
    val model: String,
    /** 归一化向量，FP32 小端打包。 */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
    val updatedAt: Long
) {
    /** 向量是内容而非身份：两个块只按主键判等。 */
    override fun equals(other: Any?): Boolean = other is LibraryChunkEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
