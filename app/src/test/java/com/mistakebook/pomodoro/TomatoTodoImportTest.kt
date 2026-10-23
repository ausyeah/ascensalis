package com.mistakebook.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 番茄TODO 导出格式的适配测试。
 *
 * 番茄TODO 的「专注历史记录」导出（.xls）结构长这样：
 *
 * ```
 * (空行)
 * (空行)
 * (空行)
 * (空行)
 * 用户:Ausyeah | 导出范围:2026-03-01 至 2026-10-24 | | 时长共计 1481 小时 2 分钟 | 共 1210 条记录
 * 专注时间 | 待办名称 | 专注时长(分钟) | 心得 | 状态 | 完成度      ← 表头在这
 * 2026-10-23 16:48 至 2026-10-23 17:39 | 整理归纳 | 51 | | 已完成 | 100%
 * ```
 *
 * 三个和普通 CSV 完全不同的地方，逐一钉死：
 * 1. **表头在第 6 行**，前面 5 行是元信息（用户、导出范围、时长共计）；
 * 2. **列名是「专注时间 / 待办名称 / 专注时长(分钟)」**，
 *    时长列的表头里带着「分钟」，且值是纯数字；
 * 3. **有「状态」列**，标着「已完成 / 已放弃」——放弃的不能算专注时长。
 */
class TomatoTodoImportTest {

    /**
     * 番茄TODO 导出的完整表格（含元信息行）。
     *
     * 列序：专注时间 / 待办名称 / 专注时长(分钟) / 心得 / 状态 / 完成度
     */
    private fun tomatoTable(): List<List<String>> = listOf(
        listOf("", "", "", "", "", ""),
        listOf("", "", "", "", "", ""),
        listOf("", "", "", "", "", ""),
        listOf("", "", "", "", "", ""),
        listOf(
            "用户:Ausyeah",
            "导出范围:2026-03-01 至 2026-10-24",
            "",
            "时长共计 1481 小时 2 分钟 (不包含已放弃的记录)",
            "共 1210 条记录",
            ""
        ),
        listOf("专注时间", "待办名称", "专注时长(分钟)", "心得", "状态", "完成度"),
        listOf("2026-10-23 16:48 至 2026-10-23 17:39", "整理归纳", "51", "", "已完成", "100%"),
        listOf("2026-10-23 09:08 至 2026-10-23 11:52", "通信原理提高篇三轮复习", "164", "", "已完成", "100%"),
        listOf("2026-10-22 19:29 至 2026-10-22 22:51", "通信原理提高篇三轮复习", "202", "", "已完成", "100%"),
        listOf("2026-10-22 13:54 至 2026-10-22 17:31", "通信原理提高篇三轮复习", "217", "", "已放弃", "30%"),
        listOf("2026-10-21 21:17 至 2026-10-21 22:06", "英语作文", "49", "Unit 6", "已完成", "100%")
    )

    @Test
    fun `表头在第六行也能认出来`() {
        val r = CsvFocusImporter.parseTable(tomatoTable())
        assertFalse("表头应被识别", r.headerUnrecognized)
        assertEquals(4, r.rows.size)
    }

    @Test
    fun `时长列按分钟解析`() {
        val r = CsvFocusImporter.parseTable(tomatoTable())
        // 51 分钟 = 3060 秒
        assertEquals(3060L, r.rows[0].durationSec)
        // 164 分钟 = 9840 秒
        assertEquals(9840L, r.rows[1].durationSec)
        // 202 分钟 = 12120 秒（217 分钟那条是「已放弃」，不入账）
        assertEquals(12120L, r.rows[2].durationSec)
        // 49 分钟 = 2940 秒
        assertEquals(2940L, r.rows[3].durationSec)
    }

    @Test
    fun `任务名与日期正确归档`() {
        val r = CsvFocusImporter.parseTable(tomatoTable())
        assertEquals("整理归纳", r.rows[0].taskName)
        assertEquals("2026-10-23", r.rows[0].day)
        assertEquals("2026-10-22", r.rows[2].day)
        // 番茄TODO 没有学科列 → 归到「未分类」
        assertNull(r.rows[0].subject)
    }

    @Test
    fun `已放弃的记录不计入专注时长`() {
        val r = CsvFocusImporter.parseTable(tomatoTable())
        // 5 行数据，1 行已放弃 → 只剩 4 条
        assertEquals(4, r.rows.size)
        assertEquals(1, r.skipped)
        // 那条「217 分钟 / 2026-10-22」的记录不能出现
        assertTrue(r.rows.none { it.durationSec == 217L * 60 })
        assertTrue(r.rows.none { it.day == "2026-10-22" && it.startMinute == 13 * 60 + 54 })
        // 同一天同任务的「已完成」那条要在
        assertTrue(r.rows.any { it.day == "2026-10-22" && it.startMinute == 19 * 60 + 29 })
    }

    @Test
    fun `开始时刻从时间段里取出来`() {
        val r = CsvFocusImporter.parseTable(tomatoTable())
        // 16:48 → 16*60+48 = 1008
        assertEquals(1008, r.rows[0].startMinute)
        // 09:08 → 548
        assertEquals(548, r.rows[1].startMinute)
    }

    @Test
    fun `状态列决定完成与否`() {
        val r = CsvFocusImporter.parseTable(tomatoTable())
        assertEquals(true, r.rows[0].completed)
        assertEquals(true, r.rows[3].completed)
        // 没有完成度列时状态列说了算
        val noCompletion = listOf(
            listOf("任务", "时长(分钟)", "状态"),
            listOf("刷题", "25", "已完成"),
            listOf("背单词", "30", "已放弃")
        )
        val r2 = CsvFocusImporter.parseTable(noCompletion)
        assertEquals(1, r2.rows.size)
        assertEquals(true, r2.rows[0].completed)
    }

    @Test
    fun `没有状态列时用完成度兜底`() {
        val table = listOf(
            listOf("任务", "时长(分钟)", "完成度"),
            listOf("刷题", "25", "100%"),
            listOf("背单词", "30", "40%")
        )
        val r = CsvFocusImporter.parseTable(table)
        assertEquals(2, r.rows.size)
        assertEquals(true, r.rows[0].completed)
        assertEquals(false, r.rows[1].completed)
    }

    @Test
    fun `元信息行不会被当成数据行`() {
        val r = CsvFocusImporter.parseTable(tomatoTable())
        // 元信息行里的「时长共计 1481 小时 2 分钟」不能被当成一条记录
        assertTrue(r.rows.none { it.taskName.contains("用户") })
        assertTrue(r.rows.none { it.taskName.contains("时长共计") })
        assertTrue(r.rows.all { it.taskName.isNotBlank() })
    }

    @Test
    fun `普通 CSV 表头在第一行时行为不变`() {
        val r = CsvFocusImporter.parse("分类,任务名,时长\n数学,刷真题,25\n")
        assertFalse(r.headerUnrecognized)
        assertEquals(1, r.rows.size)
        assertEquals(1500L, r.rows[0].durationSec)
        assertEquals("数学", r.rows[0].subject)
    }

    @Test
    fun `开始时刻解析的边界`() {
        assertEquals(0, CsvFocusImporter.parseStartMinute("2026-10-23 00:00"))
        assertEquals(1439, CsvFocusImporter.parseStartMinute("2026-10-23 23:59"))
        // 没有时刻 → null
        assertNull(CsvFocusImporter.parseStartMinute("2026-10-23"))
        assertNull(CsvFocusImporter.parseStartMinute(""))
        // 越界不认
        assertNull(CsvFocusImporter.parseStartMinute("2026-10-23 25:00"))
        assertNull(CsvFocusImporter.parseStartMinute("2026-10-23 10:75"))
    }
}
