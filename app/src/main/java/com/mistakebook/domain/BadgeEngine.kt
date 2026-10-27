package com.mistakebook.domain

import java.time.LocalDate

/**
 * 徽章统计输入（v0.0.51 T10）。
 *
 * 全部字段由 App 侧从 Room 聚合后填入（[BadgeEngine] 不碰数据库——
 * 纯函数化保证每枚徽章的判定都能在 JVM 单测里正反例穷举）。
 *
 * @param totalQuestions 累计录入错题数（deletedAt IS NULL）。
 * @param masteredQuestions 已毕业（MASTERED）错题数。
 * @param totalReviews 累计复习次数（review_logs 行数）。
 * @param maxDailyEntries 单日录入错题数的历史最大值。
 * @param studyDays 有学习行为（录入或复习）的**去重日期集合**（本地日期）。
 * @param clearedKnowledgePoints 已清零的知识点数量（从有错题到全部毕业）。
 * @param firstWordRecorded 单词书是否录过词。
 * @param masteredWords 累计掌握（毕业）单词数。
 */
data class BadgeStats(
    val totalQuestions: Int,
    val masteredQuestions: Int,
    val totalReviews: Int,
    val maxDailyEntries: Int,
    val studyDays: Set<LocalDate>,
    val clearedKnowledgePoints: Int,
    val firstWordRecorded: Boolean,
    val masteredWords: Int
)

/** 徽章目录（首版 10 枚，全部本地计算，不联网）。 */
enum class Badge(
    val key: String,
    val title: String,
    val conditionText: String,
    /** 目标量（进度型徽章）；达成型（无进度概念）填 0。 */
    val target: Int
) {
    FIRST_QUESTION("first_question", "入门", "第一次录入错题", 0),
    HUNDRED_QUESTIONS("hundred_questions", "百题斩", "累计录入 100 道错题", 100),
    TEN_MASTERED("ten_mastered", "毕业典礼", "累计 10 道错题进入已掌握", 10),
    HUNDRED_REVIEWS("hundred_reviews", "温故知新", "累计完成 100 次复习", 100),
    SEVEN_DAY_STREAK("seven_day_streak", "七日之约", "连续 7 天有学习行为", 7),
    THIRTY_DAY_STREAK("thirty_day_streak", "一月之诺", "连续 30 天有学习行为", 30),
    KNOWLEDGE_CLEARED("knowledge_cleared", "知识点清零", "任一知识点从有错题到全部毕业", 0),
    TEN_IN_ONE_DAY("ten_in_one_day", "单日十连", "一天内录入 10 道错题", 10),
    FIRST_WORD("first_word", "初识词汇", "单词书首次录词", 0),
    HUNDRED_WORDS("hundred_words", "百词斩", "累计掌握 100 个单词", 100)
}

/**
 * 徽章判定引擎（纯函数，可 JVM 单测）。
 *
 * [evaluate] 输入统计 → 输出「应解锁集合」与每枚徽章的当前进度。
 * 单测对每枚徽章正反两例各至少一条。
 */
object BadgeEngine {

    /** 单枚徽章的评估结果。 */
    data class BadgeProgress(
        val badge: Badge,
        val current: Int,
        val unlocked: Boolean
    )

    /**
     * @param today 判定基准日（连续天数按它回溯；调用方传 LocalDate.now()）。
     * @return 与 [Badge.entries] 同序的进度列表；调用方据此写库。
     */
    fun evaluate(stats: BadgeStats, today: LocalDate): List<BadgeProgress> =
        Badge.entries.map { badge ->
            val (current, unlocked) = when (badge) {
                Badge.FIRST_QUESTION ->
                    1 to (stats.totalQuestions >= 1)

                Badge.HUNDRED_QUESTIONS ->
                    stats.totalQuestions to (stats.totalQuestions >= 100)

                Badge.TEN_MASTERED ->
                    stats.masteredQuestions to (stats.masteredQuestions >= 10)

                Badge.HUNDRED_REVIEWS ->
                    stats.totalReviews to (stats.totalReviews >= 100)

                Badge.SEVEN_DAY_STREAK ->
                    streakDays(stats.studyDays, today) to
                        (streakDays(stats.studyDays, today) >= 7)

                Badge.THIRTY_DAY_STREAK ->
                    streakDays(stats.studyDays, today) to
                        (streakDays(stats.studyDays, today) >= 30)

                Badge.KNOWLEDGE_CLEARED ->
                    stats.clearedKnowledgePoints to
                        (stats.clearedKnowledgePoints >= 1)

                Badge.TEN_IN_ONE_DAY ->
                    stats.maxDailyEntries to (stats.maxDailyEntries >= 10)

                Badge.FIRST_WORD ->
                    1 to stats.firstWordRecorded

                Badge.HUNDRED_WORDS ->
                    stats.masteredWords to (stats.masteredWords >= 100)
            }
            BadgeProgress(badge, current, unlocked)
        }

    /**
     * 连续学习天数：从 [today] 往前数，[studyDays] 里连续不断的天数。
     * 今天还没学习不算断（从昨天开始也认）——「今天还没开始」不该把
     * 坚持了 6 天的人瞬间判为 0 天。
     */
    internal fun streakDays(studyDays: Set<LocalDate>, today: LocalDate): Int {
        var cursor = if (studyDays.contains(today)) today else today.minusDays(1)
        var streak = 0
        while (studyDays.contains(cursor)) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }
}
