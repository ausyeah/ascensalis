package com.mistakebook.mindmap

import com.mistakebook.data.local.MistakeBookDatabase
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.net.ApiResult
import com.mistakebook.pipeline.LlmClient
import com.mistakebook.report.WeeklyStats
import com.mistakebook.report.WeeklyStatsBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * 思维导图生成编排（F4）：薄弱点聚合 → LLM 出层级 markdown → 解析成树。
 * 与 WeeklyReportGenerator 同构（data+net 编排者）。
 */
class MindmapGenerator(
    private val database: MistakeBookDatabase,
    private val llmClient: LlmClient,
    private val settingsStore: SettingsStore
) {

    sealed interface Result {
        data class Done(val root: MindmapNode, val markdown: String) : Result
        data class Failed(val reason: String) : Result
    }

    /** 薄弱点榜上限：导图节点总量控制在 60 内，喂太多只会让模型发散。 */
    private val maxWeakPoints = 30

    suspend fun generate(subjectId: Long?, subjectName: String): Result = withContext(Dispatchers.IO) {
        val profile = settingsStore.snapshotNow().activeProfile
        if (profile == null || !profile.isConfigured()) {
            return@withContext Result.Failed("请先到设置填写大模型 API Key")
        }

        val kpJsons = database.questionDao().allKnowledgePointsJsonOf(subjectId ?: 0L)
        val weakPoints = WeeklyStatsBuilder.topKnowledgePoints(kpJsons, maxWeakPoints)

        val raw = llmClient.refineJson(
            profile = profile,
            systemPrompt = MindmapPrompts.system(subjectName),
            userPrompt = MindmapPrompts.user(subjectName, weakPoints),
            imageFile = null,
            longEdgePx = 0,
            attachImage = false
        )
        val markdown = when (raw) {
            is ApiResult.Success -> extractMarkdown(raw.data)
            is ApiResult.Failure -> return@withContext Result.Failed(
                raw.error.serverMessage.ifBlank { "生成失败，请检查网络后重试" }
            )
        }
        if (markdown == null) {
            return@withContext Result.Failed("AI 没能生成有效的导图，请重试")
        }
        // 防偷工减料：薄弱点清单大面积缺席就拒绝出图，宁可不给也不给错的体系
        val missing = MindmapParser.missingKnowledgePoints(
            markdown, weakPoints.map { it.name }
        )
        if (weakPoints.isNotEmpty() && missing.size > weakPoints.size / 2) {
            return@withContext Result.Failed("生成的导图遗漏了大部分薄弱知识点，请重试")
        }
        Result.Done(MindmapParser.parse(markdown, subjectName), markdown)
    }

    /** 从 JSON（含围栏/花括号容错）里抠 markdown；裸 markdown（带 # 或列表）也收。 */
    private fun extractMarkdown(raw: String): String? {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val candidates = sequence {
            yield(raw)
            yield(stripFences(raw))
            yield(sliceBraces(raw))
            yield(sliceBraces(stripFences(raw)))
        }
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            val md = runCatching {
                json.parseToJsonElement(candidate)
                    .let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("mindmap_markdown")?.jsonPrimitive?.content
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (md != null) return md
        }
        // 裸 markdown 兜底：必须像导图（有标题或列表）
        val bare = stripFences(raw)
        if ((bare.contains("\n#") || bare.contains("\n-") || bare.startsWith("#") || bare.startsWith("-")) &&
            bare.trim().length >= 20
        ) return bare.trim()
        return null
    }

    private fun stripFences(text: String): String =
        text.trim()
            .replace(Regex("""^```[a-zA-Z]*\s*"""), "")
            .replace(Regex("""\s*```$"""), "")
            .trim()

    private fun sliceBraces(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return ""
        return text.substring(start, end + 1)
    }
}
