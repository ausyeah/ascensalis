package com.mistakebook.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 成就徽章解锁记录（v0.0.51 T10）。
 *
 * 一行 = 一枚已解锁徽章。判定逻辑在纯函数 [com.mistakebook.domain.BadgeEngine]，
 * 本表只做「解锁了没有、什么时候解锁」的持久化；进度快照字段
 * [progressJson] 记录解锁瞬间的统计值，成就墙可展示「以什么成绩达成」。
 *
 * 徽章 key 为稳定字符串（见 [com.mistakebook.domain.Badge]），主键直接用它：
 * 重复解锁 = 主键冲突，天然防重（判定侧再兜一层「已在表内不重复写」）。
 */
@Entity(tableName = "badges")
data class BadgeRecord(
    @PrimaryKey val key: String,
    /** 解锁时间（epoch millis）。 */
    val unlockedAt: Long,
    /** 解锁瞬间的进度快照，如 {"current":100,"target":100}。 */
    val progressJson: String
)
