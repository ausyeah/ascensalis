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
 * @param focusMinutes 番茄钟累计专注分钟数（不含休息）。
 * @param pomodoroCount 累计完成的番茄钟次数。
 * @param focusDays 有专注记录的**去重日期数**（yyyy-MM-dd 字符串的天数）。
 * @param wordStudyDays 有背单词记录的去重日期数。
 * @param quizCorrect 背单词自测累计答对题数。
 * @param chatSessions 累计创建的 AI 对话会话数。
 * @param libraryDocs 资料库累计导入的文档数（去重文件名）。
 * @param unlockedBadges 已解锁徽章数（评估时点的库内计数——「全图鉴」这类
 *   收藏型徽章按它判定，本轮新解锁的下一轮评估才计入，天然滞后一轮）。
 */
data class BadgeStats(
    val totalQuestions: Int,
    val masteredQuestions: Int,
    val totalReviews: Int,
    val maxDailyEntries: Int,
    val studyDays: Set<LocalDate>,
    val clearedKnowledgePoints: Int,
    val firstWordRecorded: Boolean,
    val masteredWords: Int,
    val focusMinutes: Int = 0,
    val pomodoroCount: Int = 0,
    val focusDays: Int = 0,
    val wordStudyDays: Int = 0,
    val quizCorrect: Int = 0,
    val chatSessions: Int = 0,
    val libraryDocs: Int = 0,
    val unlockedBadges: Int = 0
)

/** 徽章目录（v0.0.125 扩到 100 枚，全部本地计算，不联网）。 */
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

    // ---- 单词（5 + 7 = 12）----
    FIRST_WORD("first_word", "初识词汇", "单词书首次录词", 0),
    W50("w50", "五十词", "累计掌握 50 个单词", 50),
    HUNDRED_WORDS("hundred_words", "百词斩", "累计掌握 100 个单词", 100),
    W200("w200", "两百词", "累计掌握 200 个单词", 200),
    W500("w500", "五百词", "累计掌握 500 个单词", 500),
    W1000("w1000", "千词之路", "累计掌握 1000 个单词", 1000),
    W2000("w2000", "两千词", "累计掌握 2000 个单词", 2000),
    WD7("wd7", "七日背词", "累计 7 天有背单词记录", 7),
    WD30("wd30", "三十日背词", "累计 30 天有背单词记录", 30),
    WD100("wd100", "百日背词", "累计 100 天有背单词记录", 100),
    WA100("wa100", "自测百题", "背单词自测累计答对 100 题", 100),
    WA1000("wa1000", "自测千题", "背单词自测累计答对 1000 题", 1000),

    // ---- 番茄专注（15，v0.0.125）----
    FIRST_FOCUS("first_focus", "首次专注", "第一次完成一次专注计时", 0),
    FOCUS_TEN("focus_ten", "专注十次", "累计完成 10 次番茄钟", 10),
    FOCUS_FIFTY("focus_fifty", "专注五十", "累计完成 50 次番茄钟", 50),
    FOCUS_HUNDRED("focus_hundred", "专注百次", "累计完成 100 次番茄钟", 100),
    FOCUS_FIVE_HUNDRED("focus_five_hundred", "五百专注", "累计完成 500 次番茄钟", 500),
    FOCUS_THOUSAND("focus_thousand", "千次专注", "累计完成 1000 次番茄钟", 1000),
    FOCUS_HALF_HOUR("focus_half_hour", "半小时之约", "累计专注满 30 分钟", 30),
    FOCUS_HOUR("focus_hour", "一小时之约", "累计专注满 60 分钟", 60),
    FOCUS_TEN_HOURS("focus_ten_hours", "十小时之约", "累计专注满 600 分钟", 600),
    FIFTY_HOURS("fifty_hours", "五十小时", "累计专注满 3000 分钟", 3000),
    HUNDRED_HOURS("hundred_hours", "百小时殿堂", "累计专注满 10000 分钟", 10000),
    FOCUS_DAYS_3("focus_days_3", "专注三日", "累计 3 天有专注记录", 3),
    FOCUS_DAYS_10("focus_days_10", "十日专注", "累计 10 天有专注记录", 10),
    FOCUS_DAYS_30("focus_days_30", "三十日专注", "累计 30 天有专注记录", 30),
    FOCUS_DAYS_100("focus_days_100", "百日专注", "累计 100 天有专注记录", 100),

    // ---- AI 问答（5，v0.0.125）----
    FIRST_CHAT("first_chat", "开口第一问", "第一次与 AI 对话", 0),
    CHAT_TEN("chat_ten", "十次问答", "累计创建 10 个对话", 10),
    CHAT_FIFTY("chat_fifty", "五十次问答", "累计创建 50 个对话", 50),
    CHAT_HUNDRED("chat_hundred", "百次问答", "累计创建 100 个对话", 100),
    CHAT_TWO_HUNDRED("chat_two_hundred", "两百次问答", "累计创建 200 个对话", 200),

    // ---- 资料库（5，v0.0.125）----
    FIRST_DOC("first_doc", "建立索引", "资料库首次导入文档并建立索引", 0),
    DOCS_FIVE("docs_five", "五份资料", "资料库累计导入 5 份文档", 5),
    DOCS_TWENTY("docs_twenty", "二十份资料", "资料库累计导入 20 份文档", 20),
    DOCS_FIFTY("docs_fifty", "五十份资料", "资料库累计导入 50 份文档", 50),
    DOCS_HUNDRED("docs_hundred", "百份资料", "资料库累计导入 100 份文档", 100),

    // ---- 徽章收藏（5，v0.0.125）----
    BADGE_ROOKIE("badge_rookie", "徽章新秀", "解锁 5 枚徽章", 5),
    BADGE_COLLECTOR("badge_collector", "收藏家", "解锁 20 枚徽章", 20),
    BADGE_MASTER("badge_master", "徽章大师", "解锁 40 枚徽章", 40),
    BADGE_ELDER("badge_elder", "资深藏家", "解锁 75 枚徽章", 75),
    BADGE_GALLERY("badge_gallery", "全图鉴", "解锁除本枚外的全部徽章", 99),

    // ---- 旧阶梯补长（9，v0.0.125）----
    Q2000("q2000", "双千题", "累计录入 2000 道错题", 2000),
    Q3000("q3000", "三千题", "累计录入 3000 道错题", 3000),
    Q5000("q5000", "五千题", "累计录入 5000 道错题", 5000),
    M1000("m1000", "千题毕业", "累计 1000 道错题进入已掌握", 1000),
    M2000("m2000", "两千毕业", "累计 2000 道错题进入已掌握", 2000),
    M5000("m5000", "五千毕业", "累计 5000 道错题进入已掌握", 5000),
    R10000("r10000", "万次复习", "累计完成 10000 次复习", 10000),
    R20000("r20000", "两万次", "累计完成 20000 次复习", 20000),
    S200("s200", "二百日之约", "连续 200 天有学习行为", 200),
    S500("s500", "五百日之约", "连续 500 天有学习行为", 500),
    S1000("s1000", "千日之约", "连续 1000 天有学习行为", 1000),
    D50("d50", "单日五十", "一天内录入 50 道错题", 50),
    D100("d100", "单日百题", "一天内录入 100 道错题", 100),
    K25("k25", "二十五关", "25 个知识点全部毕业", 25),
    K50("k50", "五十关清零", "50 个知识点全部毕业", 50);

    /** 徽章分类：成就墙按类分组展示。 */
    val category: BadgeCategory
        get() = when (this) {
            FIRST_QUESTION, TEN_QUESTIONS, Q25, Q50, HUNDRED_QUESTIONS,
            Q150, Q200, Q300, Q500, Q1000, Q2000, Q3000, Q5000 ->
                BadgeCategory.QUESTIONS

            M1, M5, TEN_MASTERED, M25, M50, M100, M200, M500,
            M1000, M2000, M5000 -> BadgeCategory.MASTERY
            R10, R50, HUNDRED_REVIEWS, R250, R500, R1000, R2000, R5000,
            R10000, R20000 -> BadgeCategory.REVIEWS
            S3, SEVEN_DAY_STREAK, S14, S21, THIRTY_DAY_STREAK, S60, S100, S365,
            S200, S500, S1000 -> BadgeCategory.STREAK
            D3, D5, TEN_IN_ONE_DAY, D20, D30, D50, D100 -> BadgeCategory.DAILY
            KNOWLEDGE_CLEARED, K3, K5, K10, K25, K50 -> BadgeCategory.KNOWLEDGE
            FIRST_WORD, W50, HUNDRED_WORDS, W200, W500, W1000, W2000,
            WD7, WD30, WD100, WA100, WA1000 -> BadgeCategory.WORDS
            FIRST_FOCUS, FOCUS_TEN, FOCUS_FIFTY, FOCUS_HUNDRED, FOCUS_FIVE_HUNDRED,
            FOCUS_THOUSAND, FOCUS_HALF_HOUR, FOCUS_HOUR, FOCUS_TEN_HOURS,
            FIFTY_HOURS, HUNDRED_HOURS, FOCUS_DAYS_3, FOCUS_DAYS_10,
            FOCUS_DAYS_30, FOCUS_DAYS_100 -> BadgeCategory.POMODORO
            FIRST_CHAT, CHAT_TEN, CHAT_FIFTY, CHAT_HUNDRED, CHAT_TWO_HUNDRED ->
                BadgeCategory.CHAT
            FIRST_DOC, DOCS_FIVE, DOCS_TWENTY, DOCS_FIFTY, DOCS_HUNDRED ->
                BadgeCategory.LIBRARY
            BADGE_ROOKIE, BADGE_COLLECTOR, BADGE_MASTER, BADGE_ELDER, BADGE_GALLERY ->
                BadgeCategory.META
        }

    /**
     * 难度排序键：成就墙上「越难的越靠后」按它比大小。
     *
     * target = 0 的达成型徽章（入门 / 知识点清零 / 初识词汇这类「第一次」）
     * 算最易，排最前；其余按目标量升序——要凑的量越大越难拿到。
     * 跨类比的是原始目标量（复习 5000 次难于录入 1000 道），粗糙但可预期，
     * 而且同一枚徽章只有一个档位，不需要更精细的模型。
     */
    val difficulty: Int
        get() = if (target == 0) 0 else target
}

/** 徽章分类：v0.0.86 起墙面不再按类分区（改成「已解锁 → 未解锁」按难度排），
 * 分类只用于缺美术素材时的程序化徽章配色，以及详情弹窗里标注这枚章属于哪一类。 */
enum class BadgeCategory(val label: String) {
    QUESTIONS("录入"),
    MASTERY("掌握"),
    REVIEWS("复习"),
    STREAK("坚持"),
    DAILY("爆发"),
    KNOWLEDGE("清零"),
    WORDS("单词"),
    POMODORO("专注"),
    CHAT("问答"),
    LIBRARY("资料"),
    META("收藏")
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
                Badge.W1000 -> stats.masteredWords to (stats.masteredWords >= 1000)
                Badge.W2000 -> stats.masteredWords to (stats.masteredWords >= 2000)
                Badge.WD7 -> stats.wordStudyDays to (stats.wordStudyDays >= 7)
                Badge.WD30 -> stats.wordStudyDays to (stats.wordStudyDays >= 30)
                Badge.WD100 -> stats.wordStudyDays to (stats.wordStudyDays >= 100)
                Badge.WA100 -> stats.quizCorrect to (stats.quizCorrect >= 100)
                Badge.WA1000 -> stats.quizCorrect to (stats.quizCorrect >= 1000)

                // ---- 番茄专注（v0.0.125）----
                Badge.FIRST_FOCUS -> 1 to (stats.pomodoroCount >= 1)
                Badge.FOCUS_TEN -> stats.pomodoroCount to (stats.pomodoroCount >= 10)
                Badge.FOCUS_FIFTY -> stats.pomodoroCount to (stats.pomodoroCount >= 50)
                Badge.FOCUS_HUNDRED -> stats.pomodoroCount to (stats.pomodoroCount >= 100)
                Badge.FOCUS_FIVE_HUNDRED -> stats.pomodoroCount to (stats.pomodoroCount >= 500)
                Badge.FOCUS_THOUSAND -> stats.pomodoroCount to (stats.pomodoroCount >= 1000)
                Badge.FOCUS_HALF_HOUR -> stats.focusMinutes to (stats.focusMinutes >= 30)
                Badge.FOCUS_HOUR -> stats.focusMinutes to (stats.focusMinutes >= 60)
                Badge.FOCUS_TEN_HOURS -> stats.focusMinutes to (stats.focusMinutes >= 600)
                Badge.FIFTY_HOURS -> stats.focusMinutes to (stats.focusMinutes >= 3000)
                Badge.HUNDRED_HOURS -> stats.focusMinutes to (stats.focusMinutes >= 10000)
                Badge.FOCUS_DAYS_3 -> stats.focusDays to (stats.focusDays >= 3)
                Badge.FOCUS_DAYS_10 -> stats.focusDays to (stats.focusDays >= 10)
                Badge.FOCUS_DAYS_30 -> stats.focusDays to (stats.focusDays >= 30)
                Badge.FOCUS_DAYS_100 -> stats.focusDays to (stats.focusDays >= 100)

                // ---- AI 问答（v0.0.125）----
                Badge.FIRST_CHAT -> 1 to (stats.chatSessions >= 1)
                Badge.CHAT_TEN -> stats.chatSessions to (stats.chatSessions >= 10)
                Badge.CHAT_FIFTY -> stats.chatSessions to (stats.chatSessions >= 50)
                Badge.CHAT_HUNDRED -> stats.chatSessions to (stats.chatSessions >= 100)
                Badge.CHAT_TWO_HUNDRED -> stats.chatSessions to (stats.chatSessions >= 200)

                // ---- 资料库（v0.0.125）----
                Badge.FIRST_DOC -> 1 to (stats.libraryDocs >= 1)
                Badge.DOCS_FIVE -> stats.libraryDocs to (stats.libraryDocs >= 5)
                Badge.DOCS_TWENTY -> stats.libraryDocs to (stats.libraryDocs >= 20)
                Badge.DOCS_FIFTY -> stats.libraryDocs to (stats.libraryDocs >= 50)
                Badge.DOCS_HUNDRED -> stats.libraryDocs to (stats.libraryDocs >= 100)

                // ---- 徽章收藏（v0.0.125）----
                // 按「评估时点的库内计数」判定：本轮刚解锁的下一轮才计入，
                // 全图鉴（99）天然最后解锁。
                Badge.BADGE_ROOKIE -> stats.unlockedBadges to (stats.unlockedBadges >= 5)
                Badge.BADGE_COLLECTOR -> stats.unlockedBadges to (stats.unlockedBadges >= 20)
                Badge.BADGE_MASTER -> stats.unlockedBadges to (stats.unlockedBadges >= 40)
                Badge.BADGE_ELDER -> stats.unlockedBadges to (stats.unlockedBadges >= 75)
                Badge.BADGE_GALLERY -> stats.unlockedBadges to (stats.unlockedBadges >= 99)

                // ---- 旧阶梯补长（v0.0.125）----
                Badge.Q2000 -> stats.totalQuestions to (stats.totalQuestions >= 2000)
                Badge.Q3000 -> stats.totalQuestions to (stats.totalQuestions >= 3000)
                Badge.Q5000 -> stats.totalQuestions to (stats.totalQuestions >= 5000)
                Badge.M1000 -> stats.masteredQuestions to (stats.masteredQuestions >= 1000)
                Badge.M2000 -> stats.masteredQuestions to (stats.masteredQuestions >= 2000)
                Badge.M5000 -> stats.masteredQuestions to (stats.masteredQuestions >= 5000)
                Badge.R10000 -> stats.totalReviews to (stats.totalReviews >= 10000)
                Badge.R20000 -> stats.totalReviews to (stats.totalReviews >= 20000)
                Badge.S200 -> streak to (streak >= 200)
                Badge.S500 -> streak to (streak >= 500)
                Badge.S1000 -> streak to (streak >= 1000)
                Badge.D50 -> stats.maxDailyEntries to (stats.maxDailyEntries >= 50)
                Badge.D100 -> stats.maxDailyEntries to (stats.maxDailyEntries >= 100)
                Badge.K25 -> stats.clearedKnowledgePoints to (stats.clearedKnowledgePoints >= 25)
                Badge.K50 -> stats.clearedKnowledgePoints to (stats.clearedKnowledgePoints >= 50)
            }
            BadgeProgress(badge, current, unlocked)
        }

    /**
     * 成就墙的展示排序：**已解锁的在前，未解锁的在后**；两组内部都按
     * 难度升序（越难越靠后），同难度按枚举声明序兜底，保证顺序稳定可测。
     *
     * 排序规则必须放在 UI 之外：墙上有 48 枚，顺序错了用户只会看到
     * 「一面乱的墙」，而顺序本身是纯数据变换，抽成纯函数才能被单测钉住。
     *
     * @param unlockedKeys 库里已记录的解锁徽章 key（不是引擎算出的应解锁集合）
     *   ——墙上「有没有这枚章」只认库，引擎算出来但还没落库的那一瞬间不该插队。
     */
    fun sortForWall(badges: List<Badge>, unlockedKeys: Set<String>): List<Badge> =
        badges.sortedWith(
            compareBy(
                { badge: Badge -> if (badge.key in unlockedKeys) 0 else 1 },
                { badge: Badge -> badge.difficulty },
                { badge: Badge -> badge.ordinal }
            )
        )

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
