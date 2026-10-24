package com.mistakebook.data.repos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 复习闭环落库链路的源码契约（T1）。
 *
 * JVM 单测没有 Room in-memory 先例（项目零仪器测试基建，见
 * MigrationRegistrationTest 的取舍说明），但 applyReviewResult 的
 * **原子性**靠 `@Transaction` 保证——这个注解漏掉不会有任何编译错误，
 * 只会在极端情况（进程被杀在两步之间）下留下「日志有了、队列没变」的
 * 半更新。用源码扫描把这几条线钉死：谁改掉了注解或拆散了两步，这里就红。
 */
class ReviewChainContractTest {

    private val daoSource: String by lazy {
        readSource("QuestionDao.kt", "src/main/java/com/mistakebook/data/local/QuestionDao.kt")
    }
    private val repoSource: String by lazy {
        readSource("QuestionRepository.kt", "src/main/java/com/mistakebook/data/repos/QuestionRepository.kt")
    }
    private val rulesSource: String by lazy {
        readSource("ReviewRules.kt", "src/main/java/com/mistakebook/domain/ReviewRules.kt")
    }

    private fun readSource(name: String, vararg paths: String): String {
        val candidates = paths.map { File(it) } +
            paths.map { File("app/$it") }
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $name，已尝试：${candidates.map { it.path }}")
        return file.readText()
    }

    @Test
    fun `DAO 的 applyReviewResult 必须带 Transaction 注解`() {
        // 截取 applyReviewResult 声明前的注解区
        val idx = daoSource.indexOf("suspend fun applyReviewResult(")
        assertTrue("QuestionDao 里找不到 applyReviewResult", idx >= 0)
        val header = daoSource.substring(maxOf(0, idx - 200), idx)
        assertTrue(
            "applyReviewResult 丢了 @Transaction：两步落库不再原子，进程被杀会留半更新",
            Regex("@Transaction\\s*$").containsMatchIn(header.trimEnd())
        )
    }

    @Test
    fun `DAO 事务体必须先插日志再更新题目`() {
        val body = Regex("applyReviewResult\\(log: [^)]*\\)\\s*\\{([\\s\\S]*?)\\n    \\}")
            .find(daoSource)?.groupValues?.get(1)
            ?: error("找不到 applyReviewResult 方法体")
        assertTrue("事务里没有插入 review_log", body.contains("insertReviewLog(log)"))
        assertTrue("事务里没有更新 question", body.contains("update(updated)"))
    }

    @Test
    fun `仓库层必须挡住已删除的题`() {
        val idx = repoSource.indexOf("suspend fun applyReviewResult(")
        assertTrue("QuestionRepository 里找不到 applyReviewResult", idx >= 0)
        val body = repoSource.substring(idx, minOf(repoSource.length, idx + 2000))
        assertTrue(
            "applyReviewResult 没有检查 deletedAt：复习中途被删的题会照常推进",
            body.contains("deletedAt != null")
        )
        assertTrue(
            "applyReviewResult 没有处理题目不存在的情况",
            body.contains("?: return false")
        )
    }

    @Test
    fun `到达 MAX_STAGE 时 nextReviewAt 必须清空`() {
        // 与 setMastered() 的约定对齐：掌握后不再进队列与每日提醒
        val idx = repoSource.indexOf("suspend fun applyReviewResult(")
        val body = repoSource.substring(idx, minOf(repoSource.length, idx + 2200))
        assertTrue(
            "达到 MAX_STAGE 时应把 nextReviewAt 置 null（对齐 setMastered 约定）",
            body.contains("nextReviewAt = if (newStage >= ReviewRules.MAX_STAGE)")
        )
    }

    @Test
    fun `仓库层推进语义必须委托 ReviewRules 而非内联`() {
        // 推进规则只允许存在一份（见 ReviewProgressionTest）：仓库层若内联
        // 自己的间隔计算，两处规则迟早漂移
        val idx = repoSource.indexOf("suspend fun applyReviewResult(")
        val body = repoSource.substring(idx, minOf(repoSource.length, idx + 2200))
        assertTrue(body.contains("ReviewRules.nextStage"))
        assertTrue(body.contains("ReviewRules.nextReviewDay"))
        assertTrue(body.contains("ReviewRules.statusFor"))
    }
}
