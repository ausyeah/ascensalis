package com.mistakebook.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 思考强度档位的存取 round-trip。
 *
 * 回归：v0.0.23 及以前，设置页点「关闭」后偏好里存的是字符串 `"off"`
 * （SettingsStore.setReasoningEffort 把 OFF 的 null key 兜底成 "off"），
 * 而 ReasoningEffort.fromKey 只认 low/medium/high/max，`"off"` 落进 else
 * 分支变成 HIGH——界面上的选中态当场跳回「高」，用户看到的就是
 * 「点关闭却跳到高强度」。
 */
class ReasoningEffortTest {

    @Test
    fun `off round-trips through store key`() {
        // store 侧：OFF.key == null，写入时兜底成 "off"
        val stored = ReasoningEffort.OFF.key ?: "off"
        assertEquals("off", stored)
        assertEquals(ReasoningEffort.OFF, ReasoningEffort.fromKey(stored))
    }

    @Test
    fun `every effort round-trips`() {
        for (effort in ReasoningEffort.entries) {
            val stored = effort.key ?: "off"
            assertEquals(effort, ReasoningEffort.fromKey(stored))
        }
    }

    @Test
    fun `null key falls back to high not off`() {
        // 从没设置过（null）时应保持历史默认 HIGH，不能变成 OFF
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromKey(null))
    }

    @Test
    fun `garbage key falls back to high`() {
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromKey("ultra"))
    }

    @Test
    fun `case insensitive`() {
        assertEquals(ReasoningEffort.OFF, ReasoningEffort.fromKey("OFF"))
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.fromKey("High"))
    }
}
