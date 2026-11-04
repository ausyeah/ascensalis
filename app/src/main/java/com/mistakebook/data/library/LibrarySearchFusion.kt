package com.mistakebook.data.library

/**
 * 资料库混合检索的融合打分（v0.0.113）。
 *
 * ## 为什么是 RRF（Reciprocal Rank Fusion）
 * 向量余弦分数与「关键词命中」的分数量纲完全不同（0.83 vs 3 次），
 * 直接加权总有一边被淹没。RRF 只看**名次**：每路结果按名次给
 * 1/(K+rank) 分（K=60，论文原值），两路相加——谁排前面谁得分，
 * 天然免归一化。zvec 的多向量查询用的也是同一招。
 */
object LibrarySearchFusion {

    /** RRF 平滑常数：论文推荐 60，名次 1 的贡献约 0.016。 */
    const val K = 60

    /** 一条检索结果：块在库里的定位 + 综合得分。 */
    data class Hit(
        val docName: String,
        val chunkIndex: Int,
        val text: String,
        val score: Double
    )

    /**
     * 把两路结果融合成一份排好序的列表。
     *
     * @param vectorRanked 语义检索的命中（按相似度从高到低）
     * @param keywordRanked 关键词命中的块（按命中次数/位置排好）
     * @param topK 最终返回条数
     *
     * 纯函数：同一个 key（docName+chunkIndex）在两路都出现时分数相加，
     * 只在一出现就只有那一路的分。稳定排序：并列时按输入先后。
     */
    fun fuse(
        vectorRanked: List<Hit>,
        keywordRanked: List<Hit>,
        topK: Int
    ): List<Hit> {
        if (topK <= 0) return emptyList()
        val scores = HashMap<String, Double>()
        val byKey = LinkedHashMap<String, Hit>()

        fun addAll(list: List<Hit>) {
            list.forEachIndexed { index, hit ->
                val key = hit.docName + "#" + hit.chunkIndex
                scores[key] = (scores[key] ?: 0.0) + 1.0 / (K + index + 1)
                byKey.putIfAbsent(key, hit)
            }
        }
        addAll(vectorRanked)
        addAll(keywordRanked)

        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value })
            .take(topK)
            .map { byKey.getValue(it.key).copy(score = it.value) }
    }
}
