package com.mistakebook.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 专注导入解析测试。
 *
 * 导入是「把用户在其他软件里几年的记录搬进来」的一次性操作，
 * 解析错一条两条无所谓，但**整文件解析失败**或**时长单位搞错**
 * （把秒当分钟）会让图表完全不可信。这些用例就是钉住这些行为的。
 */
class CsvFocusImporterTest {

    @Test
    fun `中文表头带单位后缀`() {
        val csv = """
            分类,任务名,时长
            数学,刷真题,25分钟
            英语,背单词,30分钟
        """.trimIndent()
        val r = CsvFocusImporter.parse(csv)
        assertEquals(2, r.rows.size)
        assertEquals(1500L, r.rows[0].durationSec)
        assertEquals("数学", r.rows[0].subject)
        assertEquals("刷真题", r.rows[0].taskName)
        assertEquals(1800L, r.rows[1].durationSec)
        assertEquals(0, r.skipped)
        assertFalse(r.headerUnrecognized)
    }

    @Test
    fun `英文表头纯数字默认分钟`() {
        val csv = "category,task,duration\nMath,Study,25\n"
        val r = CsvFocusImporter.parse(csv)
        assertEquals(1, r.rows.size)
        assertEquals(1500L, r.rows[0].durationSec)
        assertEquals("Math", r.rows[0].subject)
    }

    @Test
    fun `表头是秒时纯数字按秒`() {
        val csv = "subject,task,sec\nMath,Study,90\n"
        val r = CsvFocusImporter.parse(csv)
        assertEquals(90L, r.rows[0].durationSec)
    }

    @Test
    fun `冒号时分秒三种形态`() {
        // 1:30 默认时:分；表头是秒时 1:30 按 分:秒；1:30:00 固定 时:分:秒
        val r1 = CsvFocusImporter.parse("任务,时长\n刷题,1:30\n")
        assertEquals(5400L, r1.rows[0].durationSec)

        val r2 = CsvFocusImporter.parse("task,sec\nA,1:30\n")
        assertEquals(90L, r2.rows[0].durationSec)

        val r3 = CsvFocusImporter.parse("任务,时长\n刷题,1:30:00\n")
        assertEquals(5400L, r3.rows[0].durationSec)
    }

    @Test
    fun `单位后缀与小数小时`() {
        val r = CsvFocusImporter.parse("任务,时长\nA,90m\nB,1.5h\nC,45秒\nD,2hr\n")
        assertEquals(4, r.rows.size)
        assertEquals(5400L, r.rows[0].durationSec)
        assertEquals(5400L, r.rows[1].durationSec)
        assertEquals(45L, r.rows[2].durationSec)
        assertEquals(7200L, r.rows[3].durationSec)
    }

    @Test
    fun `日期列归一与短日期补年份`() {
        val r = CsvFocusImporter.parse(
            "日期,分类,任务,时长\n2026-10-01,数学,刷题,25\n2026/10/2,英语,背单词,30\n"
        )
        assertEquals("2026-10-01", r.rows[0].day)
        assertEquals("2026-10-02", r.rows[1].day)

        val short = CsvFocusImporter.parse("日期,任务,时长\n10-03,刷题,25\n")
        assertEquals(java.time.LocalDate.now().year.toString() + "-10-03", short.rows[0].day)
    }

    @Test
    fun `没有日期列时 day 为空由调用方归今天`() {
        val r = CsvFocusImporter.parse("任务,时长\n刷题,25\n")
        assertNull(r.rows[0].day)
        assertNull(r.rows[0].subject)
    }

    @Test
    fun `脏行跳过且计数`() {
        val csv = """
            分类,任务名,时长
            数学,刷题,25分钟
            数学,,30分钟
            数学,刷题,abc
            英语,背单词,20分钟
        """.trimIndent()
        val r = CsvFocusImporter.parse(csv)
        assertEquals(2, r.rows.size)
        assertEquals(2, r.skipped)
    }

    @Test
    fun `缺任务名或时长列表头直接判不可识别`() {
        assertTrue(CsvFocusImporter.parse("分类,任务名\n数学,刷题\n").headerUnrecognized)
        assertTrue(CsvFocusImporter.parse("分类,时长\n数学,25\n").headerUnrecognized)
        assertTrue(CsvFocusImporter.parse("").headerUnrecognized)
        assertTrue(CsvFocusImporter.parse("完全不相干的表格\n1,2\n").headerUnrecognized)
    }

    @Test
    fun `引号包裹的单元格按原样取出`() {
        val csv = "\"分类\",\"任务名\",\"时长\"\n\"数学, 理科\",\"刷真题, 2020\",\"25分钟\"\n"
        val r = CsvFocusImporter.parse(csv)
        assertEquals(1, r.rows.size)
        assertEquals("数学, 理科", r.rows[0].subject)
        assertEquals("刷真题, 2020", r.rows[0].taskName)
        assertEquals(1500L, r.rows[0].durationSec)
    }

    @Test
    fun `时长超过 24 小时夹到上限`() {
        val r = CsvFocusImporter.parse("任务,时长\nA,99:00:00\n")
        assertEquals(24 * 3600L, r.rows[0].durationSec)
    }

    @Test
    fun `BOM 与空行不碍事`() {
        val csv = "\uFEFF任务,时长\n\n刷题,25\n\n"
        val r = CsvFocusImporter.parse(csv)
        assertEquals(1, r.rows.size)
        assertEquals(1500L, r.rows[0].durationSec)
    }
}
