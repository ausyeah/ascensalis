package com.mistakebook.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 周报的聚合统计（F2）。纯数据 + 纯函数，脱开数据库与 LLM 可单测。
 */
@Serializable
data class WeeklyStats(
    val weekStartEpochDay: Long,
    val weekEndEpochDay: Long,
    val totalActive: Int = 0,
    val totalMastered: Int = 0,
    val newThisWeek: Int = 0,
    /** 错因名（枚举 name）→ 题数。 */
    val reasonCounts: Map<String, Int> = emptyMap(),
    /** 复习结果名 → 次数。复习闭环未接活时为空。 */
    val reviewCounts: Map<String, Int> = emptyMap(),
    /** 知识点 → 出错题数，已按频次降序截断到 [TOP_KNOWLEDGE_POINTS]。 */
    val topKnowledgePoints: List<KnowledgePointCount> = emptyList()
) {
    @Serializable
    data class KnowledgePointCount(val name: String, val count: Int)

    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(raw: String): WeeklyStats? =
            runCatching { json.decodeFromString<WeeklyStats>(raw) }.getOrNull()

        /** 知识点榜单截断长度：太长只会稀释重点，LLM 的建议也随之发散。 */
        const val TOP_KNOWLEDGE_POINTS = 12
    }
}

/**
 * 从"原始 DAO 计数"装配 [WeeklyStats]。
 *
 * 为什么是纯函数而不是在 DAO 层做完：知识点频次要跨行 JSON 解析再聚合，
 * SQL 表达不了；放 ViewModel 又会让"统计口径"散落 UI 层，没法单测。
 */
object WeeklyStatsBuilder {

    fun build(
        weekStartEpochDay: Long,
        weekEndEpochDay: Long,
        totalActive: Int,
        totalMastered: Int,
        newThisWeek: Int,
        reasonRows: List<Pair<String, Int>>,
        reviewRows: List<Pair<String, Int>>,
        knowledgePointJsons: List<String>
    ): WeeklyStats = WeeklyStats(
        weekStartEpochDay = weekStartEpochDay,
        weekEndEpochDay = weekEndEpochDay,
        totalActive = totalActive,
        totalMastered = totalMastered,
        newThisWeek = newThisWeek,
        reasonCounts = reasonRows.toMap(),
        reviewCounts = reviewRows.toMap(),
        topKnowledgePoints = topKnowledgePoints(knowledgePointJsons, WeeklyStats.TOP_KNOWLEDGE_POINTS)
    )

    /**
     * 解析每题的知识点 JSON 数组并统计频次。
     *
     * 单条解析失败（历史脏数据/字段改名）跳过该条而不是让整份周报失败：
     * 统计报表的正确姿态是"尽力而为、口径可追溯"，一条坏数据不配拦住全局。
     */
    internal fun topKnowledgePoints(
        knowledgePointJsons: List<String>,
        limit: Int
    ): List<WeeklyStats.KnowledgePointCount> {
        val json = Json { ignoreUnknownKeys = true }
        val counter = HashMap<String, Int>()
        knowledgePointJsons.forEach { raw ->
            runCatching { json.decodeFromString<List<String>>(raw) }
                .getOrNull()
                ?.forEach { point -> counter[point.trim()] = (counter[point.trim()] ?: 0) + 1 }
        }
        return counter.entries
            .filter { it.key.isNotEmpty() }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { WeeklyStats.KnowledgePointCount(it.key, it.value) }
    }
}
