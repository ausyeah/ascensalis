package com.mistakebook.report

import com.mistakebook.data.local.MistakeBookDatabase
import com.mistakebook.data.local.entities.WeeklyReport
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.net.ApiResult
import com.mistakebook.pipeline.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 周报生成编排（F2）：聚合统计 → LLM 诊断 → 覆盖落库。
 *
 * 放在 report 包而不是 data/repos：它和 RecognitionEngine 一样是
 * 「跨数据层 + 网络层」的编排者，依赖方向 data ← report → net，
 * 与 data ← pipeline → net 完全同构。
 */
class WeeklyReportGenerator(
    private val database: MistakeBookDatabase,
    private val llmClient: LlmClient,
    private val settingsStore: SettingsStore
) {

    sealed interface Result {
        /** 生成成功并已落库。 */
        data class Done(val report: WeeklyReport) : Result

        /** 未生成，[reason] 可直接展示。 */
        data class Failed(val reason: String) : Result
    }

    /** 生成 [today] 所在周的周报（同周重生成覆盖）。 */
    suspend fun generate(today: LocalDate = LocalDate.now()): Result = withContext(Dispatchers.IO) {
        val profile = settingsStore.snapshotNow().activeProfile
        if (profile == null || !profile.isConfigured()) {
            return@withContext Result.Failed("请先到设置填写大模型 API Key")
        }

        val weekStart = today.with(DayOfWeek.MONDAY)
        val weekEnd = weekStart.plusDays(6)
        val stats = collectStats(weekStart, weekEnd)

        val raw = llmClient.refineJson(
            profile = profile,
            systemPrompt = WeeklyReportPrompts.system(),
            userPrompt = WeeklyReportPrompts.user(stats),
            imageFile = null,
            longEdgePx = 0,
            attachImage = false
        )
        val markdown = when (raw) {
            is ApiResult.Success -> WeeklyReportParser.parse(raw.data)
            is ApiResult.Failure -> return@withContext Result.Failed(
                raw.error.serverMessage.ifBlank { "生成失败，请检查网络后重试" }
            )
        }
        if (markdown == null) {
            return@withContext Result.Failed("AI 没能生成有效报告，请重试")
        }

        val report = WeeklyReport(
            weekStartEpochDay = weekStart.toEpochDay(),
            weekEndEpochDay = weekEnd.toEpochDay(),
            markdown = markdown,
            statsJson = stats.toJson(),
            createdAt = System.currentTimeMillis()
        )
        database.weeklyReportDao().upsert(report)
        Result.Done(report)
    }

    private suspend fun collectStats(weekStart: LocalDate, weekEnd: LocalDate): WeeklyStats {
        val questionDao = database.questionDao()
        val sinceMs = weekStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // observeTotalCount 的口径是「未删除总数」（含已掌握），未掌握数取差值，
        // 不为它单加一条 DAO 查询
        val totalNonDeleted = questionDao.observeTotalCountSnapshot()
        val totalMastered = questionDao.countMastered()
        return WeeklyStatsBuilder.build(
            weekStartEpochDay = weekStart.toEpochDay(),
            weekEndEpochDay = weekEnd.toEpochDay(),
            totalActive = totalNonDeleted - totalMastered,
            totalMastered = totalMastered,
            newThisWeek = questionDao.countCreatedSince(sinceMs),
            reasonRows = questionDao.reasonDistribution().map { it.name to it.count },
            reviewRows = database.reviewLogDao().resultCountsSince(sinceMs).map { it.name to it.count },
            knowledgePointJsons = questionDao.allKnowledgePointsJson()
        )
    }
}

/**
 * observeTotalCount 是 Flow（列表页角标用），周报需要一次性值。
 * 这个小包装避免给 DAO 加一条语义重复的 suspend 查询。
 */
private suspend fun com.mistakebook.data.local.QuestionDao.observeTotalCountSnapshot(): Int =
    observeTotalCount().first()
