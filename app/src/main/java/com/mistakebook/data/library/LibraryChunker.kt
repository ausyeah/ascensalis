package com.mistakebook.data.library

import java.security.MessageDigest

/**
 * 资料库文档分块（v0.0.113，向量检索用）。
 *
 * ## 为什么是「段落打包 + 硬切」而不是固定滑窗
 * 资料是中文学习笔记：段落（空行分隔）就是天然的语义边界，沿着它切，
 * 每块都是完整的一段话，嵌出来的向量才「一句话一个意思」。
 * 超过 [MAX_CHUNK_CHARS] 的段落（整章没换行的场景）再按字数硬切，
 * 带 [OVERLAP_CHARS] 重叠——硬切边界上的句子在相邻两块里都完整出现，
 * 检索时哪边命中都能带出上下文。
 *
 * ## 每块自带出处头
 * 块文本以「《文件名》#序号\n」开头：模型拿到检索结果时不需要额外的
 * 映射表就知道这句话出自哪份资料，注入提示词时也更可读。
 */
object LibraryChunker {

    /** 单块目标字数（中文字符）：600 字 ≈ 900 token，检索粒度与成本平衡。 */
    const val MAX_CHUNK_CHARS = 600

    /** 硬切时的重叠字数：切点两侧的句子在相邻块各保留一份。 */
    const val OVERLAP_CHARS = 80

    /** 每次最多切多少块：单文档超限说明塞了整本书，先按上限走。 */
    const val MAX_CHUNKS_PER_DOC = 512

    /**
     * 把一份文档切成若干块。空文本返回空列表。
     *
     * 纯函数：不碰文件系统，调用方先用 [DocumentTextExtractor] 提正文。
     */
    fun chunk(docName: String, text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val body = chunksOf(text)
        val out = ArrayList<String>(body.size)
        body.forEachIndexed { index, piece ->
            out.add("《$docName》#${index + 1}\n$piece")
            if (out.size >= MAX_CHUNKS_PER_DOC) return out
        }
        return out
    }

    /** 段落打包：空行分段，短段合并、长段硬切。 */
    private fun chunksOf(text: String): List<String> {
        val out = ArrayList<String>()
        val buffer = StringBuilder()
        fun flush() {
            val piece = buffer.toString().trim()
            buffer.clear()
            when {
                piece.isEmpty() -> Unit
                piece.length <= MAX_CHUNK_CHARS -> out.add(piece)
                else -> hardSplit(piece, out)
            }
        }
        // \r\n 统一成 \n；段落以空行分隔
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).forEach { para ->
            val p = para.trim()
            if (p.isEmpty()) return@forEach
            // 再塞得下（含一个换行分隔）就并入当前块；塞不下先落盘
            if (buffer.isNotEmpty() && buffer.length + p.length + 1 > MAX_CHUNK_CHARS) {
                flush()
            }
            if (p.length > MAX_CHUNK_CHARS) {
                // 长段先落掉攒着的短块，再独立硬切
                flush()
                hardSplit(p, out)
            } else {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(p)
            }
        }
        flush()
        return out
    }

    /** 超长段落按 [MAX_CHUNK_CHARS] 硬切，相邻块重叠 [OVERLAP_CHARS] 字。 */
    private fun hardSplit(piece: String, out: MutableList<String>) {
        var start = 0
        while (start < piece.length && out.size < MAX_CHUNKS_PER_DOC) {
            val end = (start + MAX_CHUNK_CHARS).coerceAtMost(piece.length)
            out.add(piece.substring(start, end).trim())
            if (end >= piece.length) break
            start = end - OVERLAP_CHARS
        }
    }

    /**
     * 块内容指纹（SHA-256 前 16 个 hex 字符）：增量索引的增量判据。
     * 内容没变就不重新 embed——同一份资料反复「建立索引」只对新增/修改
     * 的块发请求，已算过的向量直接复用。
     */
    fun contentHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
