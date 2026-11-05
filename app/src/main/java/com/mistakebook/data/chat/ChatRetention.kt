package com.mistakebook.data.chat

import java.time.Instant
import java.time.ZoneOffset

/**
 * 对话保留期（v0.0.121，issue #35）。
 *
 * 用户的要求一句话：「对话时间戳七天前的都丢进归档，归档三个月前的删掉」。
 * 拆成两个阈值，换算收在这一处——DAO 与仓库层只认算好的毫秒数，
 * 以后改口径（比如改成 14 天）不用动 SQL。
 *
 * 全部用 **UTC** 换算：日历月的长度随时区、夏令时变化，
 * 用 UTC 的「三个月前」保证同一条 `now` 在任何机器上算出同一个值，
 * 单测才能钉死毫秒。
 */
object ChatRetention {

    /** 最后活跃超过这么多天的会话，启动时自动归档。 */
    const val ARCHIVE_AFTER_DAYS: Long = 7L

    /** 归档超过这么多个月的会话，启动时连同附件文件一起删除。 */
    const val DELETE_AFTER_MONTHS: Long = 3L

    private const val DAY_MS: Long = 24L * 60 * 60 * 1000

    /** 自动归档线：[now] 往前数七天。 */
    fun archiveCutoff(now: Long): Long = now - ARCHIVE_AFTER_DAYS * DAY_MS

    /** 自动删除线：[now] 往前数三个日历月（UTC 口径）。 */
    fun deleteCutoff(now: Long): Long = utc(now).minusMonths(DELETE_AFTER_MONTHS).toInstant().toEpochMilli()

    private fun utc(epochMilli: Long): java.time.ZonedDateTime =
        Instant.ofEpochMilli(epochMilli).atZone(ZoneOffset.UTC)
}
