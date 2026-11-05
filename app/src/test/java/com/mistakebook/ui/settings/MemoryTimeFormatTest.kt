package com.mistakebook.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 记忆库版本时间（v0.0.115）。
 *
 * 巡检发现同一台设备上两套日期写法：记忆库 `2026-11-4`，资料库 `2026-11-04`。
 * 不影响功能，但用户读起来像两个产品，所以统一补零并钉死。
 */
class MemoryTimeFormatTest {

    @Test
    fun `月日补零且带时分`() {
        val millis = ZonedDateTime.of(2026, 11, 4, 13, 52, 0, 0, ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        assertEquals("2026-11-04 13:52", formatMemoryTime(millis))
    }

    @Test
    fun `个位数月份也补零`() {
        val millis = ZonedDateTime.of(2026, 3, 7, 9, 5, 0, 0, ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        assertEquals("2026-03-07 09:05", formatMemoryTime(millis))
    }

    @Test
    fun `没有版本时不显示时间`() {
        assertEquals("", formatMemoryTime(0L))
    }
}
