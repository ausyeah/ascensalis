package com.mistakebook.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 每日新词上限的收敛单测（v0.0.86 用户反馈：设置页上限「乱了」）。
 *
 * 坏在现场长这样：旧版「0 = 不限」，v0.0.84 把档位换成 50/100/200/自定义
 * （自定义弹窗限定 1..500）之后，0 在新 UI 里**没有任何一项能表达**。
 * 于是一个从旧版升上来的用户在设置页看到「自定义（0）」：四个 chip
 * 一个都不选中，实际行为是不限、界面却显示 0，怎么看都对不上。
 *
 * 修法是把读取侧也收敛进合法区间（[normalizeDailyNewLimit]），
 * 这些用例钉住的就是「任何历史脏值都必须落进 UI 能表达的档位」。
 */
class DailyNewLimitNormalizeTest {

    @Test
    fun `未设置时回落默认档 20`() {
        assertEquals(20, normalizeDailyNewLimit(null))
        assertEquals(DEFAULT_DAILY_NEW_LIMIT, normalizeDailyNewLimit(null))
    }

    @Test
    fun `合法值原样透传`() {
        listOf(1, 20, 37, 50, 100, 200, 500).forEach { value ->
            assertEquals(value, normalizeDailyNewLimit(value))
        }
    }

    @Test
    fun `旧版 0 不限被收敛成默认档而不是显示成自定义 0`() {
        // 0 曾是「不限」，新区间里不存在这个语义，必须收敛掉
        assertEquals(DEFAULT_DAILY_NEW_LIMIT, normalizeDailyNewLimit(0))
    }

    @Test
    fun `越界值收敛：低于下限回落默认档而不是钳成 1，高于上限收到上限`() {
        // 低于下限（含旧版 0 与脏负数）绝不能钳成 1：那等于把用户掐到
        // 「一天一个新词」，比显示错更严重。
        assertEquals(DEFAULT_DAILY_NEW_LIMIT, normalizeDailyNewLimit(-5))
        assertEquals(MAX_DAILY_NEW_LIMIT, normalizeDailyNewLimit(5000))
    }

    @Test
    fun `收敛结果永远落在设置页能选中的区间内`() {
        // 设置页四个 chip 的选中判定是 `current == 50/100/200` 或
        // `current != 50/100/200`（自定义档），所以只要值在区间内
        // 就必然有一个 chip 处于选中态——这正是「UI 不乱」的充要条件。
        listOf(null, 0, -1, 1, 20, 99, 200, 500, 9999).forEach { stored ->
            val normalized = normalizeDailyNewLimit(stored)
            assertEquals(true, normalized in MIN_DAILY_NEW_LIMIT..MAX_DAILY_NEW_LIMIT)
        }
    }
}
