package com.mistakebook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 徽章判定引擎单测（任务书 T10 硬性要求：每枚徽章正反两例）。
 *
 * 引擎是纯函数，这里穷举每枚徽章的解锁/未解锁边界与进度计算。
 */
class BadgeEngineTest {

    private val today = LocalDate.of(2026, 10, 27)

    private fun empty() = BadgeStats(
        totalQuestions = 0,
        masteredQuestions = 0,
        totalReviews = 0,
        maxDailyEntries = 0,
        studyDays = emptySet(),
        clearedKnowledgePoints = 0,
        firstWordRecorded = false,
        masteredWords = 0
    )

    private fun unlockedKeys(stats: BadgeStats) =
        BadgeEngine.evaluate(stats, today).filter { it.unlocked }.map { it.badge.key }.toSet()

    private fun progressOf(stats: BadgeStats, badge: Badge) =
        BadgeEngine.evaluate(stats, today).first { it.badge == badge }

    // ---- 入门：第一次录入 ----

    @Test
    fun `first question unlocks on first entry`() {
        assertTrue(Badge.FIRST_QUESTION.key in unlockedKeys(empty().copy(totalQuestions = 1)))
        assertTrue(Badge.FIRST_QUESTION.key !in unlockedKeys(empty()))
        assertEquals(1, progressOf(empty().copy(totalQuestions = 1), Badge.FIRST_QUESTION).current)
    }

    // ---- 百题斩：累计 100 ----

    @Test
    fun `hundred questions boundary`() {
        assertFalse(
            Badge.HUNDRED_QUESTIONS.key in unlockedKeys(empty().copy(totalQuestions = 99))
        )
        assertTrue(
            Badge.HUNDRED_QUESTIONS.key in unlockedKeys(empty().copy(totalQuestions = 100))
        )
        assertEquals(
            99,
            progressOf(empty().copy(totalQuestions = 99), Badge.HUNDRED_QUESTIONS).current
        )
    }

    // ---- 毕业典礼：累计 10 道毕业 ----

    @Test
    fun `ten mastered boundary`() {
        assertFalse(Badge.TEN_MASTERED.key in unlockedKeys(empty().copy(masteredQuestions = 9)))
        assertTrue(Badge.TEN_MASTERED.key in unlockedKeys(empty().copy(masteredQuestions = 10)))
    }

    // ---- 温故知新：累计 100 次复习 ----

    @Test
    fun `hundred reviews boundary`() {
        assertFalse(Badge.HUNDRED_REVIEWS.key in unlockedKeys(empty().copy(totalReviews = 99)))
        assertTrue(Badge.HUNDRED_REVIEWS.key in unlockedKeys(empty().copy(totalReviews = 100)))
        assertEquals(50, progressOf(empty().copy(totalReviews = 50), Badge.HUNDRED_REVIEWS).current)
    }

    // ---- 七日之约 / 一月之诺：连续学习天数 ----

    @Test
    fun `seven day streak counts consecutive days including today`() {
        val days = (0L until 7L).map { today.minusDays(it) }.toSet()
        assertTrue(Badge.SEVEN_DAY_STREAK.key in unlockedKeys(empty().copy(studyDays = days)))
        assertTrue(Badge.THIRTY_DAY_STREAK.key !in unlockedKeys(empty().copy(studyDays = days)))
        assertEquals(7, progressOf(empty().copy(studyDays = days), Badge.SEVEN_DAY_STREAK).current)
    }

    @Test
    fun `thirty day streak needs full month`() {
        val days = (0L until 30L).map { today.minusDays(it) }.toSet()
        assertTrue(Badge.THIRTY_DAY_STREAK.key in unlockedKeys(empty().copy(studyDays = days)))
        val twentyNine = (0L until 29L).map { today.minusDays(it) }.toSet()
        assertFalse(
            Badge.THIRTY_DAY_STREAK.key in unlockedKeys(empty().copy(studyDays = twentyNine))
        )
    }

    @Test
    fun `streak gap breaks the chain`() {
        // 今天、昨天有，前天缺 → 连续 2 天
        val days = setOf(today, today.minusDays(1), today.minusDays(3))
        assertEquals(2, BadgeEngine.streakDays(days, today))
        assertFalse(Badge.SEVEN_DAY_STREAK.key in unlockedKeys(empty().copy(studyDays = days)))
    }

    @Test
    fun `streak tolerates today not yet studied`() {
        // 昨天起连续 7 天（今天还没开始学习）仍算 7 天——不把坚持清零
        val days = (1L until 8L).map { today.minusDays(it) }.toSet()
        assertEquals(7, BadgeEngine.streakDays(days, today))
        assertTrue(Badge.SEVEN_DAY_STREAK.key in unlockedKeys(empty().copy(studyDays = days)))
    }

    @Test
    fun `empty study days yield zero streak`() {
        assertEquals(0, BadgeEngine.streakDays(emptySet(), today))
        assertFalse(Badge.SEVEN_DAY_STREAK.key in unlockedKeys(empty()))
    }

    // ---- 知识点清零 ----

    @Test
    fun `knowledge cleared on first cleared point`() {
        assertTrue(
            Badge.KNOWLEDGE_CLEARED.key in unlockedKeys(
                empty().copy(clearedKnowledgePoints = 1)
            )
        )
        assertFalse(Badge.KNOWLEDGE_CLEARED.key in unlockedKeys(empty()))
    }

    // ---- 单日十连 ----

    @Test
    fun `ten in one day boundary`() {
        assertFalse(Badge.TEN_IN_ONE_DAY.key in unlockedKeys(empty().copy(maxDailyEntries = 9)))
        assertTrue(Badge.TEN_IN_ONE_DAY.key in unlockedKeys(empty().copy(maxDailyEntries = 10)))
    }

    // ---- 词汇类 ----

    @Test
    fun `first word on first vocab record`() {
        assertTrue(Badge.FIRST_WORD.key in unlockedKeys(empty().copy(firstWordRecorded = true)))
        assertFalse(Badge.FIRST_WORD.key in unlockedKeys(empty()))
    }

    @Test
    fun `hundred words boundary`() {
        assertFalse(Badge.HUNDRED_WORDS.key in unlockedKeys(empty().copy(masteredWords = 99)))
        assertTrue(Badge.HUNDRED_WORDS.key in unlockedKeys(empty().copy(masteredWords = 100)))
    }

    // ---- 综合：顺序与规模 ----

    @Test
    fun `evaluate returns every badge in order`() {
        val results = BadgeEngine.evaluate(empty(), today)
        assertEquals(Badge.entries.size, results.size)
        assertEquals(
            Badge.entries.map { it.key },
            results.map { it.badge.key }
        )
    }

    @Test
    fun `all badges unlock with saturated stats`() {
        // v0.0.125 目录扩到 100 枚，最高档到「五千题 / 五千毕业 / 两万复习 /
        // 千日坚持 / 单日百题 / 五十关清零 / 两千词 / 千次专注 / 百小时 /
        // 百日专注 / 自测千题 / 两百次问答 / 百份资料 / 全图鉴」。饱和值随之抬高。
        val stats = empty().copy(
            totalQuestions = 5000,
            masteredQuestions = 5000,
            totalReviews = 20000,
            maxDailyEntries = 100,
            studyDays = (0L until 1000L).map { today.minusDays(it) }.toSet(),
            clearedKnowledgePoints = 100,
            firstWordRecorded = true,
            masteredWords = 5000,
            focusMinutes = 10000,
            pomodoroCount = 1000,
            focusDays = 100,
            wordStudyDays = 100,
            quizCorrect = 1000,
            chatSessions = 200,
            libraryDocs = 100,
            // 全图鉴（99）按「评估时点的解锁数」判定：饱和口径下直接给满。
            unlockedBadges = 99
        )
        assertEquals(Badge.entries.size, unlockedKeys(stats).size)
    }

    @Test
    fun `every progress badge locks one below target and unlocks at target`() {
        // v0.0.125 起用**全目录通用边界**替代逐枚正反例：每枚进度型徽章
        // 把自己的维度压到 target-1 必须锁着，压到 target 必须解锁；
        // 其余维度全部饱和，保证只有这一维在挡路。
        val maxima = mapOf(
            "totalQuestions" to 5000, "masteredQuestions" to 5000,
            "totalReviews" to 20000, "maxDailyEntries" to 100,
            "studyDays" to 1000, "clearedKnowledgePoints" to 100,
            "masteredWords" to 5000, "focusMinutes" to 10000,
            "pomodoroCount" to 1000, "focusDays" to 100,
            "wordStudyDays" to 100, "quizCorrect" to 1000,
            "chatSessions" to 200, "libraryDocs" to 100
        )
        fun saturatedWith(dimensions: Map<String, Int>): BadgeStats {
            val v = dimensions.toMutableMap()
            v.merge("studyDays", 0, Int::plus)
            val streakDays = (0L until v.getValue("studyDays").toLong())
                .map { today.minusDays(it) }.toSet()
            return BadgeStats(
                totalQuestions = v.getValue("totalQuestions"),
                masteredQuestions = v.getValue("masteredQuestions"),
                totalReviews = v.getValue("totalReviews"),
                maxDailyEntries = v.getValue("maxDailyEntries"),
                studyDays = streakDays,
                clearedKnowledgePoints = v.getValue("clearedKnowledgePoints"),
                firstWordRecorded = true,
                masteredWords = v.getValue("masteredWords"),
                focusMinutes = v.getValue("focusMinutes"),
                pomodoroCount = v.getValue("pomodoroCount"),
                focusDays = v.getValue("focusDays"),
                wordStudyDays = v.getValue("wordStudyDays"),
                quizCorrect = v.getValue("quizCorrect"),
                chatSessions = v.getValue("chatSessions"),
                libraryDocs = v.getValue("libraryDocs"),
                unlockedBadges = 99
            )
        }
        Badge.entries.filter { it.target > 0 }.forEach { badge ->
            // 该徽章自己的维度（与 evaluate 的分支一一对应）
            val dim = when (badge) {
                Badge.M1, Badge.M5, Badge.TEN_MASTERED, Badge.M25, Badge.M50,
                Badge.M100, Badge.M200, Badge.M500, Badge.M1000, Badge.M2000,
                Badge.M5000 -> "masteredQuestions"
                Badge.S3, Badge.SEVEN_DAY_STREAK, Badge.S14, Badge.S21,
                Badge.THIRTY_DAY_STREAK, Badge.S60, Badge.S100, Badge.S365,
                Badge.S200, Badge.S500, Badge.S1000 -> "studyDays"
                Badge.D3, Badge.D5, Badge.TEN_IN_ONE_DAY, Badge.D20, Badge.D30,
                Badge.D50, Badge.D100 -> "maxDailyEntries"
                Badge.K3, Badge.K5, Badge.K10, Badge.K25, Badge.K50 ->
                    "clearedKnowledgePoints"
                Badge.W50, Badge.HUNDRED_WORDS, Badge.W200, Badge.W500,
                Badge.W1000, Badge.W2000 -> "masteredWords"
                Badge.WD7, Badge.WD30, Badge.WD100 -> "wordStudyDays"
                Badge.WA100, Badge.WA1000 -> "quizCorrect"
                Badge.FOCUS_TEN, Badge.FOCUS_FIFTY, Badge.FOCUS_HUNDRED,
                Badge.FOCUS_FIVE_HUNDRED, Badge.FOCUS_THOUSAND -> "pomodoroCount"
                Badge.FOCUS_HALF_HOUR, Badge.FOCUS_HOUR, Badge.FOCUS_TEN_HOURS,
                Badge.FIFTY_HOURS, Badge.HUNDRED_HOURS -> "focusMinutes"
                Badge.FOCUS_DAYS_3, Badge.FOCUS_DAYS_10, Badge.FOCUS_DAYS_30,
                Badge.FOCUS_DAYS_100 -> "focusDays"
                Badge.CHAT_TEN, Badge.CHAT_FIFTY, Badge.CHAT_HUNDRED,
                Badge.CHAT_TWO_HUNDRED -> "chatSessions"
                Badge.DOCS_FIVE, Badge.DOCS_TWENTY, Badge.DOCS_FIFTY,
                Badge.DOCS_HUNDRED -> "libraryDocs"
                Badge.BADGE_ROOKIE, Badge.BADGE_COLLECTOR, Badge.BADGE_MASTER,
                Badge.BADGE_ELDER, Badge.BADGE_GALLERY -> "unlockedBadges"
                else -> "totalQuestions"
            }
            // 「unlockedBadges」维度不在 maxima（它是库内计数，直接给值）
            fun stats(value: Int): BadgeStats =
                if (dim == "unlockedBadges") {
                    saturatedWith(maxima).copy(unlockedBadges = value)
                } else {
                    saturatedWith(maxima + (dim to value))
                }
            val below = progressOf(stats(badge.target - 1), badge)
            assertFalse("${badge.key} 在 target-1 必须锁着", below.unlocked)
            assertEquals("${badge.key} 进度", badge.target - 1, below.current)
            val at = progressOf(stats(badge.target), badge)
            assertTrue("${badge.key} 在 target 必须解锁", at.unlocked)
        }
    }

    // ---- 成就墙排序（v0.0.86 重排版）----

    @Test
    fun `achievement type badges rank as easiest`() {
        // target = 0 的「第一次」类徽章排最前，比 target = 1 的首次毕业还容易。
        assertEquals(0, Badge.FIRST_QUESTION.difficulty)
        assertEquals(0, Badge.KNOWLEDGE_CLEARED.difficulty)
        assertEquals(0, Badge.FIRST_WORD.difficulty)
        assertEquals(1, Badge.M1.difficulty)
        assertEquals(5000, Badge.R5000.difficulty)
    }

    @Test
    fun `wall sort puts unlocked first then by difficulty`() {
        val unlocked = setOf(Badge.R5000.key, Badge.FIRST_QUESTION.key)
        val sorted = BadgeEngine.sortForWall(Badge.entries, unlocked)

        // 已解锁的两枚必须占据最前面，哪怕其中一枚是全场最难的五千次复习
        val head = sorted.take(2).map { it.key }.toSet()
        assertEquals(unlocked, head)
        // 已解锁内部也按难度：入门（0）先于五千次复习（5000）
        assertEquals(Badge.FIRST_QUESTION.key, sorted.first().key)
        // 未解锁段必须严格难度不减
        val locked = sorted.drop(2)
        assertEquals(locked.sortedBy { it.difficulty }, locked)
        assertEquals(Badge.entries.size, sorted.size)
    }

    @Test
    fun `wall sort is stable for equal difficulty`() {
        // 三日之约与单日三连同为难度 3：顺序按枚举声明序兜底，不随输入顺序抖动
        val a = listOf(Badge.D3, Badge.S3, Badge.FIRST_QUESTION)
        val b = listOf(Badge.FIRST_QUESTION, Badge.S3, Badge.D3)
        val sortedA = BadgeEngine.sortForWall(a, emptySet())
        assertEquals(sortedA, BadgeEngine.sortForWall(b, emptySet()))
        assertEquals(
            listOf(Badge.FIRST_QUESTION.key, Badge.S3.key, Badge.D3.key),
            sortedA.map { it.key }
        )
    }

    @Test
    fun `wall sort with nothing unlocked is pure difficulty order`() {
        val sorted = BadgeEngine.sortForWall(Badge.entries, emptySet())
        assertEquals(sorted.sortedBy { it.difficulty }, sorted)
        // 最难的一枚压轴：两万次复习（v0.0.125 扩目录后全场最高目标）
        assertEquals(Badge.R20000.key, sorted.last().key)
    }
}
