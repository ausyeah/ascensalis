package com.mistakebook.data.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 对话保留期阈值（v0.0.121，issue #35）。
 *
 * 用户的要求一句话：「对话时间戳七天前的都丢进归档，归档三个月前的删掉」。
 * 阈值换算收在 [ChatRetention] 一处，这里钉死两个方向都不许漂：
 * - 七天用固定毫秒差，改口径必须显式动常量；
 * - 三个月按日历月算（2026-08-05 的三个月前是 2026-05-05，不是固定 90 天
 *   算出的 08-07）；口径是 UTC，同一条 now 在任何机器上结果一致。
 */
class ChatRetentionTest {

    /** 2026-11-05T12:00:00Z。 */
    private val now: Long = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli()

    private fun Long.utcDate(): String = DateTimeFormatter.ISO_LOCAL_DATE
        .format(Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC))

    @Test
    fun `归档线是七天前`() {
        assertEquals(now - 7L * 24 * 60 * 60 * 1000, ChatRetention.archiveCutoff(now))
        assertEquals("2026-10-29", ChatRetention.archiveCutoff(now).utcDate())
    }

    @Test
    fun `删除线是三个日历月前`() {
        assertEquals("2026-08-05", ChatRetention.deleteCutoff(now).utcDate())
    }

    @Test
    fun `跨年与月末都对齐日历`() {
        // 2026-05-31 的三个月前是 2026-02-28（2 月没有 31 日，钳到月末）
        val may31: Long = Instant.parse("2026-05-31T12:00:00Z").toEpochMilli()
        assertEquals("2026-02-28", ChatRetention.deleteCutoff(may31).utcDate())

        // 跨年：2026-02-01 的三个月前是 2025-11-01
        val feb1: Long = Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()
        assertEquals("2025-11-01", ChatRetention.deleteCutoff(feb1).utcDate())
    }

    @Test
    fun `常量口径不许悄悄改`() {
        assertEquals(7L, ChatRetention.ARCHIVE_AFTER_DAYS)
        assertEquals(3L, ChatRetention.DELETE_AFTER_MONTHS)
    }
}
