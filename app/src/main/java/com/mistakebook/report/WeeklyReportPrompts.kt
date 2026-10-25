package com.mistakebook.report

import com.mistakebook.domain.ErrorReason
import com.mistakebook.domain.ReviewResult

/**
 * 周报提示词（F2）。
 *
 * 统计数字先在本地算好、以"人话文本"喂给模型——让模型自己从原始数据算数
 * 是在最贵的地方做最不可靠的事；它只负责"把数字变成诊断和建议"。
 */
object WeeklyReportPrompts {

    fun system(): String = """
        你是一位备考教练，为一位正在使用错题本备考的学生写本周学习诊断。
        用户会提供一份本地统计（本周新增错题、错因分布、复习表现、薄弱知识点榜）。
        你的任务：基于这些数字写一份**简短、具体、可执行**的中文 Markdown 周报。

        硬性规则：
        1. 只输出一个 JSON 对象：{"report_markdown": "<周报 Markdown 正文>"}，不要围栏、不要解释。
        2. report_markdown 里用四个二级标题分节：本周概览 / 错因画像 / 薄弱知识点 / 下周建议。
        3. 诊断必须引用提供的数字，不许编造或外推不存在的数据；数据为零就直说"本周暂无"。
        4. 「下周建议」最多 3 条，每条必须是具体动作（做什么、做多少），不写空话。
        5. 语气鼓励但不煽情，总长度控制在 500 字以内。
    """.trimIndent()

    fun user(stats: WeeklyStats): String = buildString {
        appendLine("以下是本周（epochDay ${stats.weekStartEpochDay} ~ ${stats.weekEndEpochDay}）的本地统计，请按系统指令输出 JSON：")
        appendLine()
        appendLine("本周新增错题：${stats.newThisWeek} 道")
        appendLine("当前未掌握：${stats.totalActive} 道；已掌握：${stats.totalMastered} 道")
        appendLine()
        appendLine("错因分布（全部未删除错题）：")
        if (stats.reasonCounts.isEmpty()) {
            appendLine("（暂无错题）")
        } else {
            stats.reasonCounts.forEach { (name, count) ->
                appendLine("- ${labelReason(name)}：$count 道")
            }
        }
        appendLine()
        appendLine("本周复习表现：")
        val reviews = stats.reviewCounts.values.sum()
        if (reviews == 0) {
            appendLine("（本周暂无复习记录）")
        } else {
            stats.reviewCounts.forEach { (name, count) ->
                appendLine("- ${labelReview(name)}：$count 次")
            }
        }
        appendLine()
        appendLine("薄弱知识点榜（按出错题数排序）：")
        if (stats.topKnowledgePoints.isEmpty()) {
            appendLine("（暂无知识点数据）")
        } else {
            stats.topKnowledgePoints.forEachIndexed { index, kp ->
                appendLine("${index + 1}. ${kp.name}（${kp.count} 道）")
            }
        }
    }

    /** 枚举 name → 用户可读标签；未知 name 原样保留（兼容历史脏数据）。 */
    internal fun labelReason(name: String): String =
        ErrorReason.entries.firstOrNull { it.name == name }?.label ?: name

    internal fun labelReview(name: String): String =
        ReviewResult.entries.firstOrNull { it.name == name }?.label ?: name
}
