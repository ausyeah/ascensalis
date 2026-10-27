package com.mistakebook.data.library

import com.mistakebook.di.AppContainer
import kotlinx.coroutines.flow.first
import com.mistakebook.domain.Badge
import com.mistakebook.domain.BadgeEngine
import com.mistakebook.domain.BadgeStats
import com.mistakebook.data.local.entities.BadgeRecord
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 徽章仓库（v0.0.51 T10）：聚合统计 → [BadgeEngine] 判定 → 写库。
 *
 * 触发时机：App 启动 + 每次完成复习 / 录入错题（轻量判定）。
 * 新解锁写库，返回值交给调用方当次会话内 toast。
 *
 * [currentStats] 独立暴露统计聚合：成就墙的未解锁进度展示只读不算，
 * 与写库路径解耦。
 */
class BadgeRepository(private val container: AppContainer) {

    /** 已解锁徽章的观察流（成就墙用）。 */
    fun observeUnlocked(): kotlinx.coroutines.flow.Flow<List<com.mistakebook.data.local.entities.BadgeRecord>> =
        container.badgeDao.observeAll()

    /** 当前徽章统计（成就墙进度展示用；不写库）。 */
    suspend fun currentStats(): BadgeStats = collectStats()

    /**
     * 跑一轮判定，返回**本回合新解锁**的徽章（可能为空）。
     * 已解锁的徽章不会重复写（主键 IGNORE + 内存去重）。
     */
    suspend fun evaluateAndStore(): List<Badge> {
        val stats = collectStats()
        val alreadyUnlocked = container.badgeDao.all().map { it.key }.toSet()
        val now = System.currentTimeMillis()
        val newly = mutableListOf<Badge>()
        BadgeEngine.evaluate(stats, LocalDate.now()).forEach { progress ->
            if (!progress.unlocked) return@forEach
            val key = progress.badge.key
            if (key in alreadyUnlocked) return@forEach
            val inserted = container.badgeDao.insertIgnore(
                BadgeRecord(
                    key = key,
                    unlockedAt = now,
                    progressJson = "{\"current\":${progress.current},\"target\":${progress.badge.target}}"
                )
            )
            if (inserted > 0) newly += progress.badge
        }
        return newly
    }

    /**
     * 聚合徽章统计。全部走既有 DAO 的一次性查询（非 Flow）：
     * 判定是「读一次算一次」的轻量操作，不需要常驻订阅。
     */
    private suspend fun collectStats(): BadgeStats {
        val questions = container.questionRepository.allActiveQuestions()
        val totalQuestions = questions.size
        val masteredQuestions = questions.count {
            it.status == com.mistakebook.domain.MasteryStatus.MASTERED
        }

        // 累计复习次数：review_logs 全表行数
        val totalReviews = container.reviewLogDao.totalCount()

        // 单日录入最大值 + 学习行为日期集合（录入 ∪ 复习）
        val zone = ZoneId.systemDefault()
        val entryByDay = questions.groupingBy {
            Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate()
        }.eachCount()
        val maxDailyEntries = entryByDay.values.maxOrNull() ?: 0

        val reviewDays = container.reviewLogDao.allReviewedDays().map { dayMs ->
            Instant.ofEpochMilli(dayMs).atZone(zone).toLocalDate()
        }
        val studyDays = entryByDay.keys + reviewDays

        // 知识点清零：知识点维度「有错题 → 全部毕业」的数量。
        // knowledgePointsJson 是 JSON 字符串数组；逐题展开后按知识点名分组统计。
        val knowledgeQuestions = questions.flatMap { q ->
            knowledgePointsOf(q.knowledgePointsJson).map { it to q }
        }
        val clearedKnowledgePoints = knowledgeQuestions
            .groupBy({ it.first }, { it.second })
            .count { (_, list) ->
                list.isNotEmpty() && list.all {
                    it.status == com.mistakebook.domain.MasteryStatus.MASTERED
                }
            }

        // 词汇类：单词书进度（firstWordRecorded = 有任何进度行；mastered = isMastered 计数）
        val wordProgress: List<com.mistakebook.wordbook.data.WordProgress> = try {
            container.wordbookDao.observeAllProgress().first()
        } catch (_: Throwable) {
            emptyList()
        }
        val firstWordRecorded = wordProgress.isNotEmpty()
        val masteredWords = wordProgress.count { it.isMastered }

        return BadgeStats(
            totalQuestions = totalQuestions,
            masteredQuestions = masteredQuestions,
            totalReviews = totalReviews,
            maxDailyEntries = maxDailyEntries,
            studyDays = studyDays,
            clearedKnowledgePoints = clearedKnowledgePoints,
            firstWordRecorded = firstWordRecorded,
            masteredWords = masteredWords
        )
    }

    /** 解析 knowledgePointsJson（JSON 字符串数组；解析失败按空处理）。 */
    private fun knowledgePointsOf(json: String): List<String> =
        runCatching {
            kotlinx.serialization.json.Json.Default
                .decodeFromString<List<String>>(json)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
}
