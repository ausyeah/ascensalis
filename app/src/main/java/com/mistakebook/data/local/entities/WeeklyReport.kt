package com.mistakebook.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 错因诊断周报（F2）。
 *
 * 一周一条，重生成覆盖（weekStartEpochDay 唯一索引 + REPLACE）。
 * [statsJson] 存生成时的聚合数据快照：报告结论能追溯到当时的输入，
 * 下周对比"上周说要加强的知识点这周错少了没有"也靠它。
 */
@Entity(
    tableName = "weekly_reports",
    indices = [Index(value = ["weekStartEpochDay"], unique = true)]
)
data class WeeklyReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 本周周一的 epochDay。作为周的标识，也是唯一索引键。 */
    val weekStartEpochDay: Long,
    val weekEndEpochDay: Long,
    /** LLM 生成的中文 Markdown 报告正文。 */
    val markdown: String,
    /** 生成时的聚合统计快照（JSON），审计与周间对比用。 */
    val statsJson: String,
    val createdAt: Long
)
