package com.mistakebook.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周报统计装配（F2）的纯函数测试。
 */
class WeeklyStatsBuilderTest {

    @Test
    fun `知识点频次统计与排序截断`() {
        val jsons = listOf(
            """["数列","函数"]""",
            """["数列"]""",
            """["函数","数列","导数"]""",
            """["导数"]"""
        )
        val top = WeeklyStatsBuilder.topKnowledgePoints(jsons, limit = 2)
        assertEquals(listOf("数列" to 3, "函数" to 2), top.map { it.name to it.count })
    }

    @Test
    fun `同频知识点按名称稳定排序`() {
        val jsons = listOf("""["b","a"]""", """["a","b"]""")
        val top = WeeklyStatsBuilder.topKnowledgePoints(jsons, limit = 10)
        assertEquals("a", top[0].name)
        assertEquals("b", top[1].name)
    }

    @Test
    fun `脏 JSON 跳过不炸整份统计`() {
        val jsons = listOf("""["好数据"]""", "不是JSON", """{"破":1}""")
        val top = WeeklyStatsBuilder.topKnowledgePoints(jsons, limit = 10)
        assertEquals(1, top.size)
        assertEquals("好数据", top[0].name)
    }

    @Test
    fun `空白知识点不进榜`() {
        val top = WeeklyStatsBuilder.topKnowledgePoints(listOf("""["", "  "]"""), limit = 10)
        assertTrue(top.isEmpty())
    }

    @Test
    fun `statsJson 往返不丢字段`() {
        val stats = WeeklyStats(
            weekStartEpochDay = 20000,
            weekEndEpochDay = 20006,
            totalActive = 8,
            totalMastered = 2,
            newThisWeek = 5,
            reasonCounts = mapOf("CALCULATION" to 4, "CONCEPT" to 3),
            reviewCounts = mapOf("CORRECT" to 6),
            topKnowledgePoints = listOf(WeeklyStats.KnowledgePointCount("数列", 3))
        )
        val restored = WeeklyStats.fromJson(stats.toJson())!!
        assertEquals(stats, restored)
    }

    @Test
    fun `fromJson 对垃圾输入返回 null 而不是抛异常`() {
        assertEquals(null, WeeklyStats.fromJson(""))
        assertEquals(null, WeeklyStats.fromJson("不是JSON"))
    }
}
