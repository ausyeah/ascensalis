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

/** 徽章目录（v0.0.57 扩到 48 枚，全部本地计算，不联网）。 */
enum class Badge(
    val key: String,
    val title: String,
    val conditionText: String,
    /** 目标量（进度型徽章）；达成型（无进度概念）填 0。 */
    val target: Int
) {
    // ---- 录入错题（10）----
    FIRST_QUESTION("first_question", "入门", "第一次录入错题", 0),
    TEN_QUESTIONS("ten_questions", "十题起步", "累计录入 10 道错题", 10),
    Q25("q25", "积少成多", "累计录入 25 道错题", 25),
    Q50("q50", "半百之约", "累计录入 50 道错题", 50),
    HUNDRED_QUESTIONS("hundred_questions", "百题斩", "累计录入 100 道错题", 100),
    Q150("q150", "一百五", "累计录入 150 道错题", 150),
    Q200("q200", "双百", "累计录入 200 道错题", 200),
    Q300("q300", "三百里程", "累计录入 300 道错题", 300),
    Q500("q500", "五百题", "累计录入 500 道错题", 500),
    Q1000("q1000", "千题之路", "累计录入 1000 道错题", 1000),

    // ---- 掌握错题（8）----
    M1("m1", "首次毕业", "第一道错题进入已掌握", 1),
    M5("m5", "小有所成", "累计 5 道错题进入已掌握", 5),
    TEN_MASTERED("ten_mastered", "毕业典礼", "累计 10 道错题进入已掌握", 10),
    M25("m25", "融会贯通", "累计 25 道错题进入已掌握", 25),
    M50("m50", "半百通关", "累计 50 道错题进入已掌握", 50),
    M100("m100", "百题掌握", "累计 100 道错题进入已掌握", 100),
    M200("m200", "双百通关", "累计 200 道错题进入已掌握", 200),
    M500("m500", "五百通关", "累计 500 道错题进入已掌握", 500),

    // ---- 复习次数（8）----
    R10("r10", "温故十次", "累计完成 10 次复习", 10),
    R50("r50", "复习五十", "累计完成 50 次复习", 50),
    HUNDRED_REVIEWS("hundred_reviews", "温故知新", "累计完成 100 次复习", 100),
    R250("r250", "复习二百五", "累计完成 250 次复习", 250),
    R500("r500", "五百复习", "累计完成 500 次复习", 500),
    R1000("r1000", "千次复习", "累计完成 1000 次复习", 1000),
    R2000("r2000", "两千次", "累计完成 2000 次复习", 2000),
    R5000("r5000", "五千次", "累计完成 5000 次复习", 5000),

    // ---- 连续学习（8）----
    S3("s3", "三日之约", "连续 3 天有学习行为", 3),
    SEVEN_DAY_STREAK("seven_day_streak", "七日之约", "连续 7 天有学习行为", 7),
    S14("s14", "两周坚持", "连续 14 天有学习行为", 14),
    S21("s21", "三周坚持", "连续 21 天有学习行为", 21),
    THIRTY_DAY_STREAK("thirty_day_streak", "一月之诺", "连续 30 天有学习行为", 30),
    S60("s60", "双月之诺", "连续 60 天有学习行为", 60),
    S100("s100", "百日之约", "连续 100 天有学习行为", 100),
    S365("s365", "一年之约", "连续 365 天有学习行为", 365),

    // ---- 单日爆发（5）----
    D3("d3", "单日三连", "一天内录入 3 道错题", 3),
    D5("d5", "单日五连", "一天内录入 5 道错题", 5),
    TEN_IN_ONE_DAY("ten_in_one_day", "单日十连", "一天内录入 10 道错题", 10),
    D20("d20", "单日二十", "一天内录入 20 道错题", 20),
    D30("d30", "单日三十", "一天内录入 30 道错题", 30),

    // ---- 知识点清零（4）----
    KNOWLEDGE_CLEARED("knowledge_cleared", "知识点清零", "任一知识点从有错题到全部毕业", 0),
    K3("k3", "三关清零", "3 个知识点全部毕业", 3),
    K5("k5", "五关清零", "5 个知识点全部毕业", 5),
    K10("k10", "十关清零", "10 个知识点全部毕业", 10),

    // ---- 单词（5）----
    FIRST_WORD("first_word", "初识词汇", "单词书首次录词", 0),
    W50("w50", "五十词", "累计掌握 50 个单词", 50),
    HUNDRED_WORDS("hundred_words", "百词斩", "累计掌握 100 个单词", 100),
    W200("w200", "两百词", "累计掌握 200 个单词", 200),
    W500("w500", "五百词", "累计掌握 500 个单词", 500);

    /** 徽章分类：成就墙按类分组展示。 */
    val category: BadgeCategory
        get() = when (this) {
            FIRST_QUESTION, TEN_QUESTIONS, Q25, Q50, HUNDRED_QUESTIONS,
            Q150, Q200, Q300, Q500, Q1000 -> BadgeCategory.QUESTIONS

            M1, M5, TEN_MASTERED, M25, M50, M100, M200, M500 -> BadgeCategory.MASTERY
            R10, R50, HUNDRED_REVIEWS, R250, R500, R1000, R2000, R5000 -> BadgeCategory.REVIEWS
            S3, SEVEN_DAY_STREAK, S14, S21, THIRTY_DAY_STREAK, S60, S100, S365 ->
                BadgeCategory.STREAK
            D3, D5, TEN_IN_ONE_DAY, D20, D30 -> BadgeCategory.DAILY
            KNOWLEDGE_CLEARED, K3, K5, K10 -> BadgeCategory.KNOWLEDGE
            FIRST_WORD, W50, HUNDRED_WORDS, W200, W500 -> BadgeCategory.WORDS
        }
}

/** 徽章分类（仅用于墙上的分组标题与配色）。 */
enum class BadgeCategory(val label: String) {
    QUESTIONS("录入"),
    MASTERY("掌握"),
    REVIEWS("复习"),
    STREAK("坚持"),
    DAILY("爆发"),
    KNOWLEDGE("清零"),
    WORDS("单词")
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
            val streak = streakDays(stats.studyDays, today)
            val (current, unlocked) = when (badge) {
                // ---- 录入 ----
                Badge.FIRST_QUESTION -> 1 to (stats.totalQuestions >= 1)
                Badge.TEN_QUESTIONS -> stats.totalQuestions to (stats.totalQuestions >= 10)
                Badge.Q25 -> stats.totalQuestions to (stats.totalQuestions >= 25)
                Badge.Q50 -> stats.totalQuestions to (stats.totalQuestions >= 50)
                Badge.HUNDRED_QUESTIONS -> stats.totalQuestions to (stats.totalQuestions >= 100)
                Badge.Q150 -> stats.totalQuestions to (stats.totalQuestions >= 150)
                Badge.Q200 -> stats.totalQuestions to (stats.totalQuestions >= 200)
                Badge.Q300 -> stats.totalQuestions to (stats.totalQuestions >= 300)
                Badge.Q500 -> stats.totalQuestions to (stats.totalQuestions >= 500)
                Badge.Q1000 -> stats.totalQuestions to (stats.totalQuestions >= 1000)

                // ---- 掌握 ----
                Badge.M1 -> stats.masteredQuestions to (stats.masteredQuestions >= 1)
                Badge.M5 -> stats.masteredQuestions to (stats.masteredQuestions >= 5)
                Badge.TEN_MASTERED -> stats.masteredQuestions to (stats.masteredQuestions >= 10)
                Badge.M25 -> stats.masteredQuestions to (stats.masteredQuestions >= 25)
                Badge.M50 -> stats.masteredQuestions to (stats.masteredQuestions >= 50)
                Badge.M100 -> stats.masteredQuestions to (stats.masteredQuestions >= 100)
                Badge.M200 -> stats.masteredQuestions to (stats.masteredQuestions >= 200)
                Badge.M500 -> stats.masteredQuestions to (stats.masteredQuestions >= 500)

                // ---- 复习 ----
                Badge.R10 -> stats.totalReviews to (stats.totalReviews >= 10)
                Badge.R50 -> stats.totalReviews to (stats.totalReviews >= 50)
                Badge.HUNDRED_REVIEWS -> stats.totalReviews to (stats.totalReviews >= 100)
                Badge.R250 -> stats.totalReviews to (stats.totalReviews >= 250)
                Badge.R500 -> stats.totalReviews to (stats.totalReviews >= 500)
                Badge.R1000 -> stats.totalReviews to (stats.totalReviews >= 1000)
                Badge.R2000 -> stats.totalReviews to (stats.totalReviews >= 2000)
                Badge.R5000 -> stats.totalReviews to (stats.totalReviews >= 5000)

                // ---- 连续学习 ----
                Badge.S3 -> streak to (streak >= 3)
                Badge.SEVEN_DAY_STREAK -> streak to (streak >= 7)
                Badge.S14 -> streak to (streak >= 14)
                Badge.S21 -> streak to (streak >= 21)
                Badge.THIRTY_DAY_STREAK -> streak to (streak >= 30)
                Badge.S60 -> streak to (streak >= 60)
                Badge.S100 -> streak to (streak >= 100)
                Badge.S365 -> streak to (streak >= 365)

                // ---- 单日爆发 ----
                Badge.D3 -> stats.maxDailyEntries to (stats.maxDailyEntries >= 3)
                Badge.D5 -> stats.maxDailyEntries to (stats.maxDailyEntries >= 5)
                Badge.TEN_IN_ONE_DAY -> stats.maxDailyEntries to (stats.maxDailyEntries >= 10)
                Badge.D20 -> stats.maxDailyEntries to (stats.maxDailyEntries >= 20)
                Badge.D30 -> stats.maxDailyEntries to (stats.maxDailyEntries >= 30)

                // ---- 知识点清零 ----
                // 首枚是「任一知识点」的达成型（target=0），其余按数量计进度。
                Badge.KNOWLEDGE_CLEARED ->
                    stats.clearedKnowledgePoints to (stats.clearedKnowledgePoints >= 1)
                Badge.K3 -> stats.clearedKnowledgePoints to (stats.clearedKnowledgePoints >= 3)
                Badge.K5 -> stats.clearedKnowledgePoints to (stats.clearedKnowledgePoints >= 5)
                Badge.K10 -> stats.clearedKnowledgePoints to (stats.clearedKnowledgePoints >= 10)

                // ---- 单词 ----
                Badge.FIRST_WORD -> 1 to stats.firstWordRecorded
                Badge.W50 -> stats.masteredWords to (stats.masteredWords >= 50)
                Badge.HUNDRED_WORDS -> stats.masteredWords to (stats.masteredWords >= 100)
                Badge.W200 -> stats.masteredWords to (stats.masteredWords >= 200)
                Badge.W500 -> stats.masteredWords to (stats.masteredWords >= 500)
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
