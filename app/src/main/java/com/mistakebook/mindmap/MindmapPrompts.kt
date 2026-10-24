package com.mistakebook.mindmap

import com.mistakebook.report.WeeklyStats

/**
 * 思维导图的提示词与薄弱点标记（F4）。
 *
 * 标记规则固定在本地（🔴 ≥3 道 / 🟠 2 道），不让模型自己决定"什么算薄弱"——
 * 阈值是产品决策，不是模型的判断题；模型只负责把知识点组织进章节。
 */
object MindmapPrompts {

    /** 🔴 阈值：出错 ≥ 此数的知识点标为薄弱（红色 emoji 前缀）。 */
    const val WEAK_THRESHOLD = 3

    /** 🟠 阈值：出错恰好 2 道的标为次薄弱。 */
    const val MILD_THRESHOLD = 2

    fun system(subjectName: String): String = """
        你是知识体系整理师。用户会提供一个学科名和他的薄弱知识点清单（含出错题数）。
        请生成该学科的知识体系思维导图，并把他的薄弱知识点放到正确的章节里。

        硬性规则：
        1. 只输出一个 JSON 对象：{"mindmap_markdown": "<markdown>"}，不要围栏、不要解释。
        2. markdown 结构：一个一级标题（学科名），二级标题为章节，三级及以下用列表项。
           层级最多 3 层，总节点控制在 60 个以内。
        3. **用户清单里的每个知识点必须原样出现**（可去掉我加的 emoji 前缀与题数后缀，
           但名称文字不得改动），放在正确的章节下。
        4. 知识点后面的标记格式保持我给你的原样（🔴/🟠 前缀 + "(N道)" 后缀），
           那是本地统计，不许改数字。
        5. 清单之外可以补充该学科的标准章节作为骨架，但不添加虚构的具体知识点。
        6. 列表缩进用 2 个空格。
    """.trimIndent()

    fun user(subjectName: String, weakPoints: List<WeeklyStats.KnowledgePointCount>): String = buildString {
        appendLine("学科：$subjectName")
        appendLine("我的薄弱知识点清单（按出错题数降序，已带标记）：")
        if (weakPoints.isEmpty()) {
            appendLine("（暂无错题数据，请只按学科标准知识体系生成）")
        } else {
            weakPoints.forEach { kp ->
                appendLine("- ${mark(kp)}")
            }
        }
    }

    /** 标记格式：🔴(≥3道) / 🟠(2道) / 无前缀(1道)，统一带 "(N道)" 后缀。 */
    fun mark(kp: WeeklyStats.KnowledgePointCount): String {
        val prefix = when {
            kp.count >= WEAK_THRESHOLD -> "🔴"
            kp.count >= MILD_THRESHOLD -> "🟠"
            else -> ""
        }
        return "${prefix}${kp.name}（${kp.count}道）"
    }
}
